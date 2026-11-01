package com.iptv.tvapp.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.iptv.tvapp.data.PreferencesHelper
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.model.PlaylistSource
import com.iptv.tvapp.util.NetworkUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

class TvWebServer(
    private val port: Int = 8080,
    private val context: Context,
    private val prefs: PreferencesHelper,
    private val channelProvider: () -> List<Channel>,
    private val currentPlayingProvider: () -> Channel?,
    private val onPlayChannel: (Channel) -> Unit,
    private val onSendKey: (String) -> Unit,
    private val onPlaylistChanged: () -> Unit,
    private val onChannelsUpdated: () -> Unit,
    private val onEngineChanged: (() -> Unit)? = null
) {

    private var serverSocket: ServerSocket? = null
    private val threadPool = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        threadPool.execute {
            try {
                serverSocket = ServerSocket(port)
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                // Server stopped or socket error
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        threadPool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val output = s.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val method = parts[0]
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')

                // Đọc Headers để tìm Content-Length
                var contentLength = 0
                var headerLine = reader.readLine()
                while (!headerLine.isNullOrBlank()) {
                    if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    headerLine = reader.readLine()
                }

                // Đọc Body nếu là POST
                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val count = reader.read(buffer, readTotal, contentLength - readTotal)
                        if (count == -1) break
                        readTotal += count
                    }
                    String(buffer, 0, readTotal)
                } else ""

                routeRequest(method, path, body, output)
            }
        } catch (e: Exception) {
            // Client connection closed
        }
    }

    private fun routeRequest(method: String, path: String, body: String, output: OutputStream) {
        when {
            method == "GET" && (path == "/" || path.startsWith("/index")) -> {
                sendResponse(output, "text/html; charset=utf-8", WebPageTemplate.getHtml())
            }
            method == "GET" && path == "/api/status" -> {
                val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
                val playing = currentPlayingProvider()
                val json = JSONObject().apply {
                    put("ip", ip)
                    put("port", port)
                    put("nowPlaying", playing?.let { "[${String.format("%02d", it.number)}] ${it.name}" } ?: "")
                    put("streamUrl", playing?.streamUrl ?: "")
                }
                sendResponse(output, "application/json; charset=utf-8", json.toString())
            }
            method == "GET" && path == "/api/channels" -> {
                val channels = channelProvider()
                val jsonArray = JSONArray()
                channels.forEach { ch ->
                    val obj = JSONObject().apply {
                        put("number", ch.number)
                        put("name", ch.name)
                        put("logoUrl", ch.logoUrl ?: "")
                        put("streamUrl", ch.streamUrl)
                        put("groupTitle", ch.groupTitle)
                        put("isFavorite", ch.isFavorite)
                    }
                    jsonArray.put(obj)
                }
                sendResponse(output, "application/json; charset=utf-8", jsonArray.toString())
            }
            method == "GET" && path == "/api/playlists" -> {
                val list = prefs.getPlaylists()
                val jsonArray = JSONArray()
                list.forEach { pl ->
                    val obj = JSONObject().apply {
                        put("id", pl.id)
                        put("name", pl.name)
                        put("url", pl.url)
                        put("isSelected", pl.isSelected)
                    }
                    jsonArray.put(obj)
                }
                sendResponse(output, "application/json; charset=utf-8", jsonArray.toString())
            }
            method == "GET" && path == "/api/settings" -> {
                val json = JSONObject().apply {
                    put("playerEngine", prefs.playerEngine)
                    put("deinterlaceMode", prefs.deinterlaceMode)
                    put("httpUserAgent", prefs.httpUserAgent)
                    put("autoStartOnBoot", prefs.autoStartOnBoot)
                    put("startupMode", prefs.startupMode)
                    put("defaultStartupChannelName", prefs.defaultStartupChannelName ?: "")
                    put("udpxyServer", prefs.udpxyServer)
                }
                sendResponse(output, "application/json; charset=utf-8", json.toString())
            }
            method == "POST" && path == "/api/key" -> {
                val json = if (body.isNotBlank()) JSONObject(body) else JSONObject()
                val action = json.optString("action", "")
                mainHandler.post { onSendKey(action) }
                val playing = currentPlayingProvider()
                val resp = JSONObject().apply {
                    put("success", true)
                    put("nowPlaying", playing?.let { "[${String.format("%02d", it.number)}] ${it.name}" } ?: "")
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/play" -> {
                val json = JSONObject(body)
                val streamUrl = json.getString("streamUrl")
                val channel = channelProvider().firstOrNull { it.streamUrl == streamUrl }
                if (channel != null) {
                    mainHandler.post { onPlayChannel(channel) }
                    val resp = JSONObject().apply {
                        put("success", true)
                        put("channelName", channel.name)
                    }
                    sendResponse(output, "application/json; charset=utf-8", resp.toString())
                } else {
                    sendResponse(output, "application/json; charset=utf-8", """{"success":false,"error":"Channel not found"}""")
                }
            }
            method == "POST" && path == "/api/favorite/toggle" -> {
                val json = JSONObject(body)
                val streamUrl = json.getString("streamUrl")
                val isFav = prefs.toggleFavorite(streamUrl)
                mainHandler.post { onChannelsUpdated() }
                val resp = JSONObject().apply {
                    put("success", true)
                    put("isFavorite", isFav)
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/playlist/add" -> {
                val json = JSONObject(body)
                val name = json.getString("name").trim()
                val url = json.getString("url").trim()
                val selectImmediately = json.optBoolean("selectImmediately", true)
                val list = prefs.getPlaylists()
                val newId = UUID.randomUUID().toString()
                if (selectImmediately) {
                    list.forEach { it.isSelected = false }
                }
                list.add(PlaylistSource(newId, name, url, selectImmediately))
                prefs.savePlaylists(list)
                mainHandler.post { onPlaylistChanged() }
                val resp = JSONObject().apply {
                    put("success", true)
                    put("id", newId)
                    put("name", name)
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/playlist/upload" -> {
                val json = JSONObject(body)
                val filename = json.optString("filename", "playlist.m3u")
                var name = json.optString("name", "").trim()
                if (name.isBlank()) {
                    name = filename.substringBeforeLast(".")
                }
                val content = json.getString("content")
                val newId = UUID.randomUUID().toString()
                val targetFile = File(context.filesDir, "playlist_${newId.replace("-", "_")}.m3u")
                targetFile.writeText(content, Charsets.UTF_8)

                val selectImmediately = json.optBoolean("selectImmediately", true)
                val list = prefs.getPlaylists()
                if (selectImmediately) {
                    list.forEach { it.isSelected = false }
                }
                list.add(PlaylistSource(newId, name, "file://${targetFile.absolutePath}", selectImmediately))
                prefs.savePlaylists(list)
                mainHandler.post { onPlaylistChanged() }

                val resp = JSONObject().apply {
                    put("success", true)
                    put("name", name)
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/playlist/select" -> {
                val json = JSONObject(body)
                val id = json.getString("id")
                prefs.setActivePlaylist(id)
                mainHandler.post { onPlaylistChanged() }
                val resp = JSONObject().apply { put("success", true) }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/playlist/delete" -> {
                val json = JSONObject(body)
                val id = json.getString("id")
                val list = prefs.getPlaylists()
                if (list.size > 1) {
                    val removed = list.firstOrNull { it.id == id }
                    list.removeAll { it.id == id }
                    if (removed != null && removed.url.startsWith("file://")) {
                        try {
                            val f = File(removed.url.removePrefix("file://"))
                            if (f.exists()) f.delete()
                        } catch (e: Exception) {}
                    }
                    if (list.none { it.isSelected }) {
                        list[0].isSelected = true
                    }
                    prefs.savePlaylists(list)
                    mainHandler.post { onPlaylistChanged() }
                    val resp = JSONObject().apply { put("success", true) }
                    sendResponse(output, "application/json; charset=utf-8", resp.toString())
                } else {
                    val resp = JSONObject().apply {
                        put("success", false)
                        put("error", "Cần giữ lại ít nhất 1 danh sách!")
                    }
                    sendResponse(output, "application/json; charset=utf-8", resp.toString())
                }
            }
            method == "POST" && path == "/api/settings/decoder" -> {
                val json = JSONObject(body)
                val engine = json.optString("playerEngine", PreferencesHelper.ENGINE_VLC)
                prefs.playerEngine = engine
                mainHandler.post { onEngineChanged?.invoke() }
                val resp = JSONObject().apply {
                    put("success", true)
                    put("playerEngine", prefs.playerEngine)
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/settings/deinterlace" -> {
                val json = JSONObject(body)
                val mode = json.optString("deinterlaceMode", PreferencesHelper.DEINTERLACE_YADIF)
                prefs.deinterlaceMode = mode
                mainHandler.post { onEngineChanged?.invoke() }
                val resp = JSONObject().apply {
                    put("success", true)
                    put("deinterlaceMode", prefs.deinterlaceMode)
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/settings/useragent" -> {
                val json = JSONObject(body)
                val ua = json.optString("httpUserAgent", PreferencesHelper.DEFAULT_USER_AGENT).trim()
                prefs.httpUserAgent = ua
                mainHandler.post {
                    onPlaylistChanged()
                    onEngineChanged?.invoke()
                }
                val resp = JSONObject().apply {
                    put("success", true)
                    put("httpUserAgent", prefs.httpUserAgent)
                }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/settings/boot" -> {
                val json = JSONObject(body)
                if (json.has("autoStartOnBoot")) {
                    prefs.autoStartOnBoot = json.getBoolean("autoStartOnBoot")
                }
                prefs.startupMode = json.optString("startupMode", PreferencesHelper.MODE_FIRST_CHANNEL)
                val resp = JSONObject().apply { put("success", true) }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "POST" && path == "/api/settings/udpxy" -> {
                val json = JSONObject(body)
                val newUdpxy = json.optString("udpxyServer", "").trim()
                prefs.udpxyServer = newUdpxy
                mainHandler.post { onPlaylistChanged() }
                val resp = JSONObject().apply { put("success", true) }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            method == "GET" && path == "/api/backup" -> {
                val json = prefs.createBackupJson()
                val bytes = json.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Disposition: attachment; filename=\"tvplay_backup.json\"\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                output.write(header.toByteArray(Charsets.UTF_8))
                output.write(bytes)
                output.flush()
            }
            method == "POST" && path == "/api/restore" -> {
                val success = prefs.restoreFromBackupJson(body)
                if (success) {
                    mainHandler.post {
                        onPlaylistChanged()
                        onChannelsUpdated()
                        onEngineChanged?.invoke()
                    }
                }
                val resp = JSONObject().apply { put("success", success) }
                sendResponse(output, "application/json; charset=utf-8", resp.toString())
            }
            else -> {
                val resp = "404 Not Found"
                val header = "HTTP/1.1 404 Not Found\r\nContent-Length: ${resp.length}\r\nConnection: close\r\n\r\n"
                output.write((header + resp).toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }
    }

    private fun sendResponse(output: OutputStream, contentType: String, data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}

package com.iptv.tvapp.data

import android.content.Context
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.parser.M3UParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class PlaylistManager(private val context: Context) {

    private val prefs = PreferencesHelper(context)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getCacheFile(playlistId: String): File {
        return File(context.cacheDir, "playlist_${playlistId.replace("-", "_")}.m3u")
    }

    /**
     * Tự động thay thế hoặc bổ sung tham số udpxy=server:port nếu người dùng đã cấu hình server udpxy
     */
    fun resolvePlaylistUrl(rawUrl: String): String {
        val server = prefs.udpxyServer.trim()
        if (server.isBlank()) return rawUrl

        return when {
            rawUrl.contains("udpxy=", ignoreCase = true) -> {
                rawUrl.replace(Regex("(?i)udpxy=[^&]+"), "udpxy=$server")
            }
            rawUrl.contains("121124.xyz/tv/fpt", ignoreCase = true) -> {
                val separator = if (rawUrl.contains("?")) "&" else "?"
                "$rawUrl${separator}udpxy=$server"
            }
            else -> rawUrl
        }
    }

    /**
     * Tải danh sách kênh từ URL hoặc file cache theo PlaylistSource
     */
    suspend fun loadPlaylist(playlistId: String, url: String, forceRefresh: Boolean = false): List<Category> =
        withContext(Dispatchers.IO) {
            val channels = mutableListOf<Channel>()
            val cacheFile = getCacheFile(playlistId)
            val effectiveUrl = resolvePlaylistUrl(url)

            try {
                if (isLocalSource(effectiveUrl)) {
                    loadFromLocal(effectiveUrl, cacheFile)
                } else {
                    if (forceRefresh || !cacheFile.exists() || cacheFile.length() == 0L) {
                        downloadAndCache(effectiveUrl, cacheFile)
                    }
                }

                if (cacheFile.exists() && cacheFile.length() > 0L) {
                    val stream = FileInputStream(cacheFile)
                    channels.addAll(M3UParser.parse(stream, baseUrl = effectiveUrl))
                }
            } catch (e: Exception) {
                // Nếu tải lỗi mà có cache cũ, đọc từ cache
                if (cacheFile.exists() && cacheFile.length() > 0L && channels.isEmpty()) {
                    val stream = FileInputStream(cacheFile)
                    channels.addAll(M3UParser.parse(stream, baseUrl = effectiveUrl))
                } else {
                    throw e
                }
            }

            if (channels.isEmpty()) {
                throw Exception("Không tìm thấy kênh nào trong danh sách. Vui lòng kiểm tra lại link M3U.")
            }

            val favoriteUrls = prefs.getFavoriteUrls()
            val defaultStartupUrl = prefs.defaultStartupChannelUrl

            // Gán lại số thứ tự kênh và trạng thái yêu thích, mặc định
            channels.forEachIndexed { index, channel ->
                channel.number = index + 1
                channel.isFavorite = favoriteUrls.contains(channel.streamUrl)
                channel.isDefaultStartup = (channel.streamUrl == defaultStartupUrl)
            }

            // Gom nhóm kênh theo Category
            return@withContext groupChannels(channels)
        }

    private fun downloadAndCache(url: String, targetFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: Không thể tải danh sách kênh")
        }

        val body = response.body ?: throw Exception("Nội dung tải về rỗng")
        FileOutputStream(targetFile).use { output ->
            body.byteStream().copyTo(output)
        }
    }

    fun isLocalSource(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("/")
    }

    private fun loadFromLocal(url: String, targetFile: File) {
        val trimmed = url.trim()
        val inputStream = when {
            trimmed.startsWith("content://", ignoreCase = true) -> {
                context.contentResolver.openInputStream(android.net.Uri.parse(trimmed))
                    ?: throw Exception("Không thể mở đường dẫn: $trimmed")
            }
            trimmed.startsWith("file://", ignoreCase = true) -> {
                val filePath = android.net.Uri.parse(trimmed).path ?: trimmed.substring(7)
                val file = File(filePath)
                if (!file.exists()) throw Exception("File không tồn tại: $filePath")
                FileInputStream(file)
            }
            else -> {
                val file = File(trimmed)
                if (!file.exists()) throw Exception("File không tồn tại: $trimmed")
                FileInputStream(file)
            }
        }

        inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    fun groupChannels(channels: List<Channel>): List<Category> {
        val result = mutableListOf<Category>()

        if (channels.isEmpty()) return result

        // 1. Nhóm YÊU THÍCH (nếu có kênh yêu thích)
        val favChannels = channels.filter { it.isFavorite }
        if (favChannels.isNotEmpty()) {
            result.add(Category("★ KÊNH YÊU THÍCH", favChannels.toMutableList()))
        }

        // 2. Nhóm "Tất cả" chứa toàn bộ kênh
        val allCategory = Category("Tất cả", channels.toMutableList())
        result.add(allCategory)

        // 3. Gom theo nhóm trong M3U (group-title)
        val groupedMap = channels.groupBy { it.groupTitle }
        for ((groupName, groupChannels) in groupedMap) {
            if (groupName.isNotBlank() && groupName != "Tất cả") {
                result.add(Category(groupName, groupChannels.toMutableList()))
            }
        }

        return result
    }
}

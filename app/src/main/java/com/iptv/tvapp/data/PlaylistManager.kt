package com.iptv.tvapp.data

import android.content.Context
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.parser.M3UParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PlaylistManager(private val context: Context) {

    private val prefs = PreferencesHelper(context)
    val sportsLiveManager = SportsLiveManager(context)

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val list = cookieStore.getOrPut(url.host) { CopyOnWriteArrayList() }
                list.addAll(cookies)
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .build()

    companion object {
        // Danh sách các User-Agent xoay vòng: Mặc định ưu tiên FPT Play Box chuẩn IPTV
        val USER_AGENTS = listOf(
            // 1. FPT Play Box ZTE B866V2F (Chuẩn nhận diện gốc của đài IPTV)
            PreferencesHelper.DEFAULT_USER_AGENT,
            // 2. TiviMate IPTV Player (Danh tính chuẩn IPTV Android TV phổ biến)
            "TiviMate/5.0.4 (Android TV)",
            // 3. OTT Navigator IPTV Player (Danh tính chuẩn trình phát IPTV OTT Navigator)
            "OTT Navigator/1.6.9.1 (Android TV; Android 12; arm64-v8a)",
            // 4. Mobile Android Chrome (Trình duyệt điện thoại Android chính thống)
            "Mozilla/5.0 (Linux; Android 14; Mobile; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            // 5. Mobile iOS Safari (Trình duyệt điện thoại iPhone)
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1",
            // 6. VLC Media Player (Đầu phát đa phương tiện IPTV chuẩn)
            "VLC/3.0.18 LibVLC/3.0.18"
        )
    }

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
            val baseCategories = groupChannels(channels)

            // Tự động tích hợp các danh mục thể thao trực tiếp hôm nay (Gà Vàng, Tiếu Lâm, Xôi Lạc, Cà Khịa, Chuối Chiên)
            return@withContext try {
                val sportsCategories = sportsLiveManager.getSportsCategories(forceRefresh = forceRefresh)
                if (sportsCategories.isNotEmpty()) {
                    val merged = mutableListOf<Category>()
                    val favCat = baseCategories.firstOrNull { it.name == "★ KÊNH YÊU THÍCH" }
                    if (favCat != null) merged.add(favCat)
                    merged.addAll(sportsCategories)
                    baseCategories.filter { it.name != "★ KÊNH YÊU THÍCH" }.forEach { merged.add(it) }
                    merged
                } else {
                    baseCategories
                }
            } catch (e: Exception) {
                baseCategories
            }
        }

    private fun downloadAndCache(url: String, targetFile: File) {
        var lastException: Exception? = null
        var downloadedBytes: ByteArray? = null

        for (ua in USER_AGENTS) {
            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/plain, audio/x-mpegurl, application/x-mpegurl, text/html, */*")
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")

                if (ua.contains("Mobile") || ua.contains("Android")) {
                    requestBuilder.header("sec-ch-ua-mobile", "?1")
                    requestBuilder.header("sec-ch-ua-platform", "\"Android\"")
                    requestBuilder.header("Sec-Fetch-Mode", "navigate")
                    requestBuilder.header("Sec-Fetch-Dest", "document")
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                if (response.isSuccessful) {
                    val bodyBytes = response.body?.bytes()
                    if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                        // Kiểm tra xem dữ liệu có phải là HTML hay không
                        val preview = String(bodyBytes.take(512).toByteArray(), Charsets.UTF_8).trim().lowercase()
                        val isHtml = preview.startsWith("<!doctype html") || preview.startsWith("<html")

                        if (!isHtml) {
                            // Dữ liệu M3U hoặc text danh sách hợp lệ!
                            downloadedBytes = bodyBytes
                            break
                        } else {
                            // Nội dung trả về là HTML -> Thử tìm kiếm link .m3u / .m3u8 bên trong HTML
                            val fullHtml = String(bodyBytes, Charsets.UTF_8)
                            val extractedM3uUrl = findM3uLinkInHtml(fullHtml, url)
                            if (extractedM3uUrl != null && extractedM3uUrl != url) {
                                try {
                                    val redirectReq = Request.Builder()
                                        .url(extractedM3uUrl)
                                        .header("User-Agent", ua)
                                        .build()
                                    val redResp = httpClient.newCall(redirectReq).execute()
                                    if (redResp.isSuccessful) {
                                        val redBytes = redResp.body?.bytes()
                                        if (redBytes != null && redBytes.isNotEmpty()) {
                                            downloadedBytes = redBytes
                                            break
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Bỏ qua lỗi để thử UA tiếp theo
                                }
                            }

                            // Thử kiểm tra xem trong HTML có nội dung #EXTM3U/EXTINF dạng pre/code hay không
                            val extractedContent = extractM3uContentFromHtml(fullHtml)
                            if (extractedContent != null) {
                                downloadedBytes = extractedContent.toByteArray(Charsets.UTF_8)
                                break
                            }

                            lastException = Exception("Nguồn online trả về trang HTML thay vì danh sách M3U. Bạn có thể tải file về điện thoại và truyền lên TV qua Web Remote.")
                        }
                    }
                } else {
                    lastException = Exception("HTTP ${response.code}: Không thể tải danh sách kênh")
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (downloadedBytes != null && downloadedBytes.isNotEmpty()) {
            FileOutputStream(targetFile).use { output ->
                output.write(downloadedBytes)
            }
        } else {
            throw lastException ?: Exception("Không thể tải danh sách kênh từ URL này.")
        }
    }

    private fun findM3uLinkInHtml(html: String, baseUrl: String): String? {
        // 1. Thẻ meta refresh chuyển tiếp
        val metaRegex = Regex("(?i)<meta\\s+http-equiv=[\"']refresh[\"']\\s+content=[\"']\\d+;\\s*url=([^\"']+)[\"']")
        metaRegex.find(html)?.groupValues?.get(1)?.let { return resolveRelativeUrl(baseUrl, it.trim()) }

        // 2. Thẻ href hoặc src trỏ tới .m3u hoặc .m3u8
        val linkRegex = Regex("(?i)(?:href|src)=[\"']([^\"'\\s>]+\\.(?:m3u8?|txt)(?:\\?[^\"'\\s>]*)?)[\"']")
        linkRegex.find(html)?.groupValues?.get(1)?.let { return resolveRelativeUrl(baseUrl, it.trim()) }

        // 3. Link tuyệt đối chứa .m3u trong toàn văn bản
        val urlRegex = Regex("(?i)https?://[^\"'\\s<>]+\\.(?:m3u8?|txt)(?:\\?[^\"'\\s<>]*)?")
        urlRegex.find(html)?.value?.let { return it.trim() }

        return null
    }

    private fun extractM3uContentFromHtml(html: String): String? {
        val lower = html.lowercase()
        val startIndex = lower.indexOf("#extm3u").takeIf { it != -1 } ?: lower.indexOf("#extinf")
        if (startIndex != -1) {
            val content = html.substring(startIndex)
            val clean = content.replace(Regex("(?i)</(?:pre|code|body|html)>.*"), "").trim()
            if (clean.lines().count { it.startsWith("#EXTINF", ignoreCase = true) } > 0) {
                return clean
            }
        }
        return null
    }

    private fun resolveRelativeUrl(baseUrl: String, targetUrl: String): String {
        return try {
            if (targetUrl.contains("://")) targetUrl
            else URI(baseUrl).resolve(targetUrl).toString()
        } catch (e: Exception) {
            targetUrl
        }
    }

    fun isLocalSource(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("file://", ignoreCase = true) ||
                trimmed.startsWith("content://", ignoreCase = true) ||
                trimmed.startsWith("asset://", ignoreCase = true) ||
                trimmed.startsWith("/")
    }

    private fun loadFromLocal(url: String, targetFile: File) {
        val trimmed = url.trim()
        val inputStream = when {
            trimmed.startsWith("asset://", ignoreCase = true) -> {
                val assetPath = trimmed.substring(8).trimStart('/')
                context.assets.open(assetPath)
            }
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

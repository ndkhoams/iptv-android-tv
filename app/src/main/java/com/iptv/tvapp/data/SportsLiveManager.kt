package com.iptv.tvapp.data

import android.content.Context
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.parser.M3UParser
import com.iptv.tvapp.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class SportsLiveManager(private val context: Context) {

    private val prefs = PreferencesHelper(context)

    private val httpClient = NetworkUtils.configureTrustAllSsl(
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
    ).build()

    companion object {
        const val ACTION_REFRESH_SPORTS = "action_refresh_sports"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // Tự động cập nhật 30 phút 1 lần
        const val DEFAULT_SPORTS_URL = "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao.m3u"
        private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        @Volatile
        private var cachedCategories: List<Category>? = null
        @Volatile
        private var lastFetchTimeMs: Long = 0L

        fun clearCache() {
            cachedCategories = null
            lastFetchTimeMs = 0L
        }
    }

    fun clearCache() {
        cachedCategories = null
        lastFetchTimeMs = 0L
    }

    /**
     * Tải danh sách trực tiếp thể thao từ file thethao.m3u (được máy chủ Armbian tự động đẩy lên GitHub)
     * Thay vì cào link phức tạp trên từng TV, app tải file M3U duy nhất này, siêu nhanh (<0.5s) và tiết kiệm RAM.
     */
    suspend fun getSportsCategories(forceRefresh: Boolean = false): List<Category> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedCategories != null && (now - lastFetchTimeMs) < CACHE_TTL_MS) {
                return@withContext cachedCategories!!
            }

            val cacheFile = File(context.cacheDir, "thethao_sports_cache.m3u")
            val sportsUrl = prefs.sportsPlaylistUrl.ifBlank { DEFAULT_SPORTS_URL }

            try {
                // 1. Tải file thethao.m3u từ GitHub
                val request = Request.Builder()
                    .url(sportsUrl)
                    .header("User-Agent", CHROME_UA)
                    .header("Cache-Control", "no-cache")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyBytes = response.body?.bytes()
                    if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                        FileOutputStream(cacheFile).use { it.write(bodyBytes) }
                    }
                }
            } catch (e: Exception) {
                // Nếu tải mạng lỗi, sử dụng file cache đã lưu lần trước (nếu có)
            }

            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                return@withContext cachedCategories ?: emptyList()
            }

            try {
                // 2. Parse file thethao.m3u thành danh sách Channels
                val rawChannels = FileInputStream(cacheFile).use { input ->
                    M3UParser.parse(input, baseUrl = sportsUrl)
                }

                // Lọc bỏ các dòng refresh header giả lập
                val channels = rawChannels.filter {
                    !it.streamUrl.contains("/refresh") && it.streamUrl.isNotBlank()
                }

                if (channels.isEmpty()) {
                    return@withContext cachedCategories ?: emptyList()
                }

                // 3. Phân nhóm các category:
                // Nhóm 1: "⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)" - Nhóm tổng hợp toàn bộ trận đấu trong ngày
                val matchChannels = channels.filter {
                    !it.groupTitle.contains("QUỐC TẾ", ignoreCase = true) &&
                    !it.name.contains("[24/7]", ignoreCase = true)
                }

                val categories = mutableListOf<Category>()

                if (matchChannels.isNotEmpty()) {
                    val matchesWithRefresh = mutableListOf<Channel>()
                    val refreshItem = Channel(
                        id = ACTION_REFRESH_SPORTS,
                        name = "🔄 Cập nhật trận đấu (Bấm OK để tải lại)",
                        logoUrl = null,
                        streamUrl = "action://refresh_sports",
                        groupTitle = "⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)",
                        number = 0
                    )
                    matchesWithRefresh.add(refreshItem)
                    matchChannels.forEachIndexed { idx, ch ->
                        ch.number = idx + 1
                    }
                    matchesWithRefresh.addAll(matchChannels)

                    categories.add(Category("⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)", matchesWithRefresh))
                }

                // Nhóm 2: "🌐 THỂ THAO QUỐC TẾ (F1 / TENNIS)"
                val intlChannels = channels.filter {
                    it.groupTitle.contains("QUỐC TẾ", ignoreCase = true) ||
                    it.name.contains("[24/7]", ignoreCase = true)
                }
                if (intlChannels.isNotEmpty()) {
                    intlChannels.forEachIndexed { idx, ch -> ch.number = idx + 1 }
                    categories.add(Category("🌐 THỂ THAO QUỐC TẾ (F1 / TENNIS)", intlChannels.toMutableList()))
                }

                // Các nhóm theo nguồn: "🍌 Chuối Chiên TV", "🐔 Gà Vàng / Tiếu Lâm", "🌶️ Cà Khịa TV", ...
                val groupedBySource = channels.groupBy { it.groupTitle }
                for ((groupName, groupList) in groupedBySource) {
                    if (groupName.isNotBlank() &&
                        !groupName.contains("TRỰC TIẾP", ignoreCase = true) &&
                        !groupName.contains("QUỐC TẾ", ignoreCase = true)) {
                        groupList.forEachIndexed { idx, ch -> ch.number = idx + 1 }
                        categories.add(Category(groupName, groupList.toMutableList()))
                    }
                }

                cachedCategories = categories
                lastFetchTimeMs = System.currentTimeMillis()
                categories
            } catch (e: Exception) {
                cachedCategories ?: emptyList()
            }
        }

    /**
     * Giữ lại hàm giải mã để tương thích giao diện gọi phát kênh
     */
    suspend fun resolveLiveStreamUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        rawUrl
    }
}

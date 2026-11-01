package com.iptv.tvapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SportsStandingsManager(private val context: Context) {

    data class StandingEntry(
        val rank: Int,
        val teamName: String,
        val shortName: String,
        val logoUrl: String?,
        val played: Int,
        val gd: String,
        val points: Int
    )

    fun clearCache() {
        Companion.clearCache()
    }

    companion object {
        private const val CACHE_TTL_MS = 30 * 60 * 1000L
        private val cache = ConcurrentHashMap<String, List<StandingEntry>>()
        private val lastFetchTime = ConcurrentHashMap<String, Long>()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        fun clearCache() {
            cache.clear()
            lastFetchTime.clear()
        }

        fun updateFromDataJson(root: JSONObject) {
            val standingsObj = root.optJSONObject("standings") ?: return
            val keys = standingsObj.keys()
            val now = System.currentTimeMillis()
            while (keys.hasNext()) {
                val leagueKey = keys.next()
                val arr = standingsObj.optJSONArray(leagueKey) ?: continue
                val entries = mutableListOf<StandingEntry>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    entries.add(
                        StandingEntry(
                            rank = item.optInt("rank", i + 1),
                            teamName = item.optString("teamName", ""),
                            shortName = item.optString("shortName", ""),
                            logoUrl = item.optString("logoUrl").takeIf { !it.isNullOrBlank() },
                            played = item.optInt("played", 0),
                            gd = item.optString("gd", "0"),
                            points = item.optInt("points", 0)
                        )
                    )
                }
                if (entries.isNotEmpty()) {
                    cache[leagueKey] = entries
                    lastFetchTime[leagueKey] = now
                }
            }
        }
    }

    suspend fun getStandings(league: String, forceRefresh: Boolean = false): List<StandingEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = cache[league]
            val lastTime = lastFetchTime[league] ?: 0L
            if (cached != null && cached.isNotEmpty() && (now - lastTime) < CACHE_TTL_MS) {
                return@withContext cached
            }
        }

        // 1. Đọc từ file thethao_data_cache.json (đã được nạp từ Armbian)
        val dataCacheFile = File(context.cacheDir, "thethao_data_cache.json")
        if (dataCacheFile.exists() && dataCacheFile.length() > 0L) {
            try {
                val jsonStr = dataCacheFile.readText(Charsets.UTF_8)
                val root = JSONObject(jsonStr)
                updateFromDataJson(root)
                cache[league]?.let { if (it.isNotEmpty()) return@withContext it }
            } catch (e: Exception) {}
        }

        // 2. Nếu chưa có trên đĩa, tải thethao-data.json từ GitHub / CDN
        try {
            val dataUrl = "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao-data.json?_ts=$now"
            val cdnUrl = "https://cdn.jsdelivr.net/gh/ndkhoams/iptv@main/thethao-data.json?_ts=$now"
            for (u in listOf(dataUrl, cdnUrl)) {
                try {
                    val req = Request.Builder()
                        .url(u)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Cache-Control", "no-cache")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            dataCacheFile.writeText(body, Charsets.UTF_8)
                            val root = JSONObject(body)
                            updateFromDataJson(root)
                            cache[league]?.let { if (it.isNotEmpty()) return@withContext it }
                            break
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        // 3. Fallback danh sách cố định nếu hoàn toàn mất mạng
        if (league == "VLEAGUE") return@withContext getVLeagueStandingsFallback()
        if (league == "NBA") return@withContext getNbaStandingsFallback()

        cache[league] ?: emptyList()
    }

    private fun getVLeagueStandingsFallback(): List<StandingEntry> {
        val vleagueRaw = listOf(
            Triple("Thép Xanh Nam Định", "+9", 13),
            Triple("Sông Lam Nghệ An (SLNA)", "+6", 12),
            Triple("Công An Hà Nội (CAHN)", "+5", 11),
            Triple("Hồng Lĩnh Hà Tĩnh", "+2", 11),
            Triple("Ninh Bình Club", "+3", 10),
            Triple("Công An TP. Hồ Chí Minh", "+2", 10),
            Triple("Hải Phòng", "+2", 9),
            Triple("SHB Đà Nẵng", "-3", 9),
            Triple("Hà Nội FC", "-3", 9),
            Triple("Đông Á Thanh Hóa", "-1", 8),
            Triple("Bắc Ninh FC", "0", 8),
            Triple("Thể Công - Viettel", "-4", 4),
            Triple("Hoàng Anh Gia Lai (HAGL)", "-7", 3),
            Triple("Trường Tươi Đồng Nai", "-11", 1)
        )

        return vleagueRaw.mapIndexed { idx, item ->
            StandingEntry(
                rank = idx + 1,
                teamName = item.first,
                shortName = item.first,
                logoUrl = null,
                played = 6,
                gd = item.second,
                points = item.third
            )
        }
    }

    private fun getNbaStandingsFallback(): List<StandingEntry> = listOf(
        StandingEntry(1, "Boston Celtics", "Celtics", "https://a.espncdn.com/i/teamlogos/nba/500/bos.png", 6, "+9.2", 5),
        StandingEntry(2, "Cleveland Cavaliers", "Cavaliers", "https://a.espncdn.com/i/teamlogos/nba/500/cle.png", 6, "+5.0", 5),
        StandingEntry(3, "Oklahoma City Thunder", "Thunder", "https://a.espncdn.com/i/teamlogos/nba/500/okc.png", 6, "+7.5", 5),
        StandingEntry(4, "Golden State Warriors", "Warriors", "https://a.espncdn.com/i/teamlogos/nba/500/gsw.png", 6, "+6.0", 4),
        StandingEntry(5, "Los Angeles Lakers", "Lakers", "https://a.espncdn.com/i/teamlogos/nba/500/lal.png", 6, "+5.2", 4),
        StandingEntry(6, "Denver Nuggets", "Nuggets", "https://a.espncdn.com/i/teamlogos/nba/500/den.png", 5, "+3.0", 3),
        StandingEntry(7, "Dallas Mavericks", "Mavericks", "https://a.espncdn.com/i/teamlogos/nba/500/dal.png", 6, "+2.5", 4),
        StandingEntry(8, "Phoenix Suns", "Suns", "https://a.espncdn.com/i/teamlogos/nba/500/phx.png", 6, "+2.0", 4),
        StandingEntry(9, "New York Knicks", "Knicks", "https://a.espncdn.com/i/teamlogos/nba/500/nyk.png", 5, "+1.5", 3),
        StandingEntry(10, "Minnesota Timberwolves", "Timberwolves", "https://a.espncdn.com/i/teamlogos/nba/500/min.png", 6, "+2.0", 3),
        StandingEntry(11, "Milwaukee Bucks", "Bucks", "https://a.espncdn.com/i/teamlogos/nba/500/mil.png", 6, "-2.0", 2),
        StandingEntry(12, "Miami Heat", "Heat", "https://a.espncdn.com/i/teamlogos/nba/500/mia.png", 5, "-1.5", 2),
        StandingEntry(13, "LA Clippers", "Clippers", "https://a.espncdn.com/i/teamlogos/nba/500/lac.png", 5, "-3.0", 2),
        StandingEntry(14, "Philadelphia 76ers", "76ers", "https://a.espncdn.com/i/teamlogos/nba/500/phi.png", 5, "-4.0", 1),
        StandingEntry(15, "San Antonio Spurs", "Spurs", "https://a.espncdn.com/i/teamlogos/nba/500/sas.png", 5, "-5.0", 1)
    )
}

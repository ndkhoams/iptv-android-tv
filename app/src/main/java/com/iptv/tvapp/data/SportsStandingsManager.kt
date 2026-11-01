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

    companion object {
        private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L // Cache bảng xếp hạng 2 tiếng
        private val cache = ConcurrentHashMap<String, List<StandingEntry>>()
        private val lastFetchTime = ConcurrentHashMap<String, Long>()

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        private val LEAGUE_CODES = mapOf(
            "EPL" to "eng.1",
            "LALIGA" to "esp.1",
            "SERIE_A" to "ita.1",
            "BUNDESLIGA" to "ger.1",
            "LIGUE1" to "fra.1",
            "UCL" to "uefa.champions"
        )
    }

    suspend fun getStandings(league: String): List<StandingEntry> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cache[league]
        val lastTime = lastFetchTime[league] ?: 0L
        if (cached != null && (now - lastTime) < CACHE_TTL_MS) {
            return@withContext cached
        }

        // 1. Nếu là V-League 1: Lấy trực tiếp từ FotMob API (Mùa giải hiện tại)
        if (league == "VLEAGUE") {
            val liveVLeague = fetchFotMobVLeagueStandings()
            val vleague = if (liveVLeague.isNotEmpty()) liveVLeague else getVLeagueStandingsFallback()
            cache[league] = vleague
            lastFetchTime[league] = now
            return@withContext vleague
        }

        // 2. Các giải đấu châu Âu từ ESPN
        val espnCode = LEAGUE_CODES[league]
        if (espnCode != null) {
            val url = "https://site.api.espn.com/apis/v2/sports/soccer/$espnCode/standings"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val parsed = parseEspnStandings(body)
                        if (parsed.isNotEmpty()) {
                            cache[league] = parsed
                            lastFetchTime[league] = now
                            saveToDiskCache(league, body)
                            return@withContext parsed
                        }
                    }
                }
            } catch (e: Exception) {
                // Lỗi mạng: Thử nạp từ disk cache
            }
        }

        // 3. Fallback đọc từ disk cache nếu có
        val diskCached = loadFromDiskCache(league)
        if (diskCached.isNotEmpty()) {
            cache[league] = diskCached
            return@withContext diskCached
        }

        emptyList()
    }

    private fun parseEspnStandings(jsonStr: String): List<StandingEntry> {
        val list = mutableListOf<StandingEntry>()
        try {
            val root = JSONObject(jsonStr)
            val children = root.optJSONArray("children") ?: return emptyList()
            if (children.length() == 0) return emptyList()
            val firstChild = children.getJSONObject(0)
            val standingsObj = firstChild.optJSONObject("standings") ?: return emptyList()
            val entries = standingsObj.optJSONArray("entries") ?: return emptyList()

            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val team = entry.optJSONObject("team") ?: continue
                val teamName = team.optString("displayName", "")
                val shortName = team.optString("shortDisplayName", teamName)

                var logoUrl: String? = null
                val logos = team.optJSONArray("logos")
                if (logos != null && logos.length() > 0) {
                    logoUrl = logos.getJSONObject(0).optString("href")
                }

                var rank = i + 1
                var played = 0
                var gd = "0"
                var points = 0

                val stats = entry.optJSONArray("stats")
                if (stats != null) {
                    for (j in 0 until stats.length()) {
                        val s = stats.getJSONObject(j)
                        val name = s.optString("name")
                        val dVal = s.optString("displayValue", "0")
                        when (name) {
                            "rank" -> rank = dVal.toIntOrNull() ?: rank
                            "gamesPlayed" -> played = dVal.toIntOrNull() ?: 0
                            "pointDifferential" -> gd = dVal
                            "points" -> points = dVal.toIntOrNull() ?: 0
                        }
                    }
                }

                list.add(
                    StandingEntry(
                        rank = rank,
                        teamName = teamName,
                        shortName = shortName,
                        logoUrl = logoUrl,
                        played = played,
                        gd = gd,
                        points = points
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun fetchFotMobVLeagueStandings(): List<StandingEntry> {
        val url = "https://www.fotmob.com/leagues/9088/table/v-league"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                val regex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""")
                val match = regex.find(html)
                if (match != null) {
                    val jsonStr = match.groupValues[1]
                    val root = JSONObject(jsonStr)
                    val props = root.optJSONObject("props")?.optJSONObject("pageProps")
                    val tableArr = props?.optJSONArray("table")
                    if (tableArr != null && tableArr.length() > 0) {
                        val composite = tableArr.getJSONObject(0).optJSONObject("data")?.optJSONObject("table")?.optJSONArray("all")
                        if (composite != null && composite.length() > 0) {
                            val list = mutableListOf<StandingEntry>()
                            for (i in 0 until composite.length()) {
                                val r = composite.getJSONObject(i)
                                val teamId = r.optInt("id", 0)
                                val teamName = r.optString("name", "")
                                val shortName = r.optString("shortName", teamName)
                                val logoUrl = if (teamId > 0) "https://images.fotmob.com/image_resources/logo/teamlogo/$teamId.png" else null
                                val idx = r.optInt("idx", i + 1)
                                val played = r.optInt("played", 0)
                                val pts = r.optInt("pts", 0)
                                val gd = r.optString("goalConDiff", "0")
                                list.add(
                                    StandingEntry(
                                        rank = idx,
                                        teamName = teamName,
                                        shortName = shortName,
                                        logoUrl = logoUrl,
                                        played = played,
                                        gd = if (!gd.startsWith("-") && gd != "0") "+$gd" else gd,
                                        points = pts
                                    )
                                )
                            }
                            if (list.isNotEmpty()) {
                                saveToDiskCache("VLEAGUE", html)
                                return list
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    private fun getVLeagueStandingsFallback(): List<StandingEntry> {
        // Bảng xếp hạng mùa giải hiện tại 2025/2026 của LPBank V.League 1 từ FotMob
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

    private fun saveToDiskCache(league: String, data: String) {
        try {
            val f = File(context.cacheDir, "standings_$league.json")
            f.writeText(data, Charsets.UTF_8)
        } catch (e: Exception) {}
    }

    private fun loadFromDiskCache(league: String): List<StandingEntry> {
        try {
            val f = File(context.cacheDir, "standings_$league.json")
            if (f.exists()) {
                val data = f.readText(Charsets.UTF_8)
                return parseEspnStandings(data)
            }
        } catch (e: Exception) {}
        return emptyList()
    }
}


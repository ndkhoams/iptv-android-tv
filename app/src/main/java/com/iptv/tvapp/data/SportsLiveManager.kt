package com.iptv.tvapp.data

import android.content.Context
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SportsLiveManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val CACHE_TTL_MS = 3 * 60 * 1000L // Cache 3 phút

        // Chuối Chiên TV
        private const val CHUOICHIEN_API =
            "https://api-v2.chuoichientv.com/v2/matches?page=1&limit=100&sport=football&type=blv"
        private const val CHUOICHIEN_HOME = "https://chuoichientv.com/"
        private const val CHUOICHIEN_REFERER = "https://live.chuoichien.tv/"

        // Gà Vàng / Tiếu Lâm
        private const val GAVANG_API = "https://gavangtv-api.adviceme.io/api/v1/matches"
        private const val GAVANG_REFERER = "https://gavangtvttbdo.tv/"

        // Xôi Lạc TV
        private const val XOILAC_HOME = "https://xoilaczcb.tv/"
        private const val XOILAC_REFERER = "https://xlz.domainkqt.cc/"

        // Cà Khịa TV
        private const val CAKHIA_HOME = "https://cakhiazbb.tv/"
        private const val CAKHIA_REFERER = "https://ck.domainkqt.cc/"

        private const val IPHONE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

        private val REGEX_LIST_STREAM = Pattern.compile("var\\s+list_stream\\s*=\\s*(\\[.*?\\]);")
        private val REGEX_URL_STREAM = Pattern.compile("var\\s+urlStream\\s*=\\s*\"([^\"]+)\"")
        private val REGEX_MATCH_ITEM =
            Pattern.compile("<div[^>]*class=\"[^\"]*grid-matches__item[^\"]*\"[^>]*>(.*?)</div>\\s*</div>\\s*</div>", Pattern.DOTALL)
        private val REGEX_HREF = Pattern.compile("href=\"([^\"]+)\"")

        @Volatile
        private var cachedCategories: List<Category>? = null
        @Volatile
        private var lastFetchTimeMs: Long = 0L
    }

    /**
     * Tải danh sách trực tiếp bóng đá hôm nay từ Chuối Chiên, Gà Vàng / Tiếu Lâm, Xôi Lạc, Cà Khịa
     */
    suspend fun getSportsCategories(forceRefresh: Boolean = false): List<Category> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedCategories != null && (now - lastFetchTimeMs) < CACHE_TTL_MS) {
                return@withContext cachedCategories!!
            }

            try {
                coroutineScope {
                    val chuoichienDeferred = async { fetchChuoiChienMatches() }
                    val gavangDeferred = async { fetchGaVangMatches() }
                    val xoilacDeferred = async { fetchXoiLacMatches() }
                    val cakhiaDeferred = async { fetchCaKhiaMatches() }

                    val chuoichienList = try { chuoichienDeferred.await() } catch (e: Exception) { emptyList() }
                    val gavangList = try { gavangDeferred.await() } catch (e: Exception) { emptyList() }
                    val xoilacList = try { xoilacDeferred.await() } catch (e: Exception) { emptyList() }
                    val cakhiaList = try { cakhiaDeferred.await() } catch (e: Exception) { emptyList() }

                    val allMatches = mutableListOf<Channel>()
                    allMatches.addAll(chuoichienList)
                    allMatches.addAll(gavangList)
                    allMatches.addAll(xoilacList)
                    allMatches.addAll(cakhiaList)

                    val categories = mutableListOf<Category>()

                    if (allMatches.isNotEmpty()) {
                        // Nhóm tổng hợp: Toàn bộ trận đấu trực tiếp hôm nay
                        categories.add(Category("⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)", allMatches.toMutableList()))
                    }

                    if (chuoichienList.isNotEmpty()) {
                        categories.add(Category("🍌 Chuối Chiên TV", chuoichienList.toMutableList()))
                    }

                    if (gavangList.isNotEmpty()) {
                        categories.add(Category("🐔 Gà Vàng / Tiếu Lâm", gavangList.toMutableList()))
                    }

                    if (xoilacList.isNotEmpty()) {
                        categories.add(Category("🍚 Xôi Lạc TV", xoilacList.toMutableList()))
                    }

                    if (cakhiaList.isNotEmpty()) {
                        categories.add(Category("🌶️ Cà Khịa TV", cakhiaList.toMutableList()))
                    }

                    if (categories.isNotEmpty()) {
                        cachedCategories = categories
                        lastFetchTimeMs = now
                    }

                    categories
                }
            } catch (e: Exception) {
                e.printStackTrace()
                cachedCategories ?: emptyList()
            }
        }

    /**
     * Chuối Chiên API Scraper (kèm danh sách luồng trực tiếp FHD / HD & BLV)
     */
    private fun fetchChuoiChienMatches(): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(CHUOICHIEN_API)
                .header("User-Agent", PreferencesHelper.DEFAULT_USER_AGENT)
                .header("Referer", CHUOICHIEN_HOME)
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val matches = json.optJSONArray("matches") ?: return emptyList()

                var idx = 1
                for (i in 0 until matches.length()) {
                    val m = matches.optJSONObject(i) ?: continue
                    val teamsObj = m.optJSONObject("teams")
                    val home = teamsObj?.optJSONObject("home")?.optString("name") ?: ""
                    val away = teamsObj?.optJSONObject("away")?.optString("name") ?: ""
                    if (home.isBlank() && away.isBlank()) continue

                    val matchTime = m.optString("matchTime")
                    val timeStr = if (matchTime.length >= 16) {
                        try {
                            val hourMinute = matchTime.substring(11, 16)
                            val parts = hourMinute.split(":")
                            val utcHour = parts[0].toInt()
                            val vnHour = (utcHour + 7) % 24
                            String.format("%02d:%s", vnHour, parts[1])
                        } catch (e: Exception) {
                            ""
                        }
                    } else ""

                    val blvs = m.optJSONArray("blvs")
                    if (blvs != null && blvs.length() > 0) {
                        for (bIdx in 0 until blvs.length()) {
                            val blv = blvs.optJSONObject(bIdx) ?: continue
                            val blvName = blv.optString("name").trim()
                            val streams = blv.optJSONArray("streams")
                            var bestStreamUrl = ""
                            if (streams != null && streams.length() > 0) {
                                for (sIdx in 0 until streams.length()) {
                                    val stObj = streams.optJSONObject(sIdx) ?: continue
                                    val u = stObj.optString("url").trim()
                                    if (u.isNotBlank()) {
                                        bestStreamUrl = u
                                        if (stObj.optString("label").contains("FHD", ignoreCase = true)) {
                                            break
                                        }
                                    }
                                }
                            }

                            if (bestStreamUrl.isNotBlank()) {
                                val blvSuffix = if (blvName.isNotBlank()) " (BLV $blvName)" else ""
                                val channelName = "⚽ [${if (timeStr.isNotBlank()) timeStr else "LIVE"}] $home vs $away$blvSuffix - Chuối Chiên"
                                val fullUrl = "$bestStreamUrl|Referer=$CHUOICHIEN_REFERER"

                                list.add(
                                    Channel(
                                        id = "chuoichien_${idx}_$bIdx",
                                        name = channelName,
                                        logoUrl = blv.optString("avatar").takeIf { it.isNotBlank() }
                                            ?: "https://media.chuoichientv.net/media/20260611_225909_8715fbcf.jpg",
                                        streamUrl = fullUrl,
                                        groupTitle = "🍌 Chuối Chiên TV",
                                        number = idx
                                    )
                                )
                            }
                        }
                    }
                    idx++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Gà Vàng & Tiếu Lâm API Scraper
     */
    private fun fetchGaVangMatches(): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(GAVANG_API)
                .header("User-Agent", PreferencesHelper.DEFAULT_USER_AGENT)
                .header("Referer", GAVANG_REFERER)
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: return emptyList()
                val json = JSONObject(bodyStr)
                val dataObj = json.optJSONObject("data") ?: return emptyList()

                var idx = 1
                val keys = dataObj.keys()
                while (keys.hasNext()) {
                    val slug = keys.next()
                    val matchObj = dataObj.optJSONObject(slug) ?: continue
                    val anchors = matchObj.optJSONArray("anchorAppointmentVoList")

                    var blvName = ""
                    var streamUrl = ""
                    if (anchors != null && anchors.length() > 0) {
                        val anchor0 = anchors.optJSONObject(0)
                        blvName = anchor0?.optString("nickName")?.trim().orEmpty()
                        val streams = anchor0?.optJSONArray("streamUrls")
                        if (streams != null && streams.length() > 0) {
                            streamUrl = streams.optString(0).trim()
                        }
                    }

                    if (streamUrl.isBlank()) continue

                    // Trích xuất tên hai đội và giờ đá từ slug
                    val parts = slug.split("-luc-")
                    val rawTeams = if (parts.isNotEmpty()) parts[0].replace("-", " ") else slug
                    val teams = formatTitle(rawTeams)

                    val timePart = if (parts.size > 1) parts[1].substringBefore("-ngay-") else ""
                    val timeStr = if (timePart.length == 4 && timePart.all { it.isDigit() }) {
                        "${timePart.substring(0, 2)}:${timePart.substring(2)}"
                    } else timePart

                    val blvSuffix = if (blvName.isNotBlank()) " (BLV $blvName)" else ""
                    val channelName = "⚽ [${if (timeStr.isNotBlank()) timeStr else "LIVE"}] $teams$blvSuffix - Gà Vàng"

                    // Gắn header Referer chuẩn để ExoPlayer / VLC truyền thẳng khi phát
                    val fullStreamUrl = "$streamUrl|Referer=$GAVANG_REFERER"

                    list.add(
                        Channel(
                            id = "gavang_$idx",
                            name = channelName,
                            logoUrl = "https://cdn.imgts.com/uploads/images/1784649818862-gr6fqgbhsx8.png",
                            streamUrl = fullStreamUrl,
                            groupTitle = "🐔 Gà Vàng / Tiếu Lâm",
                            number = idx
                        )
                    )
                    idx++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Xôi Lạc TV Scraper
     */
    private fun fetchXoiLacMatches(): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(XOILAC_HOME)
                .header("User-Agent", IPHONE_UA)
                .header("Referer", XOILAC_HOME)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: return emptyList()
                val matcher = REGEX_MATCH_ITEM.matcher(html)

                var idx = 1
                while (matcher.find() && idx <= 25) {
                    val block = matcher.group(1) ?: continue
                    val hrefMatcher = REGEX_HREF.matcher(block)
                    if (hrefMatcher.find()) {
                        val path = hrefMatcher.group(1)?.trim().orEmpty()
                        if (path.contains("/truc-tiep/")) {
                            val slug = path.trim('/').substringAfter("truc-tiep/").substringBefore('/')
                            val parts = slug.split("-luc-")
                            val rawTeams = if (parts.isNotEmpty()) parts[0].replace("-", " ") else slug
                            val teams = formatTitle(rawTeams)

                            val timePart = if (parts.size > 1) parts[1].substringBefore("-ngay-") else ""
                            val timeStr = if (timePart.length == 4 && timePart.all { it.isDigit() }) {
                                "${timePart.substring(0, 2)}:${timePart.substring(2)}"
                            } else timePart

                            val channelName = "⚽ [${if (timeStr.isNotBlank()) timeStr else "LIVE"}] $teams - Xôi Lạc"
                            val fullMatchUrl = if (path.startsWith("http")) path else "$XOILAC_HOME${path.trimStart('/')}"

                            list.add(
                                Channel(
                                    id = "xoilac_$idx",
                                    name = channelName,
                                    logoUrl = "https://xoilaczcb.tv/favicon.ico",
                                    streamUrl = fullMatchUrl,
                                    groupTitle = "🍚 Xôi Lạc TV",
                                    number = idx
                                )
                            )
                            idx++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Cà Khịa TV Scraper
     */
    private fun fetchCaKhiaMatches(): List<Channel> {
        val list = mutableListOf<Channel>()
        try {
            val request = Request.Builder()
                .url(CAKHIA_HOME)
                .header("User-Agent", IPHONE_UA)
                .header("Referer", CAKHIA_HOME)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: return emptyList()
                val matcher = REGEX_MATCH_ITEM.matcher(html)

                var idx = 1
                while (matcher.find() && idx <= 20) {
                    val block = matcher.group(1) ?: continue
                    val hrefMatcher = REGEX_HREF.matcher(block)
                    if (hrefMatcher.find()) {
                        val path = hrefMatcher.group(1)?.trim().orEmpty()
                        if (path.contains("/truc-tiep/")) {
                            val slug = path.trim('/').substringAfter("truc-tiep/").substringBefore('/')
                            val parts = slug.split("-luc-")
                            val rawTeams = if (parts.isNotEmpty()) parts[0].replace("-", " ") else slug
                            val teams = formatTitle(rawTeams)

                            val timePart = if (parts.size > 1) parts[1].substringBefore("-ngay-") else ""
                            val timeStr = if (timePart.length == 4 && timePart.all { it.isDigit() }) {
                                "${timePart.substring(0, 2)}:${timePart.substring(2)}"
                            } else timePart

                            val channelName = "⚽ [${if (timeStr.isNotBlank()) timeStr else "LIVE"}] $teams - Cà Khịa"
                            val fullMatchUrl = if (path.startsWith("http")) path else "$CAKHIA_HOME${path.trimStart('/')}"

                            list.add(
                                Channel(
                                    id = "cakhia_$idx",
                                    name = channelName,
                                    logoUrl = "https://cakhiazbb.tv/favicon.ico",
                                    streamUrl = fullMatchUrl,
                                    groupTitle = "🌶️ Cà Khịa TV",
                                    number = idx
                                )
                            )
                            idx++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Tự động giải mã link m3u8 động cho Xôi Lạc và Cà Khịa khi người dùng bấm vào xem
     */
    suspend fun resolveLiveStreamUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        if (!rawUrl.contains("/truc-tiep/")) {
            return@withContext rawUrl
        }

        val isCakhia = rawUrl.contains("cakhia")
        val baseReferer = if (isCakhia) CAKHIA_REFERER else XOILAC_REFERER

        try {
            val req1 = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", IPHONE_UA)
                .header("Referer", if (isCakhia) CAKHIA_HOME else XOILAC_HOME)
                .build()

            val resp1 = httpClient.newCall(req1).execute()
            if (resp1.isSuccessful) {
                val html = resp1.body?.string().orEmpty()
                val m1 = REGEX_LIST_STREAM.matcher(html)
                if (m1.find()) {
                    val jsonArrayStr = m1.group(1) ?: return@withContext rawUrl
                    val jsonArr = org.json.JSONArray(jsonArrayStr)
                    if (jsonArr.length() > 0) {
                        val firstGroup = jsonArr.optJSONArray(0)
                        if (firstGroup != null && firstGroup.length() > 0) {
                            val playerAjaxUrl = firstGroup.optString(0)

                            val req2 = Request.Builder()
                                .url(playerAjaxUrl)
                                .header("User-Agent", IPHONE_UA)
                                .header("Referer", if (isCakhia) CAKHIA_HOME else XOILAC_HOME)
                                .build()

                            val resp2 = httpClient.newCall(req2).execute()
                            if (resp2.isSuccessful) {
                                val playerBody = resp2.body?.string().orEmpty()
                                val m2 = REGEX_URL_STREAM.matcher(playerBody)
                                if (m2.find()) {
                                    val streamUrl = m2.group(1)?.trim().orEmpty()
                                    if (streamUrl.isNotBlank()) {
                                        return@withContext "$streamUrl|Referer=$baseReferer"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext rawUrl
    }

    private fun formatTitle(str: String): String {
        return str.split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .replace(" Vs ", " vs ")
    }
}

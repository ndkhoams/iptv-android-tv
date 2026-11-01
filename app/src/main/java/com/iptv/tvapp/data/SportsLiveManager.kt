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
        const val ACTION_REFRESH_SPORTS = "action_refresh_sports"
        private const val CACHE_TTL_MS = 15 * 60 * 1000L // Tự động cập nhật 15 phút 1 lần

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

        // StreamFree API
        private const val STREAMFREE_API = "https://streamfree.top/api/v1/streams"
        private const val STREAMFREE_HOME = "https://streamfree.top/"
        private const val STREAMFREE_REFERER = "https://streamfree.top/"

        // User-Agent Chrome Desktop chuẩn để vượt qua Cloudflare WAF của Chuối Chiên, Cà Khịa, Xôi Lạc, Gà Vàng, StreamFree
        private const val CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val REGEX_LIST_STREAM = Pattern.compile("var\\s+list_stream\\s*=\\s*(\\[.*?\\]);")
        private val REGEX_URL_STREAM = Pattern.compile("var\\s+urlStream\\s*=\\s*\"([^\"]+)\"")
        private val REGEX_MATCH_ITEM =
            Pattern.compile("<div[^>]*class=\"[^\"]*grid-matches__item[^\"]*\"[^>]*>(.*?)</div>\\s*</div>\\s*</div>", Pattern.DOTALL)
        private val REGEX_HREF = Pattern.compile("href=\"([^\"]+)\"")
        private val REGEX_0X = Pattern.compile("const\\s+_0x\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL)

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
                    val intlList = getInternationalChannels()

                    val allMatches = mutableListOf<Channel>()
                    allMatches.addAll(chuoichienList)
                    allMatches.addAll(gavangList)
                    allMatches.addAll(xoilacList)
                    allMatches.addAll(cakhiaList)

                    val categories = mutableListOf<Category>()

                    if (allMatches.isNotEmpty()) {
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
                        allMatches.forEachIndexed { idx, ch ->
                            ch.number = idx + 1
                        }
                        matchesWithRefresh.addAll(allMatches)

                        // Nhóm tổng hợp: Toàn bộ trận đấu trực tiếp hôm nay (dòng đầu tiên là nút Cập nhật)
                        categories.add(Category("⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)", matchesWithRefresh))
                    }

                    if (intlList.isNotEmpty()) {
                        categories.add(Category("🌐 THỂ THAO QUỐC TẾ (F1 / TENNIS)", intlList.toMutableList()))
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
                .header("User-Agent", CHROME_UA)
                .header("Referer", CHUOICHIEN_HOME)
                .header("Origin", "https://chuoichientv.com")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
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
                                val fullUrl = "$bestStreamUrl|Referer=$CHUOICHIEN_REFERER&User-Agent=$CHROME_UA"

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
                .header("User-Agent", CHROME_UA)
                .header("Referer", GAVANG_REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
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
                    val fullStreamUrl = "$streamUrl|Referer=$GAVANG_REFERER&User-Agent=$CHROME_UA"

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
                .header("User-Agent", CHROME_UA)
                .header("Referer", XOILAC_HOME)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
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
                .header("User-Agent", CHROME_UA)
                .header("Referer", CAKHIA_HOME)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
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
     * Kênh thể thao quốc tế 24/7 (Sky Sports F1, Sky Sports Tennis, TNT Sports 1-4, Eurosport 1-2, Canal+)
     */
    private fun getInternationalChannels(): List<Channel> {
        val list = mutableListOf<Channel>()
        val intl = listOf(
            Triple("🏎️ [24/7] Sky Sport F1 HD - Đua xe Công thức 1", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=1149423&extension=ts&play_token=1jkVQWGCqc", "https://r2.thesportsdb.com/images/media/channel/logo/p5csyn1620551587.png"),
            Triple("🎾 [24/7] Sky Sport Tennis HD - Quần vợt ATP / WTA", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=1149433&extension=ts&play_token=Y1kv3yxPS8", "https://e0.365dm.com/24/01/2048x1152/skysports-sky-sports-tennis_6437040.jpg"),
            Triple("🏆 [24/7] TNT Sports 1 HD - Cúp C1 / Ngoại Hạng Anh", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=894977&extension=ts&play_token=W2HvDO5iES", "https://media.info/l/o/1/1539.1690027797.png"),
            Triple("🏆 [24/7] TNT Sports 2 HD - Đua xe MotoGP / UFC", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=1091517&extension=ts&play_token=zKzxFKTNmb", "https://media.info/l/o/1/1540.1690027877.png"),
            Triple("🏆 [24/7] TNT Sports 3 HD - Thể thao trực tiếp", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=894979&extension=ts&play_token=RLLwKaJKqY", "https://media.info/l/o/5/5712.1690027954.png"),
            Triple("🏆 [24/7] TNT Sports 4 HD - Thể thao trực tiếp", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=894980&extension=ts&play_token=4ofwEIdTU4", "https://media.info/l/o/1/1491.1690027975.png"),
            Triple("🚴 [24/7] Eurosport 1 HD - Thể thao & Tennis Grand Slam", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=933004&extension=ts&play_token=jtuDUMdLno", "https://upload.wikimedia.org/wikipedia/commons/3/37/Eurosport1hd.png"),
            Triple("🚴 [24/7] Eurosport 2 HD - Thể thao & Đua xe", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=933003&extension=ts&play_token=OIqiUqIbWW", "https://upload.wikimedia.org/wikipedia/commons/d/d5/Eurosport2hd.png"),
            Triple("⚽ [24/7] Canal+ Sport HD - Thể thao Châu Âu", "http://line.watchtivo-8k.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=958758&extension=ts&play_token=MskDtCSrpn", "https://upload.wikimedia.org/wikipedia/commons/1/1a/Canal%2B_Sport_2015.png")
        )
        intl.forEachIndexed { index, (name, url, logo) ->
            list.add(
                Channel(
                    id = "intl_sports_$index",
                    name = name,
                    logoUrl = logo,
                    streamUrl = url,
                    groupTitle = "🌐 KÊNH THỂ THAO QUỐC TẾ (F1 / TENNIS)",
                    number = index + 1
                )
            )
        }
        return list
    }

    /**
     * Tự động giải mã link m3u8 động cho StreamFree, Xôi Lạc và Cà Khịa khi người dùng bấm vào xem
     */
    suspend fun resolveLiveStreamUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        if (rawUrl.startsWith("streamfree://")) {
            return@withContext resolveStreamFreeUrl(rawUrl)
        }

        if (!rawUrl.contains("/truc-tiep/")) {
            return@withContext rawUrl
        }

        val isCakhia = rawUrl.contains("cakhia")
        val baseReferer = if (isCakhia) CAKHIA_REFERER else XOILAC_REFERER

        try {
            val req1 = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", CHROME_UA)
                .header("Referer", if (isCakhia) CAKHIA_HOME else XOILAC_HOME)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
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
                                .header("User-Agent", CHROME_UA)
                                .header("Referer", if (isCakhia) CAKHIA_HOME else XOILAC_HOME)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                                .build()

                            val resp2 = httpClient.newCall(req2).execute()
                            if (resp2.isSuccessful) {
                                val playerBody = resp2.body?.string().orEmpty()
                                val m2 = REGEX_URL_STREAM.matcher(playerBody)
                                if (m2.find()) {
                                    val streamUrl = m2.group(1)?.trim().orEmpty()
                                    if (streamUrl.isNotBlank()) {
                                        return@withContext "$streamUrl|Referer=$baseReferer&User-Agent=$CHROME_UA"
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

    /**
     * Giải mã luồng m3u8 StreamFree trực tiếp từ Embed player và API token
     */
    private fun resolveStreamFreeUrl(rawUrl: String): String {
        try {
            val uri = android.net.Uri.parse(rawUrl)
            val category = uri.host ?: "soccer"
            val streamKey = uri.path?.trim('/') ?: ""
            val quality = uri.getQueryParameter("quality") ?: "1080p"
            if (streamKey.isBlank()) return rawUrl

            val encodedKey = try {
                java.net.URLEncoder.encode(streamKey, "UTF-8").replace("+", "%20")
            } catch (e: Exception) { streamKey }

            val embedUrl = "https://streamfree.top/embed/$category/$encodedKey$quality"
            val playerReferer = "https://streamfree.top/player/$category/$encodedKey"

            // 1. Lấy mã nhúng HTML để trích xuất token chữ ký bảo mật _0x
            val embedReq = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", CHROME_UA)
                .header("Referer", playerReferer)
                .header("Sec-Fetch-Dest", "iframe")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            val embedResp = httpClient.newCall(embedReq).execute()
            if (!embedResp.isSuccessful) return rawUrl
            val html = embedResp.body?.string().orEmpty()

            val m0x = REGEX_0X.matcher(html)
            if (!m0x.find()) return rawUrl
            val tokensJson = JSONObject(m0x.group(1) ?: "{}")

            // 2. Lấy thông tin CDN server phân phối luồng
            val keyInfoReq = Request.Builder()
                .url("https://streamfree.top/get-stream-key/$encodedKey")
                .header("User-Agent", CHROME_UA)
                .header("Referer", STREAMFREE_HOME)
                .build()

            val keyResp = httpClient.newCall(keyInfoReq).execute()
            if (!keyResp.isSuccessful) return rawUrl
            val keyData = JSONObject(keyResp.body?.string().orEmpty())

            if (keyData.optBoolean("is_external") && keyData.optString("external_url").isNotBlank()) {
                val extUrl = keyData.optString("external_url")
                return "$extUrl|Referer=$STREAMFREE_REFERER&User-Agent=$CHROME_UA"
            }

            val serverName = keyData.optString("server_name", "origin")
            val actualQuality = when {
                tokensJson.has(quality) -> quality
                tokensJson.has("1080p") -> "1080p"
                tokensJson.has("720p") -> "720p"
                tokensJson.has("540p") -> "540p"
                tokensJson.has("2160p") -> "2160p"
                else -> quality
            }
            val variant = "$encodedKey$actualQuality"
            val (domain, path) = when {
                serverName == "externalcdn" -> Pair("https://streamfree.top", "/live-externalcdn/$variant/index.m3u8")
                else -> Pair("https://strmfree.st", "/live-cdn/$variant/index.m3u8")
            }

            val tokenObj = tokensJson.optJSONObject(actualQuality)
                ?: tokensJson.optJSONObject("1080p")
                ?: tokensJson.optJSONObject("720p")
                ?: tokensJson.optJSONObject("540p")
                ?: tokensJson.optJSONObject("2160p")

            val m3u8Url = if (tokenObj != null) {
                val t = tokenObj.optString("_t")
                val e = tokenObj.optString("_e")
                val n = tokenObj.optString("_n")
                "$domain$path?_t=$t&_e=$e&_n=$n"
            } else {
                "$domain$path"
            }

            val streamReferer = if (serverName == "externalcdn") STREAMFREE_HOME else "https://strmfree.st/"
            return "$m3u8Url|Referer=$streamReferer&User-Agent=$CHROME_UA"
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rawUrl
    }

    private fun formatTitle(str: String): String {
        return str.split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .replace(" Vs ", " vs ")
    }
}

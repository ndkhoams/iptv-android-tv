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
import java.util.concurrent.ConcurrentHashMap
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

    data class MatchGroup(
        val key: String,
        val homeTeam: String,
        val awayTeam: String,
        val time: String,
        val league: String,
        val leagueName: String,
        val sources: MutableList<Channel>
    )

    private val matchGroupsMap = ConcurrentHashMap<String, MatchGroup>()

    fun getMatch(key: String): MatchGroup? = matchGroupsMap[key]

    companion object {
        const val ACTION_REFRESH_SPORTS = "action_refresh_sports"
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // Tự động cập nhật 30 phút 1 lần
        const val DEFAULT_SPORTS_URL = "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao.m3u"
        private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val PRIORITY_LEAGUES = listOf("UCL", "EPL", "VLEAGUE", "LALIGA", "BUNDESLIGA", "SERIE_A", "LIGUE1")

        val LEAGUE_LABELS = mapOf(
            "UCL" to "🏆 UEFA Champions League",
            "EPL" to "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Ngoại Hạng Anh",
            "VLEAGUE" to "🇻🇳 V-League 1",
            "LALIGA" to "🇪🇸 La Liga",
            "BUNDESLIGA" to "🇩🇪 Bundesliga",
            "SERIE_A" to "🇮🇹 Serie A",
            "LIGUE1" to "🇫🇷 Ligue 1",
            "OTHER" to "🌐 Trực tiếp"
        )

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
        matchGroupsMap.clear()
    }

    private fun detectLeague(text: String): String {
        val t = text.lowercase()
        // 1. UEFA Champions League (Cúp C1)
        if ((t.contains("uefa champions") || t.contains("cúp c1") || t.contains("cup c1") || t.contains("champions league")) &&
            !t.contains("afc") && !t.contains("caf") && !t.contains("concacaf") && !t.contains("women")) {
            return "UCL"
        }
        // 2. English Premier League (EPL / Ngoại Hạng Anh)
        val eplKeywords = listOf(
            "premier league", "ngoại hạng anh", "epl", "arsenal", "aston villa", "bournemouth", "brentford",
            "brighton", "chelsea", "crystal palace", "everton", "fulham", "ipswich", "leicester", "liverpool",
            "manchester city", "man city", "manchester united", "man utd", "man united", "newcastle",
            "nottingham", "southampton", "tottenham", "spurs", "west ham", "wolves", "wolverhampton"
        )
        if (eplKeywords.any { t.contains(it) }) return "EPL"

        // 3. Vietnam V-League 1
        val vleagueKeywords = listOf(
            "v.league 1", "v-league 1", "vleague 1", "v league 1", "v.league", "vleague", "v-league",
            "hà nội fc", "clb hà nội", "hoàng anh gia lai", "hagl", "thể công", "viettel", "nam định",
            "hải phòng", "công an hà nội", "cahn", "sông lam nghệ an", "slna", "bình dương", "thanh hóa",
            "shb đà nẵng", "đà nẵng", "quy nhơn bình định", "bình định", "quảng nam", "hồng lĩnh hà tĩnh",
            "hà tĩnh", "tp.hồ chí minh", "tp hồ chí minh", "clb tp.hcm", "pvf-cand", "khánh hòa", "long an"
        )
        if (vleagueKeywords.any { t.contains(it) }) return "VLEAGUE"

        // 4. Spanish La Liga
        val laligaKeywords = listOf(
            "la liga", "laliga", "primera division", "real madrid", "barcelona", "barca", "atletico madrid",
            "atletico", "athletic bilbao", "athletic club", "real sociedad", "villarreal", "real betis",
            "sevilla", "celta vigo", "valencia", "osasuna", "getafe", "girona", "mallorca", "las palmas",
            "rayo vallecano", "espanyol", "alaves", "leganes", "valladolid", "santander"
        )
        if (laligaKeywords.any { t.contains(it) }) return "LALIGA"

        // 5. German Bundesliga
        val bundesligaKeywords = listOf(
            "bundesliga", "bayern munich", "bayern", "borussia dortmund", "dortmund", "bayer leverkusen",
            "leverkusen", "rb leipzig", "leipzig", "eintracht frankfurt", "frankfurt", "vfb stuttgart",
            "stuttgart", "wolfsburg", "freiburg", "borussia monchengladbach", "monchengladbach",
            "union berlin", "werder bremen", "bremen", "augsburg", "heidenheim", "mainz", "hoffenheim",
            "st. pauli", "st pauli", "holstein kiel", "bochum", "fc koln", "fc köln", "köln"
        )
        if (bundesligaKeywords.any { t.contains(it) } && !t.contains("bundesliga 2")) return "BUNDESLIGA"

        // 6. Italian Serie A
        val serieAKeywords = listOf(
            "serie a", "juventus", "inter milan", "ac milan", "napoli", "as roma", "lazio", "atalanta",
            "fiorentina", "bologna", "torino", "como", "venezia", "genoa", "cagliari", "lecce", "monza",
            "parma", "udinese", "empoli", "verona", "frosinone"
        )
        if (serieAKeywords.any { t.contains(it) } && !t.contains("serie b") && !t.contains("inter miami") && !t.contains("inter turku")) return "SERIE_A"

        // 7. French Ligue 1
        val ligue1Keywords = listOf(
            "ligue 1", "ligue1", "psg", "paris saint-germain", "paris sg", "marseille", "olympique marseille",
            "as monaco", "monaco", "olympique lyon", "lyon", "lille", "lens", "rennes", "nice", "reims",
            "strasbourg", "brest", "toulouse", "montpellier", "nantes", "auxerre", "le havre", "angers",
            "saint-etienne", "paris fc"
        )
        if (ligue1Keywords.any { t.contains(it) } && !t.contains("ligue 2")) return "LIGUE1"

        return "OTHER"
    }

    private fun cleanTeamName(name: String): String {
        var s = name
        // Remove geo, hls, fhd, hd tags
        s = s.replace(Regex("""(?i)\[(?:geo|hls\s*\d*|fhd|hd|sd)\]"""), "")
        // Remove emojis
        s = s.replace(Regex("""[\u2600-\u26ff\u2700-\u27bf\uD83C-\uDBFF\uDC00-\uDFFF]"""), "")
        // Remove times like [18:30] or 18:30 01/11
        s = s.replace(Regex("""\[\d{1,2}:\d{2}\]"""), "")
        s = s.replace(Regex("""\d{1,2}:\d{2}\s*\d{1,2}/\d{1,2}"""), "")
        s = s.replace(Regex("""\d{1,2}:\d{2}"""), "")
        // Remove (BLV ...)
        s = s.replace(Regex("""(?i)\(BLV[^\)]*\)"""), "")
        s = s.replace(Regex("""\([^\)]*\)"""), "")
        // Remove source suffixes
        s = s.replace(Regex("""(?i)\s*-\s*(?:Chuối Chiên|Gà Vàng|Xôi Lạc|Cà Khịa|TQM|Cola|Bia Ôm|Phá Làng|Vua Sân Cỏ|S8|Khô Gà|Giờ Vàng|Sao Kê|Xôi Gấc).*"""), "")
        // Remove noise words
        s = s.replace(Regex("""(?i)\b(CLB|FC|CF|S\.S\.|AC|AS|FK|RC|Olympique|Calcio)\b\.?"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun normalizeKey(str: String): String {
        val s = str.lowercase().replace(Regex("""[^a-z0-9\s]"""), "")
        val words = s.split(Regex("""\s+""")).filter { it.length > 1 && it != "vs" }.sorted()
        return words.joinToString(" ")
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
                // Nhóm 1: "⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)" - Nhóm gom các luồng cùng 1 trận đấu thành 1 title duy nhất
                val matchChannels = channels.filter {
                    !it.groupTitle.contains("QUỐC TẾ", ignoreCase = true) &&
                    !it.name.contains("[24/7]", ignoreCase = true)
                }

                val categories = mutableListOf<Category>()

                if (matchChannels.isNotEmpty()) {
                    matchGroupsMap.clear()
                    val grouped = LinkedHashMap<String, MatchGroup>()

                    for (ch in matchChannels) {
                        val rawName = ch.name
                        val cleaned = cleanTeamName(rawName)
                        val timeMatch = Regex("""(\d{1,2}:\d{2})""").find(rawName)
                        val timeStr = timeMatch?.groupValues?.get(1) ?: "LIVE"

                        val parts = cleaned.split(Regex("""\s+(?:vs|Vs|VS|-)\s+"""))
                        val (homeTeam, awayTeam) = if (parts.size >= 2) {
                            parts[0].trim() to parts[1].trim()
                        } else {
                            cleaned.trim() to ""
                        }

                        val normKey = if (awayTeam.isNotBlank()) {
                            "${timeStr}_${normalizeKey("$homeTeam $awayTeam")}"
                        } else {
                            "${timeStr}_${normalizeKey(cleaned)}"
                        }

                        val existing = grouped[normKey]
                        if (existing != null) {
                            existing.sources.add(ch)
                            if (existing.league == "OTHER") {
                                val lg = detectLeague(rawName)
                                if (lg != "OTHER") {
                                    val updated = existing.copy(
                                        league = lg,
                                        leagueName = LEAGUE_LABELS[lg] ?: "🌐 Trực tiếp"
                                    )
                                    grouped[normKey] = updated
                                }
                            }
                        } else {
                            val lg = detectLeague(rawName)
                            val lgName = LEAGUE_LABELS[lg] ?: "🌐 Trực tiếp"
                            val group = MatchGroup(
                                key = normKey,
                                homeTeam = homeTeam.ifBlank { "Đội nhà" },
                                awayTeam = awayTeam.ifBlank { "Đội khách" },
                                time = timeStr,
                                league = lg,
                                leagueName = lgName,
                                sources = mutableListOf(ch)
                            )
                            grouped[normKey] = group
                        }
                    }

                    // Tách thành giải ưu tiên và giải khác
                    val priorityMatches = grouped.values.filter { it.league in PRIORITY_LEAGUES }
                    val otherMatches = grouped.values.filter { it.league !in PRIORITY_LEAGUES }

                    // Sắp xếp: Ưu tiên UCL -> EPL -> VLEAGUE -> LALIGA -> BUNDESLIGA -> SERIE_A -> LIGUE1, sau đó theo giờ
                    val sortedPriority = priorityMatches.sortedWith(
                        compareBy({ PRIORITY_LEAGUES.indexOf(it.league) }, { it.time })
                    )
                    val sortedOther = otherMatches.sortedBy { it.time }

                    // "Ưu tiên hiển thị các trận đấu của Uefa Champions League, EPL, Vietnam V-League 1, La Liga, Bundes Liga, Serie A, France L1. Nếu không có mới hiển thị các trận đấu của giải khác."
                    val targetMatches = if (sortedPriority.isNotEmpty()) sortedPriority else sortedOther

                    // Lưu vào cache Map để MatchSourcesDialog truy xuất khi click
                    for (m in grouped.values) {
                        matchGroupsMap[m.key] = m
                    }

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

                    targetMatches.forEachIndexed { idx, mg ->
                        val firstSource = mg.sources.firstOrNull()
                        val matchName = if (mg.awayTeam.isNotBlank()) {
                            "⚽ [${mg.time}] ${mg.homeTeam} vs ${mg.awayTeam} • ${mg.leagueName} (${mg.sources.size} nguồn)"
                        } else {
                            "⚽ [${mg.time}] ${mg.homeTeam} • ${mg.leagueName} (${mg.sources.size} nguồn)"
                        }
                        val matchChannel = Channel(
                            id = "match_${mg.key}",
                            name = matchName,
                            logoUrl = mg.sources.firstOrNull { !it.logoUrl.isNullOrBlank() }?.logoUrl ?: firstSource?.logoUrl,
                            streamUrl = "match://${mg.key}",
                            groupTitle = "⚽ TRỰC TIẾP BÓNG ĐÁ (HÔM NAY)",
                            number = idx + 1
                        )
                        matchesWithRefresh.add(matchChannel)
                    }

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

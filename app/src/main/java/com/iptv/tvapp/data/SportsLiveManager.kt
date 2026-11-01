package com.iptv.tvapp.data

import android.content.Context
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.parser.M3UParser
import com.iptv.tvapp.data.PreferencesHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SportsLiveManager(private val context: Context) {

    private val prefs = PreferencesHelper(context)
    private val httpClient = OkHttpClient.Builder().apply {
        connectTimeout(8, TimeUnit.SECONDS)
        readTimeout(10, TimeUnit.SECONDS)
        writeTimeout(10, TimeUnit.SECONDS)
        followRedirects(true)
        followSslRedirects(true)
    }.build()

    data class MatchGroup(
        val key: String,
        val homeTeam: String,
        val awayTeam: String,
        val time: String,
        val date: String = "",
        val timestamp: Long = 0L,
        val league: String,
        val leagueName: String,
        val sources: MutableList<Channel>
    )

    data class EspnFixture(val team1: String, val team2: String, val league: String)

    fun getMatch(key: String): MatchGroup? {
        matchGroupsMap[key]?.let { return it }
        // Fallback 1: Khớp theo đuôi key (loại bỏ chênh lệch giờ thi đấu)
        val cleanKey = key.substringAfter("_")
        for ((k, v) in matchGroupsMap) {
            if (k.substringAfter("_") == cleanKey) return v
        }
        // Fallback 2: Khớp theo tên CLB đã chuẩn hóa
        for (v in matchGroupsMap.values) {
            if (v.key == key || key.contains(v.key) || v.key.contains(key)) return v
            val vHome = canonicalizeTeam(v.homeTeam)
            val vAway = canonicalizeTeam(v.awayTeam)
            if (vHome.isNotBlank() && key.contains(vHome)) {
                if (vAway.isBlank() || key.contains(vAway)) return v
            }
        }
        // Fallback 3: Tìm kiếm trong danh sách kênh thô của các danh mục hiện có
        val foundSources = mutableListOf<Channel>()
        for (cat in (cachedCategories ?: emptyList())) {
            for (ch in cat.channels) {
                if (ch.streamUrl.startsWith("match://") || ch.id == ACTION_REFRESH_SPORTS) continue
                val cClean = canonicalizeTeam(ch.name)
                val cParts = key.split("_")
                val matchesAll = cParts.all { part -> part.length <= 2 || cClean.contains(part) }
                if (matchesAll) {
                    foundSources.add(ch)
                }
            }
        }
        if (foundSources.isNotEmpty()) {
            val fallbackGroup = MatchGroup(
                key = key,
                homeTeam = foundSources.first().name,
                awayTeam = "",
                time = "LIVE",
                date = "",
                timestamp = System.currentTimeMillis(),
                league = "OTHER",
                leagueName = "Trực tiếp",
                sources = foundSources
            )
            matchGroupsMap[key] = fallbackGroup
            return fallbackGroup
        }
        return null
    }

    companion object {
        const val ACTION_REFRESH_SPORTS = "action_refresh_sports"
        const val CATEGORY_LIVE_EVENTS = "🔴 LIVE EVENTS"
        const val CATEGORY_SPORT_TV = "📺 SPORT 24/7 TV"
        private val matchGroupsMap = ConcurrentHashMap<String, MatchGroup>()
        private val espnFixturesCache = ConcurrentHashMap<String, List<EspnFixture>>()
        @Volatile private var lastEspnFetchTimeMs: Long = 0L
        private const val CACHE_TTL_MS = 30 * 60 * 1000L // Tự động cập nhật 30 phút 1 lần
        private const val ESPN_CACHE_TTL_MS = 60 * 60 * 1000L // Cache lịch thi đấu 1 tiếng
        const val DEFAULT_SPORTS_URL = "https://raw.githubusercontent.com/ndkhoams/iptv/main/thethao.m3u"
        private const val CHROME_UA = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        val PRIORITY_LEAGUES = listOf("UCL", "EPL", "VLEAGUE", "LALIGA", "BUNDESLIGA", "SERIE_A", "LIGUE1")
        val FEATURED_SPORTS = listOf("F1", "TENNIS", "NBA")

        val LEAGUE_LABELS = mapOf(
            "UCL" to "🏆 UEFA Champions League",
            "EPL" to "🏴󠁧󠁢󠁥󠁮󠁧󠁿 Ngoại Hạng Anh",
            "VLEAGUE" to "🇻🇳 V-League 1",
            "LALIGA" to "🇪🇸 La Liga",
            "BUNDESLIGA" to "🇩🇪 Bundesliga",
            "SERIE_A" to "🇮🇹 Serie A",
            "LIGUE1" to "🇫🇷 Ligue 1",
            "F1" to "🏎️ Formula 1",
            "TENNIS" to "🎾 Tennis ATP / Grand Slam",
            "NBA" to "🏀 Bóng rổ NBA",
            "OTHER" to "🌐 Trực tiếp"
        )

        // Bảng ánh xạ định danh chuẩn hóa cho các CLB để gom nhóm các cách viết khác nhau thành 1 trận duy nhất
        val TEAM_ALIASES = listOf(
            "bilbao" to listOf("athletic bilbao", "athletic club", "ath bilbao", "bilbao"),
            "realsociedad" to listOf("real sociedad", "sociedad"),
            "torino" to listOf("torino f.c.", "torino fc", "torino", "torinofc"),
            "frosinone" to listOf("frosinone calcio", "frosinone fc", "frosinone", "frosinonecalcio"),
            "tphcm" to listOf("công an tp.hồ chí minh", "công an tp. hồ chí minh", "cong an ho chi minh", "tp. hồ chí minh", "tp.hồ chí minh", "thành phố hồ chí minh", "hồ chí minh", "ho chi minh", "tp.hcm", "tphcm", "clb tp.hcm"),
            "danang" to listOf("shb đà nẵng", "shb da nang", "đà nẵng", "da nang"),
            "manutd" to listOf("manchester united", "manchester utd", "man united", "man utd", "mu"),
            "mancity" to listOf("manchester city", "man city", "mc"),
            "tottenham" to listOf("tottenham hotspur", "tottenham", "spurs"),
            "wolves" to listOf("wolverhampton", "wolves"),
            "newcastle" to listOf("newcastle united", "newcastle"),
            "westham" to listOf("west ham united", "west ham"),
            "brighton" to listOf("brighton & hove albion", "brighton"),
            "nottingham" to listOf("nottingham forest", "nottingham"),
            "leicester" to listOf("leicester city", "leicester"),
            "ipswich" to listOf("ipswich town", "ipswich"),
            "atleticomadrid" to listOf("atletico madrid", "atletico de madrid", "atletico", "atleti"),
            "realmadrid" to listOf("real madrid"),
            "betis" to listOf("real betis", "betis"),
            "celtavigo" to listOf("celta vigo", "celta"),
            "rayovallecano" to listOf("rayo vallecano", "rayo"),
            "alaves" to listOf("deportivo alaves", "alaves"),
            "valladolid" to listOf("real valladolid", "valladolid"),
            "santander" to listOf("racing santander", "santander"),
            "bayern" to listOf("bayern munich", "bayern munchen", "bayern"),
            "dortmund" to listOf("borussia dortmund", "dortmund"),
            "leverkusen" to listOf("bayer leverkusen", "leverkusen"),
            "leipzig" to listOf("rb leipzig", "leipzig"),
            "frankfurt" to listOf("eintracht frankfurt", "frankfurt"),
            "stuttgart" to listOf("vfb stuttgart", "stuttgart"),
            "monchengladbach" to listOf("borussia monchengladbach", "monchengladbach", "gladbach"),
            "unionberlin" to listOf("1. fc union berlin", "union berlin"),
            "bremen" to listOf("werder bremen", "bremen"),
            "koln" to listOf("1. fc koln", "fc koln", "fc köln", "koln", "köln"),
            "hamburg" to listOf("hamburg sv", "hamburg"),
            "intermilan" to listOf("internazionale", "inter milan", "inter"),
            "acmilan" to listOf("ac milan", "milan"),
            "juventus" to listOf("juventus", "juve"),
            "roma" to listOf("as roma", "roma"),
            "lazio" to listOf("s.s. lazio", "ss lazio", "lazio"),
            "verona" to listOf("hellas verona", "verona"),
            "psg" to listOf("paris saint-germain", "paris sg", "psg"),
            "marseille" to listOf("olympique marseille", "marseille"),
            "lyon" to listOf("olympique lyon", "lyon"),
            "monaco" to listOf("as monaco", "monaco"),
            "auxerre" to listOf("aj auxerre", "auxerre"),
            "strasbourg" to listOf("strasbourg alsace", "strasbourg"),
            "viettel" to listOf("thể công viettel", "thể công", "viettel"),
            "hagl" to listOf("hoàng anh gia lai", "hagl"),
            "cahn" to listOf("công an hà nội", "cahn"),
            "slna" to listOf("sông lam nghệ an", "slna"),
            "namdinh" to listOf("thép xanh nam định", "nam định", "nam dinh"),
            "haiphong" to listOf("hải phòng", "hai phong"),
            "thanhhoa" to listOf("đông á thanh hóa", "thanh hóa", "thanh hoa"),
            "binhduong" to listOf("becamex bình dương", "bình dương", "binh duong"),
            "binhdinh" to listOf("quy nhơn bình định", "bình định", "binh dinh"),
            "quangnam" to listOf("quảng nam", "quang nam"),
            "hatinh" to listOf("hồng lĩnh hà tĩnh", "hà tĩnh", "ha tinh"),
            "hanoi" to listOf("hà nội fc", "clb hà nội", "hà nội", "ha noi")
        )

        val EPL_CLUBS = listOf(
            "arsenal", "aston villa", "bournemouth", "brentford", "brighton", "chelsea", "crystal palace",
            "everton", "fulham", "ipswich", "leicester", "liverpool", "manchester city", "man city",
            "manchester united", "man utd", "man united", "newcastle", "nottingham", "southampton",
            "tottenham", "spurs", "west ham", "wolves", "wolverhampton"
        )

        val LALIGA_CLUBS = listOf(
            "real madrid", "barcelona", "barca", "atletico madrid", "atletico", "athletic bilbao",
            "athletic club", "real sociedad", "villarreal", "real betis", "sevilla", "celta vigo",
            "valencia", "osasuna", "getafe", "girona", "mallorca", "las palmas", "rayo vallecano",
            "espanyol", "alaves", "leganes", "valladolid", "racing santander", "elche"
        )

        val SERIE_A_CLUBS = listOf(
            "juventus", "inter milan", "ac milan", "napoli", "as roma", "roma", "lazio", "s.s. lazio",
            "atalanta", "fiorentina", "bologna", "torino", "como", "venezia", "genoa", "cagliari",
            "lecce", "monza", "parma", "udinese", "empoli", "verona", "hellas verona", "frosinone"
        )

        val BUNDESLIGA_CLUBS = listOf(
            "bayern munich", "bayern", "borussia dortmund", "dortmund", "bayer leverkusen", "leverkusen",
            "rb leipzig", "leipzig", "eintracht frankfurt", "frankfurt", "vfb stuttgart", "stuttgart",
            "wolfsburg", "freiburg", "borussia monchengladbach", "monchengladbach", "union berlin",
            "werder bremen", "bremen", "augsburg", "heidenheim", "mainz", "hoffenheim", "st. pauli",
            "st pauli", "holstein kiel", "bochum", "fc koln", "fc köln", "köln", "hamburg sv"
        )

        val LIGUE1_CLUBS = listOf(
            "psg", "paris saint-germain", "paris sg", "marseille", "olympique marseille", "as monaco",
            "monaco", "olympique lyon", "lyon", "lille", "lens", "rennes", "nice", "reims", "strasbourg",
            "brest", "toulouse", "montpellier", "nantes", "auxerre", "aj auxerre", "le havre", "angers",
            "saint-etienne", "paris fc"
        )

        val VLEAGUE_CLUBS = listOf(
            "hà nội", "ha noi", "hoàng anh gia lai", "hagl", "thể công viettel", "viettel", "nam định",
            "nam dinh", "thép xanh nam định", "hải phòng", "hai phong", "công an hà nội", "cahn",
            "sông lam nghệ an", "slna", "bình dương", "binh duong", "becamex bình dương", "thanh hóa",
            "thanh hoa", "đông á thanh hóa", "shb đà nẵng", "shb da nang", "đà nẵng", "da nang",
            "quy nhơn bình định", "bình định", "binh dinh", "quảng nam", "quang nam", "hồng lĩnh hà tĩnh",
            "hà tĩnh", "ha tinh", "tp.hồ chí minh", "tp hồ chí minh", "clb tp.hcm", "tp.hcm"
        )

        @Volatile
        private var cachedCategories: List<Category>? = null
        @Volatile
        private var lastFetchTimeMs: Long = 0L

        fun clearCache() {
            cachedCategories = null
            lastFetchTimeMs = 0L
        }

        // Bảng tên hiển thị chuẩn hóa theo FotMob & Wikipedia tiếng Việt
        val CANONICAL_DISPLAY_NAMES = mapOf(
            // Ngoại Hạng Anh (FotMob)
            "arsenal" to "Arsenal",
            "astonvilla" to "Aston Villa",
            "bournemouth" to "Bournemouth",
            "brentford" to "Brentford",
            "brighton" to "Brighton",
            "chelsea" to "Chelsea",
            "crystalpalace" to "Crystal Palace",
            "everton" to "Everton",
            "fulham" to "Fulham",
            "ipswich" to "Ipswich Town",
            "leicester" to "Leicester City",
            "liverpool" to "Liverpool",
            "mancity" to "Manchester City",
            "manutd" to "Manchester United",
            "newcastle" to "Newcastle United",
            "nottingham" to "Nottingham Forest",
            "southampton" to "Southampton",
            "tottenham" to "Tottenham Hotspur",
            "westham" to "West Ham United",
            "wolves" to "Wolverhampton",

            // La Liga (FotMob)
            "realmadrid" to "Real Madrid",
            "barcelona" to "Barcelona",
            "atleticomadrid" to "Atletico Madrid",
            "bilbao" to "Athletic Club",
            "realsociedad" to "Real Sociedad",
            "villarreal" to "Villarreal",
            "betis" to "Real Betis",
            "sevilla" to "Sevilla",
            "celtavigo" to "Celta Vigo",
            "valencia" to "Valencia",
            "osasuna" to "Osasuna",
            "getafe" to "Getafe",
            "girona" to "Girona",
            "mallorca" to "Mallorca",
            "laspalmas" to "Las Palmas",
            "rayovallecano" to "Rayo Vallecano",
            "espanyol" to "Espanyol",
            "alaves" to "Alaves",
            "leganes" to "Leganes",
            "valladolid" to "Real Valladolid",
            "santander" to "Racing Santander",
            "elche" to "Elche",

            // Serie A (FotMob)
            "juventus" to "Juventus",
            "intermilan" to "Inter",
            "acmilan" to "Milan",
            "napoli" to "Napoli",
            "roma" to "Roma",
            "lazio" to "Lazio",
            "atalanta" to "Atalanta",
            "fiorentina" to "Fiorentina",
            "bologna" to "Bologna",
            "torino" to "Torino",
            "frosinone" to "Frosinone",
            "como" to "Como",
            "venezia" to "Venezia",
            "genoa" to "Genoa",
            "cagliari" to "Cagliari",
            "lecce" to "Lecce",
            "monza" to "Monza",
            "parma" to "Parma",
            "udinese" to "Udinese",
            "empoli" to "Empoli",
            "verona" to "Hellas Verona",

            // Bundesliga (FotMob)
            "bayern" to "Bayern München",
            "dortmund" to "Borussia Dortmund",
            "leverkusen" to "Bayer Leverkusen",
            "leipzig" to "RB Leipzig",
            "frankfurt" to "Eintracht Frankfurt",
            "stuttgart" to "Stuttgart",
            "wolfsburg" to "Wolfsburg",
            "freiburg" to "Freiburg",
            "monchengladbach" to "Borussia M'gladbach",
            "unionberlin" to "Union Berlin",
            "bremen" to "Werder Bremen",
            "augsburg" to "Augsburg",
            "heidenheim" to "FC Heidenheim",
            "mainz" to "Mainz 05",
            "hoffenheim" to "Hoffenheim",
            "stpauli" to "St. Pauli",
            "holsteinkiel" to "Holstein Kiel",
            "bochum" to "Bochum",
            "koln" to "1. FC Köln",
            "hamburg" to "Hamburg SV",

            // Ligue 1 (FotMob)
            "psg" to "Paris Saint-Germain",
            "marseille" to "Marseille",
            "monaco" to "Monaco",
            "lyon" to "Lyon",
            "lille" to "Lille",
            "lens" to "Lens",
            "rennes" to "Rennes",
            "nice" to "Nice",
            "reims" to "Reims",
            "strasbourg" to "Strasbourg",
            "brest" to "Brest",
            "toulouse" to "Toulouse",
            "montpellier" to "Montpellier",
            "nantes" to "Nantes",
            "auxerre" to "Auxerre",
            "lehavre" to "Le Havre",
            "angers" to "Angers",
            "saintetienne" to "Saint-Etienne",

            // V-League 1 (Chuẩn Wikipedia tiếng Việt)
            "namdinh" to "Thép Xanh Nam Định",
            "hanoi" to "Hà Nội",
            "cahn" to "Công An Hà Nội",
            "viettel" to "Thể Công – Viettel",
            "hatinh" to "Hồng Lĩnh Hà Tĩnh",
            "haiphong" to "Hải Phòng",
            "binhduong" to "Becamex Bình Dương",
            "thanhhoa" to "Đông Á Thanh Hóa",
            "hagl" to "Hoàng Anh Gia Lai",
            "tphcm" to "Công an TP. Hồ Chí Minh",
            "quangnam" to "Quảng Nam",
            "slna" to "Sông Lam Nghệ An",
            "danang" to "SHB Đà Nẵng",
            "binhdinh" to "Quy Nhơn Bình Định"
        )

        fun getStandardTeamName(rawName: String): String {
            val canon = canonicalizeTeam(rawName)
            CANONICAL_DISPLAY_NAMES[canon]?.let { return it }
            return rawName.trim()
        }

        fun canonicalizeTeam(name: String): String {
            val clean = name.lowercase().trim()
                .replace(Regex("""(?i)\b(clb|f\.?\s*c\.?|c\.?\s*f\.?|calcio|club)\b"""), "")
                .trim()
            for ((canon, aliases) in TEAM_ALIASES) {
                if (clean == canon) return canon
                for (al in aliases) {
                    val alClean = al.lowercase().replace(Regex("""[^a-z0-9\p{L}]"""), "")
                    val targetClean = clean.replace(Regex("""[^a-z0-9\p{L}]"""), "")
                    if (targetClean == alClean || targetClean.contains(alClean) || alClean.contains(targetClean)) {
                        return canon
                    }
                }
            }
            return clean.replace(Regex("""[^a-z0-9]"""), "")
        }
    }

    fun clearCache() {
        cachedCategories = null
        lastFetchTimeMs = 0L
        matchGroupsMap.clear()
        espnFixturesCache.clear()
        lastEspnFetchTimeMs = 0L
    }

    /**
     * Tự động lấy lịch thi đấu thực tế hôm nay từ ESPN Scoreboard API để đối chiếu lần 2
     */
    private fun fetchTodayEspnFixtures() {
        val now = System.currentTimeMillis()
        if (espnFixturesCache.isNotEmpty() && (now - lastEspnFetchTimeMs) < ESPN_CACHE_TTL_MS) {
            return
        }

        val leagues = mapOf(
            "EPL" to "eng.1",
            "UCL" to "uefa.champions",
            "LALIGA" to "esp.1",
            "BUNDESLIGA" to "ger.1",
            "SERIE_A" to "ita.1",
            "LIGUE1" to "fra.1"
        )

        for ((leagueKey, code) in leagues) {
            try {
                val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/$code/scoreboard"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", CHROME_UA)
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val root = JSONObject(bodyStr)
                        val events = root.optJSONArray("events")
                        if (events != null) {
                            val fixtures = mutableListOf<EspnFixture>()
                            for (i in 0 until events.length()) {
                                val ev = events.getJSONObject(i)
                                val comps = ev.optJSONArray("competitions")
                                if (comps != null && comps.length() > 0) {
                                    val competitors = comps.getJSONObject(0).optJSONArray("competitors")
                                    if (competitors != null && competitors.length() >= 2) {
                                        val t1 = competitors.getJSONObject(0).optJSONObject("team")?.optString("name", "") ?: ""
                                        val t2 = competitors.getJSONObject(1).optJSONObject("team")?.optString("name", "") ?: ""
                                        if (t1.isNotBlank() && t2.isNotBlank()) {
                                            fixtures.add(EspnFixture(t1, t2, leagueKey))
                                        }
                                    }
                                }
                            }
                            espnFixturesCache[leagueKey] = fixtures
                        }
                    }
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi mạng cục bộ với 1 giải
            }
        }
        lastEspnFetchTimeMs = now
    }

    private fun matchEspnFixture(home: String, away: String): String? {
        val h = home.lowercase()
        val a = away.lowercase()
        for ((league, fixtures) in espnFixturesCache) {
            for (f in fixtures) {
                val t1 = f.team1.lowercase()
                val t2 = f.team2.lowercase()
                val match1 = (h.contains(t1) || t1.contains(h)) && (a.contains(t2) || t2.contains(a))
                val match2 = (h.contains(t2) || t2.contains(h)) && (a.contains(t1) || t1.contains(a))
                if (match1 || match2) return league
            }
        }
        return null
    }

    /**
     * Nhận diện giải đấu:
     * 1. Đối chiếu lịch thi đấu thực tế hôm nay từ ESPN
     * 2. Kiểm tra các môn thể thao nổi bật (F1, Tennis ATP/Grand Slam, NBA)
     * 3. Kiểm tra từ khóa giải đấu trực tiếp trong tiêu đề
     * 4. So sánh CẢ 2 CLB để chắc chắn chứ không chỉ 1 CLB
     */
    private fun detectLeague(text: String, homeTeam: String, awayTeam: String): String {
        val t = text.lowercase()

        // 1. Kiểm tra các môn thể thao nổi bật được yêu cầu (F1, Tennis Grand Slam/ATP, NBA)
        if (t.contains("f1") || t.contains("formula 1") || t.contains("formula one") || t.contains("grand prix") || t.contains("công thức 1")) {
            return "F1"
        }
        if (t.contains("grand slam") || t.contains("atp 1000") || t.contains("atp1000") || t.contains("wimbledon") ||
            t.contains("us open") || t.contains("australian open") || t.contains("roland garros") || t.contains("tennis") || t.contains("quần vợt")) {
            return "TENNIS"
        }
        if (t.contains("nba") || t.contains("bóng rổ nba")) {
            return "NBA"
        }

        // 2. Đối chiếu lịch thi đấu thực tế hôm nay
        val espnLeague = matchEspnFixture(homeTeam, awayTeam)
        if (espnLeague != null) return espnLeague

        // 3. Kiểm tra trực tiếp tên giải đấu trong tiêu đề
        if ((t.contains("uefa champions") || t.contains("cúp c1") || t.contains("cup c1") || t.contains("champions league")) &&
            !t.contains("afc") && !t.contains("women")) {
            return "UCL"
        }
        if (t.contains("premier league") || t.contains("ngoại hạng anh")) return "EPL"
        if (t.contains("la liga") || t.contains("laliga") || t.contains("primera division")) return "LALIGA"
        if ((t.contains("serie a") || t.contains("serie-a")) && !t.contains("serie b")) return "SERIE_A"
        if (t.contains("bundesliga") && !t.contains("bundesliga 2")) return "BUNDESLIGA"
        if ((t.contains("ligue 1") || t.contains("ligue1")) && !t.contains("ligue 2")) return "LIGUE1"
        if (t.contains("v.league 1") || t.contains("v-league 1") || t.contains("vleague 1") || t.contains("v.league-1")) return "VLEAGUE"

        // 4. So sánh CẢ 2 CLB để chắc chắn (không nhầm khi chỉ có 1 đội hoặc đội bóng chuyền)
        val h = homeTeam.lowercase()
        val a = awayTeam.lowercase()
        if (h.isNotBlank() && a.isNotBlank()) {
            if (EPL_CLUBS.any { h.contains(it) || it.contains(h) } && EPL_CLUBS.any { a.contains(it) || it.contains(a) }) return "EPL"
            if (LALIGA_CLUBS.any { h.contains(it) || it.contains(h) } && LALIGA_CLUBS.any { a.contains(it) || it.contains(a) }) return "LALIGA"
            if (SERIE_A_CLUBS.any { h.contains(it) || it.contains(h) } && SERIE_A_CLUBS.any { a.contains(it) || it.contains(a) }) return "SERIE_A"
            if (BUNDESLIGA_CLUBS.any { h.contains(it) || it.contains(h) } && BUNDESLIGA_CLUBS.any { a.contains(it) || it.contains(a) }) return "BUNDESLIGA"
            if (LIGUE1_CLUBS.any { h.contains(it) || it.contains(h) } && LIGUE1_CLUBS.any { a.contains(it) || it.contains(a) }) return "LIGUE1"
            if (VLEAGUE_CLUBS.any { h.contains(it) || it.contains(h) } && VLEAGUE_CLUBS.any { a.contains(it) || it.contains(a) }) return "VLEAGUE"
        }

        return "OTHER"
    }

    /**
     * Lấy URI logo của giải đấu thay vì logo của luồng
     */
    fun getLeagueLogoUri(league: String): String {
        val resName = when (league) {
            "UCL" -> "ic_league_ucl"
            "EPL" -> "ic_league_epl"
            "VLEAGUE" -> "ic_league_vleague"
            "LALIGA" -> "ic_league_laliga"
            "BUNDESLIGA" -> "ic_league_bundesliga"
            "SERIE_A" -> "ic_league_seriea"
            "LIGUE1" -> "ic_league_ligue1"
            "F1" -> "ic_league_f1"
            "TENNIS" -> "ic_league_tennis"
            "NBA" -> "ic_league_nba"
            else -> "ic_nav_sports"
        }
        return "android.resource://${context.packageName}/drawable/$resName"
    }

    /**
     * Tách tên 2 đội và nhận diện giải đấu từ tiêu đề luồng (dùng cho mọi nguồn TV/Thể thao)
     */
    fun extractTeamsAndLeague(rawName: String): Triple<String, String, String> {
        val cleaned = cleanTeamName(rawName)
        val parts = cleaned.split(Regex("""\s+(?:vs|Vs|VS|-)\s+"""))
        val homeTeam = if (parts.isNotEmpty()) parts[0].trim() else cleaned
        val awayTeam = if (parts.size >= 2) parts[1].trim() else ""
        val lg = detectLeague(rawName, homeTeam, awayTeam)
        return Triple(homeTeam, awayTeam, lg)
    }

    /**
     * Lọc loại bỏ hoàn toàn các trận bóng đá nữ (Chỉ giữ bóng đá nam theo yêu cầu)
     */
    fun isWomenMatch(vararg texts: String): Boolean {
        val combined = texts.joinToString(" ").lowercase()
        val patterns = listOf(
            Regex("""\b(nu|nữ|women|woman|wsl|ladies|femenino|frauen|dames|femmes|féminin|femme)\b"""),
            Regex("""\(w\)|\[w\]|\(nữ\)|\[nữ\]|\(nu\)|\[nu\]"""),
            Regex("""\b(u\d{2}\s+nữ|nữ\s+u\d{2})\b"""),
            Regex("""\bnu\s+"""),
            Regex("""\bnữ\s+"""),
            Regex("""\s+nu\b"""),
            Regex("""\s+nữ\b""")
        )
        return patterns.any { it.containsMatchIn(combined) }
    }

    /**
     * Lọc các môn thể thao: Giữ bóng đá, F1, Quần vợt (ATP/Grand Slam), Bóng rổ NBA
     * Loại bỏ bóng đá nữ, bóng chuyền 🏐, bóng bàn 🏓, cầu lông 🏸, bida 🎱
     */
    private fun isEligibleSportsMatch(name: String, groupTitle: String): Boolean {
        if (isWomenMatch(name, groupTitle)) {
            return false
        }
        val nonSports = listOf("🏐", "🏸", "🏓", "🎱", "🥊", "🥋", "🏒", "bóng chuyền", "bóng bàn", "cầu lông")
        if (nonSports.any { name.contains(it) || groupTitle.contains(it, ignoreCase = true) }) {
            return false
        }
        return true
    }

    /**
     * Kiểm tra ngày: Chỉ lấy các trận đấu diễn ra HÔM NAY hoặc rạng sáng mai
     * Tránh hiển thị các link cũ từ tuần trước (ví dụ 25/10)
     */
    private fun isDateTodayOrUpcoming(name: String): Boolean {
        val dm = Regex("""\b(\d{1,2})[/-](\d{1,2})\b""").find(name) ?: return true
        val d = dm.groupValues[1].toIntOrNull() ?: return true
        val m = dm.groupValues[2].toIntOrNull() ?: return true

        val cal = Calendar.getInstance()
        val todayD = cal.get(Calendar.DAY_OF_MONTH)
        val todayM = cal.get(Calendar.MONTH) + 1

        if (m == todayM && (d == todayD || d == todayD + 1)) return true
        if (m == todayM + 1 && d == 1 && todayD >= 28) return true

        return false
    }

    /**
     * Làm sạch tiêu đề trận đấu để gom nhóm đồng nhất 100%
     */
    private fun cleanTeamName(name: String): String {
        var s = name
        // 1. Xóa emoji
        s = s.replace(Regex("""[\u2600-\u26ff\u2700-\u27bf\uD83C-\uDBFF\uDC00-\uDFFF]"""), "")
        // 2. Xóa các khối ngoặc vuông [18:30], [hls], [geo], [hls 2]
        s = s.replace(Regex("""\[[^\]]*\]"""), "")
        // 3. Xóa các khối ngoặc đơn (BLV ...), (Lưu Bang), (TIGER), (HD2)
        s = s.replace(Regex("""\([^\)]*\)"""), "")
        // 4. Xóa ngày tháng dd/MM hoặc dd-MM
        s = s.replace(Regex("""\b\d{1,2}[/-]\d{1,2}\b"""), "")
        // 5. Xóa giờ thi đấu HH:mm
        s = s.replace(Regex("""\b\d{1,2}:\d{2}\b"""), "")
        // 6. Xóa tên nhà cung cấp / website (có hoặc không có dấu gạch ngang, có hoặc không có chữ TV)
        s = s.replace(Regex("""(?i)(?:-|\s)?\s*(?:Chuối Chiên|Gà Vàng|Xôi Lạc|Cà Khịa|TQM|Cola|Bia Ôm|Phá Làng|Vua Sân Cỏ|S8|Khô Gà|Giờ Vàng|Sao Kê|Xôi Gấc|Tiếu Lâm)(?:\s+TV)?\b"""), "")
        // 7. Xóa các từ phụ trợ kỹ thuật và tiền tố CLB
        s = s.replace(Regex("""(?i)\b(clb|f\.?\s*c\.?|c\.?\s*f\.?|s\.?\s*s\.?\s*c?\.?|a\.?\s*c\.?|a\.?\s*s\.?|s\.?\s*c\.?|f\.?\s*k\.?|r\.?\s*c\.?|olympique|calcio|club|hls|geo|hd|fhd|sd|4k|live)\b\.?"""), "")
        // 8. Cắt các ký tự phân cách ở đầu và cuối chuỗi
        s = s.replace(Regex("""^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$"""), "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Tải danh sách trực tiếp thể thao từ file thethao.m3u (được máy chủ Armbian tự động đẩy lên GitHub)
     */
    suspend fun getSportsCategories(forceRefresh: Boolean = false): List<Category> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (forceRefresh) {
                clearCache()
            } else if (cachedCategories != null && (now - lastFetchTimeMs) < CACHE_TTL_MS) {
                return@withContext cachedCategories!!
            }

            val cacheFile = File(context.cacheDir, "thethao_sports_cache.m3u")
            if (forceRefresh && cacheFile.exists()) {
                cacheFile.delete()
            }

            val baseSportsUrl = prefs.sportsPlaylistUrl.ifBlank { DEFAULT_SPORTS_URL }
            val sportsUrl = if (forceRefresh) {
                if (baseSportsUrl.contains("?")) "$baseSportsUrl&_ts=$now" else "$baseSportsUrl?_ts=$now"
            } else baseSportsUrl

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
                // Sử dụng file cache đã lưu nếu lỗi mạng
            }

            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                return@withContext cachedCategories ?: emptyList()
            }

            try {
                // Lấy lịch thi đấu thực tế hôm nay từ ESPN
                fetchTodayEspnFixtures()

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
                // Nhóm 1: "🔴 LIVE EVENTS" - Nhóm gom các luồng cùng 1 trận đấu thành 1 title duy nhất
                val matchChannels = channels.filter {
                    !it.groupTitle.contains("QUỐC TẾ", ignoreCase = true) &&
                    !it.name.contains("[24/7]", ignoreCase = true) &&
                    isEligibleSportsMatch(it.name, it.groupTitle) &&
                    isDateTodayOrUpcoming(it.name)
                }

                val categories = mutableListOf<Category>()

                if (matchChannels.isNotEmpty()) {
                    matchGroupsMap.clear()
                    val grouped = LinkedHashMap<String, MatchGroup>()

                    for (ch in matchChannels) {
                        val rawName = ch.name
                        val cleaned = cleanTeamName(rawName)
                        val timeMatch = Regex("""\b(\d{1,2}:\d{2})\b""").find(rawName)
                        val timeStr = timeMatch?.groupValues?.get(1) ?: "LIVE"

                        val dateMatch = Regex("""\b(\d{1,2})[/-](\d{1,2})\b""").find(rawName)
                        val nowCal = Calendar.getInstance()
                        val curDay = nowCal.get(Calendar.DAY_OF_MONTH)
                        val curMonth = nowCal.get(Calendar.MONTH) + 1
                        val curHour = nowCal.get(Calendar.HOUR_OF_DAY)

                        var mDay = curDay
                        var mMonth = curMonth

                        if (dateMatch != null) {
                            mDay = dateMatch.groupValues[1].toIntOrNull() ?: curDay
                            mMonth = dateMatch.groupValues[2].toIntOrNull() ?: curMonth
                        } else if (timeStr != "LIVE") {
                            val h = timeStr.substringBefore(":").toIntOrNull() ?: 12
                            if (curHour >= 12 && h < 7) {
                                val nextDayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
                                mDay = nextDayCal.get(Calendar.DAY_OF_MONTH)
                                mMonth = nextDayCal.get(Calendar.MONTH) + 1
                            }
                        }

                        val dateStr = String.format("%02d/%02d", mDay, mMonth)

                        val matchCal = Calendar.getInstance().apply {
                            set(Calendar.MONTH, mMonth - 1)
                            set(Calendar.DAY_OF_MONTH, mDay)
                            if (timeStr != "LIVE") {
                                val parts = timeStr.split(":")
                                set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: 0)
                                set(Calendar.MINUTE, parts.getOrNull(1)?.toIntOrNull() ?: 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                        }
                        val matchTimestamp = matchCal.timeInMillis

                        val parts = cleaned.split(Regex("""\s+(?:vs|Vs|VS|-)\s+"""))
                        val (homeTeam, awayTeam) = if (parts.size >= 2) {
                            parts[0].trim() to parts[1].trim()
                        } else {
                            cleaned.trim() to ""
                        }

                        if (isWomenMatch(rawName, homeTeam, awayTeam)) {
                            continue
                        }

                        val cHome = canonicalizeTeam(homeTeam)
                        val cAway = canonicalizeTeam(awayTeam)
                        val stdHome = getStandardTeamName(homeTeam)
                        val stdAway = if (awayTeam.isNotBlank()) getStandardTeamName(awayTeam) else ""
                        val sortedCanonPair = if (cAway.isNotBlank()) listOf(cHome, cAway).sorted() else listOf(cHome)
                        val normKey = if (cAway.isNotBlank()) {
                            "${dateStr}_${timeStr}_${sortedCanonPair.joinToString("_")}"
                        } else {
                            "${dateStr}_${timeStr}_$cHome"
                        }

                        // Tìm xem đã có trận nào của 2 đội này trong cùng ngày (hoặc trong khoảng +/- 4 tiếng) chưa
                        val existing = grouped.values.firstOrNull { ex ->
                            val exHomeC = canonicalizeTeam(ex.homeTeam)
                            val exAwayC = canonicalizeTeam(ex.awayTeam)
                            val exPair = if (exAwayC.isNotBlank()) listOf(exHomeC, exAwayC).sorted() else listOf(exHomeC)
                            (exPair == sortedCanonPair) && (ex.date == dateStr || Math.abs(ex.timestamp - matchTimestamp) < 4 * 3600 * 1000L)
                        } ?: grouped[normKey]

                        if (existing != null) {
                            existing.sources.add(ch)
                            if (existing.league == "OTHER") {
                                val lg = detectLeague(rawName, existing.homeTeam, existing.awayTeam)
                                if (lg != "OTHER") {
                                    val updated = existing.copy(
                                        league = lg,
                                        leagueName = LEAGUE_LABELS[lg] ?: "🌐 Trực tiếp"
                                    )
                                    grouped[existing.key] = updated
                                }
                            }
                        } else {
                            val lg = detectLeague(rawName, stdHome, stdAway)
                            val lgName = LEAGUE_LABELS[lg] ?: "🌐 Trực tiếp"
                            val group = MatchGroup(
                                key = normKey,
                                homeTeam = stdHome.ifBlank { "Đội nhà" },
                                awayTeam = stdAway.ifBlank { "Đội khách" },
                                time = timeStr,
                                date = dateStr,
                                timestamp = matchTimestamp,
                                league = lg,
                                leagueName = lgName,
                                sources = mutableListOf(ch)
                            )
                            grouped[normKey] = group
                        }
                    }

                    // Tách thành:
                    // 1. Giải bóng đá ưu tiên (7 giải hàng đầu)
                    val priorityMatches = grouped.values.filter { it.league in PRIORITY_LEAGUES }
                    // 2. Các trận thể thao lớn nổi bật (F1, Tennis Grand Slam/ATP, NBA)
                    val featuredSportsMatches = grouped.values.filter { it.league in FEATURED_SPORTS }
                    // 3. Các trận bóng đá khác
                    val otherMatches = grouped.values.filter { it.league !in PRIORITY_LEAGUES && it.league !in FEATURED_SPORTS }

                    // Sắp xếp:
                    // - Ưu tiên UCL -> EPL -> VLEAGUE -> LALIGA -> BUNDESLIGA -> SERIE_A -> LIGUE1, sau đó theo mốc thời gian thực tế
                    val sortedPriority = priorityMatches.sortedWith(
                        compareBy({ PRIORITY_LEAGUES.indexOf(it.league) }, { it.timestamp })
                    )
                    val sortedOther = otherMatches.sortedBy { it.timestamp }
                    val sortedFeaturedSports = featuredSportsMatches.sortedBy { it.timestamp }

                    val targetMatches = mutableListOf<MatchGroup>()
                    if (sortedPriority.isNotEmpty()) {
                        targetMatches.addAll(sortedPriority)
                    } else {
                        targetMatches.addAll(sortedOther)
                    }
                    targetMatches.addAll(sortedFeaturedSports)

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
                        groupTitle = CATEGORY_LIVE_EVENTS,
                        number = 0
                    )
                    matchesWithRefresh.add(refreshItem)

                    targetMatches.forEachIndexed { idx, mg ->
                        val sportIcon = when (mg.league) {
                            "F1" -> "🏎️"
                            "TENNIS" -> "🎾"
                            "NBA" -> "🏀"
                            else -> "⚽"
                        }
                        val timePart = if (mg.date.isNotBlank()) "[${mg.time} ${mg.date}]" else "[${mg.time}]"
                        val matchName = if (mg.awayTeam.isNotBlank()) {
                            "$sportIcon $timePart ${mg.homeTeam} vs ${mg.awayTeam} • ${mg.leagueName} (${mg.sources.size} nguồn)"
                        } else {
                            "$sportIcon $timePart ${mg.homeTeam} • ${mg.leagueName} (${mg.sources.size} nguồn)"
                        }
                        val leagueLogoUri = getLeagueLogoUri(mg.league)
                        val matchChannel = Channel(
                            id = "match_${mg.key}",
                            name = matchName,
                            logoUrl = leagueLogoUri,
                            streamUrl = "match://${mg.key}",
                            groupTitle = CATEGORY_LIVE_EVENTS,
                            number = idx + 1
                        )
                        matchesWithRefresh.add(matchChannel)
                    }

                    categories.add(Category(CATEGORY_LIVE_EVENTS, matchesWithRefresh))
                }

                // Nhóm 2: "📺 SPORT 24/7 TV"
                val intlChannels = channels.filter {
                    it.groupTitle.contains("SPORT 24/7", ignoreCase = true) ||
                    it.groupTitle.contains("QUỐC TẾ", ignoreCase = true) ||
                    it.name.contains("[24/7]", ignoreCase = true)
                }
                if (intlChannels.isNotEmpty()) {
                    intlChannels.forEachIndexed { idx, ch -> ch.number = idx + 1 }
                    categories.add(Category(CATEGORY_SPORT_TV, intlChannels.toMutableList()))
                }

                // Các nhóm theo nguồn: "🍌 Chuối Chiên TV", "🐔 Gà Vàng / Tiếu Lâm", "🌶️ Cà Khịa TV", ...
                val groupedBySource = channels.groupBy { it.groupTitle }
                for ((groupName, groupList) in groupedBySource) {
                    if (groupName.isNotBlank() &&
                        !groupName.contains("LIVE EVENTS", ignoreCase = true) &&
                        !groupName.contains("TRỰC TIẾP", ignoreCase = true) &&
                        !groupName.contains("SPORT 24/7", ignoreCase = true) &&
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

    suspend fun resolveLiveStreamUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        rawUrl
    }
}

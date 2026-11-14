package com.iptv.tvapp.util

import java.text.Normalizer
import java.util.Locale

object TeamLogoHelper {

    // Danh sách quốc kỳ chuẩn theo ISO code qua CDN FlagCDN
    private val COUNTRY_FLAGS = mapOf(
        "vietnam" to "vn", "viet nam" to "vn", "u23 viet nam" to "vn", "u22 viet nam" to "vn",
        "china" to "cn", "trung quoc" to "cn", "china u19" to "cn", "trung quoc u19" to "cn",
        "tajikistan" to "tj", "tajikistan u19" to "tj",
        "italy" to "it", "y" to "it",
        "argentina" to "ar",
        "dai bac trung hoa" to "tw", "chinese taipei" to "tw", "taiwan" to "tw",
        "kyrgyzstan" to "kg",
        "qatar" to "qa", "qatar u23" to "qa",
        "thailand" to "th", "thai lan" to "th",
        "brunei" to "bn", "brunei darussalam" to "bn",
        "england" to "gb-eng", "anh" to "gb-eng",
        "france" to "fr", "phap" to "fr",
        "germany" to "de", "duc" to "de",
        "spain" to "es", "tay ban nha" to "es",
        "portugal" to "pt", "bo dao nha" to "pt",
        "netherlands" to "nl", "ha lan" to "nl",
        "brazil" to "br",
        "japan" to "jp", "nhat ban" to "jp",
        "korea" to "kr", "south korea" to "kr", "han quoc" to "kr",
        "indonesia" to "id",
        "malaysia" to "my",
        "singapore" to "sg",
        "philippines" to "ph",
        "laos" to "la", "lao" to "la",
        "cambodia" to "kh", "campuchia" to "kh",
        "myanmar" to "mm",
        "australia" to "au", "uc" to "au",
        "russia" to "ru", "nga" to "ru",
        "belgium" to "be", "bi" to "be",
        "croatia" to "hr",
        "switzerland" to "ch", "thuy si" to "ch",
        "sweden" to "se", "thuy dien" to "se",
        "denmark" to "dk", "dan mach" to "dk",
        "norway" to "no", "na uy" to "no",
        "poland" to "pl", "ba lan" to "pl",
        "turkey" to "tr", "tho nhi ky" to "tr",
        "serbia" to "rs",
        "greece" to "gr", "hy lap" to "gr",
        "scotland" to "gb-sct",
        "north macedonia" to "mk", "bac macedonia" to "mk",
        "slovenia" to "si",
        "slovakia" to "sk",
        "czech republic" to "cz", "sec" to "cz"
    )

    // Logo câu lạc bộ phổ biến toàn cầu qua ESPN CDN
    private val CLUB_LOGOS = mapOf(
        "arsenal" to "https://a.espncdn.com/i/teamlogos/soccer/500/359.png",
        "mancity" to "https://a.espncdn.com/i/teamlogos/soccer/500/382.png",
        "manchestercity" to "https://a.espncdn.com/i/teamlogos/soccer/500/382.png",
        "liverpool" to "https://a.espncdn.com/i/teamlogos/soccer/500/364.png",
        "chelsea" to "https://a.espncdn.com/i/teamlogos/soccer/500/363.png",
        "manchesterunited" to "https://a.espncdn.com/i/teamlogos/soccer/500/360.png",
        "manunited" to "https://a.espncdn.com/i/teamlogos/soccer/500/360.png",
        "tottenham" to "https://a.espncdn.com/i/teamlogos/soccer/500/367.png",
        "tottenhamhotspur" to "https://a.espncdn.com/i/teamlogos/soccer/500/367.png",
        "astonvilla" to "https://a.espncdn.com/i/teamlogos/soccer/500/362.png",
        "newcastle" to "https://a.espncdn.com/i/teamlogos/soccer/500/361.png",
        "realmadrid" to "https://a.espncdn.com/i/teamlogos/soccer/500/86.png",
        "barcelona" to "https://a.espncdn.com/i/teamlogos/soccer/500/83.png",
        "atleticomadrid" to "https://a.espncdn.com/i/teamlogos/soccer/500/1068.png",
        "bayernmunich" to "https://a.espncdn.com/i/teamlogos/soccer/500/132.png",
        "borussiadortmund" to "https://a.espncdn.com/i/teamlogos/soccer/500/124.png",
        "bayerleverkusen" to "https://a.espncdn.com/i/teamlogos/soccer/500/131.png",
        "psg" to "https://a.espncdn.com/i/teamlogos/soccer/500/160.png",
        "parissaintgermain" to "https://a.espncdn.com/i/teamlogos/soccer/500/160.png",
        "juventus" to "https://a.espncdn.com/i/teamlogos/soccer/500/111.png",
        "intermilan" to "https://a.espncdn.com/i/teamlogos/soccer/500/110.png",
        "inter" to "https://a.espncdn.com/i/teamlogos/soccer/500/110.png",
        "acmilan" to "https://a.espncdn.com/i/teamlogos/soccer/500/103.png",
        "milan" to "https://a.espncdn.com/i/teamlogos/soccer/500/103.png",
        "roma" to "https://a.espncdn.com/i/teamlogos/soccer/500/104.png",
        "napoli" to "https://a.espncdn.com/i/teamlogos/soccer/500/114.png"
    )

    fun normalize(str: String?): String {
        if (str.isNullOrBlank()) return ""
        val nfd = Normalizer.normalize(str, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace("đ", "d").replace("Đ", "d")
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .lowercase(Locale.ROOT)
            .trim()
    }

    fun getInitial(name: String?): String {
        if (name.isNullOrBlank()) return "CLB"
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.size >= 3 -> "${words[0].first()}${words[1].first()}${words[2].first()}".uppercase(Locale.ROOT)
            words.size == 2 -> "${words[0].first()}${words[1].take(2)}".uppercase(Locale.ROOT)
            else -> name.take(3).uppercase(Locale.ROOT)
        }
    }

    fun getTeamLogoUrl(teamName: String, directLogo: String? = null): String? {
        if (!directLogo.isNullOrBlank() && directLogo.startsWith("http")) {
            return directLogo
        }

        val norm = normalize(teamName)
        if (norm.isBlank()) return null

        // 1. Kiểm tra Premapped Club logos
        CLUB_LOGOS[norm]?.let { return it }

        // 2. Kiểm tra Country Flags
        val flagCode = COUNTRY_FLAGS[norm] ?: COUNTRY_FLAGS.entries.firstOrNull {
            norm.contains(it.key) || it.key.contains(norm)
        }?.value

        if (!flagCode.isNullOrBlank()) {
            return "https://flagcdn.com/w80/$flagCode.png"
        }

        return null
    }
}


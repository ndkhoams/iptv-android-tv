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
        "napoli" to "https://a.espncdn.com/i/teamlogos/soccer/500/114.png",

        // V-League & CLB Việt Nam (Official Verified Crests)
        "thepxanhnamdinh" to "https://img.cltvap.cc/football/team/bc5db4dd418cb566a4475649876b55bb.png",
        "namdinh" to "https://img.cltvap.cc/football/team/bc5db4dd418cb566a4475649876b55bb.png",
        "conganhanoi" to "https://img.cltvap.cc/football/team/9882bbda1cd86a2af4e724f399d1a1c8.png",
        "cahn" to "https://img.cltvap.cc/football/team/9882bbda1cd86a2af4e724f399d1a1c8.png",
        "hanoi" to "https://img.cltvap.cc/football/team/7146224d9ef4e5d2eb712b4e71bdfaa4.png",
        "hanoifc" to "https://img.cltvap.cc/football/team/7146224d9ef4e5d2eb712b4e71bdfaa4.png",
        "thecongviettel" to "https://assets.football-logos.cc/logos/vietnam/700x700/the-cong-viettel.ce5afe8a.png",
        "viettel" to "https://assets.football-logos.cc/logos/vietnam/700x700/the-cong-viettel.ce5afe8a.png",
        "hoanganhgialai" to "https://img.cltvap.cc/football/team/df2ae58b9e658494b940b0bd782901b8.png",
        "hagl" to "https://img.cltvap.cc/football/team/df2ae58b9e658494b940b0bd782901b8.png",
        "songlamnghean" to "https://img.cltvap.cc/football/team/7f95b001711ce3978d498602e1d2c9fd.png",
        "slna" to "https://img.cltvap.cc/football/team/7f95b001711ce3978d498602e1d2c9fd.png",
        "haiphong" to "https://assets.football-logos.cc/logos/vietnam/700x700/hai-phong.89943111.png",
        "binhduong" to "https://assets.football-logos.cc/logos/vietnam/700x700/becamex-tp-ho-chi-minh.e1813f36.png",
        "becamexbinhduong" to "https://assets.football-logos.cc/logos/vietnam/700x700/becamex-tp-ho-chi-minh.e1813f36.png",
        "thanhhoa" to "https://assets.football-logos.cc/logos/vietnam/700x700/thanh-hoa.0708698c.png",
        "dongathanhhoa" to "https://assets.football-logos.cc/logos/vietnam/700x700/thanh-hoa.0708698c.png",
        "binhdinh" to "https://assets.football-logos.cc/logos/vietnam/700x700/quy-nhon-united.9a9b4368.png",
        "quynhonbinhdinh" to "https://assets.football-logos.cc/logos/vietnam/700x700/quy-nhon-united.9a9b4368.png",
        "danang" to "https://img.cltvap.cc/football/team/9eec5ce9b0aa3a7010cf0de17509948a.png",
        "shbdanang" to "https://img.cltvap.cc/football/team/9eec5ce9b0aa3a7010cf0de17509948a.png",
        "hatinh" to "https://img.cltvap.cc/football/team/83dd94c5ca68e8f9a3980f036afcb511.png",
        "honglinhhatinh" to "https://img.cltvap.cc/football/team/83dd94c5ca68e8f9a3980f036afcb511.png",
        "tphochiminh" to "https://assets.football-logos.cc/logos/vietnam/700x700/tp-ho-chi-minh.6a41e4cf.png",
        "tphcm" to "https://assets.football-logos.cc/logos/vietnam/700x700/tp-ho-chi-minh.6a41e4cf.png",
        "hcmc" to "https://assets.football-logos.cc/logos/vietnam/700x700/tp-ho-chi-minh.6a41e4cf.png",
        "ninhbinh" to "https://img.cltvap.cc/football/team/5452226660be0829fdb40967dd4b0b77.png",
        "bacninh" to "https://img.cltvap.cc/football/team/40b7d679de613aedfb80bcaba85fa7e0.png",
        "pvfcand" to "https://img.cltvap.cc/football/team/16b802abec41440eb4996fc68f16125b.png",
        "dongtamlongan" to "https://img.cltvap.cc/football/team/347d428c24e85dbda67f7817e7d80ebf.png",
        "longan" to "https://img.cltvap.cc/football/team/347d428c24e85dbda67f7817e7d80ebf.png",
        "hudahue" to "https://img.cltvap.cc/football/team/d183acf4e1500de34a9bb72ff77f2ecc.jpg",
        "hue" to "https://img.cltvap.cc/football/team/d183acf4e1500de34a9bb72ff77f2ecc.jpg"
    )

    private val logoUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val initialCache = java.util.concurrent.ConcurrentHashMap<String, String>()

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
        initialCache[name]?.let { return it }
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val res = when {
            words.size >= 3 -> "${words[0].first()}${words[1].first()}${words[2].first()}".uppercase(Locale.ROOT)
            words.size == 2 -> "${words[0].first()}${words[1].take(2)}".uppercase(Locale.ROOT)
            else -> name.take(3).uppercase(Locale.ROOT)
        }
        initialCache[name] = res
        return res
    }

    fun getTeamLogoUrl(teamName: String, directLogo: String? = null): String? {
        val norm = normalize(teamName)

        // 1. Ưu tiên kiểm tra danh bạ CLB chính thức đã verify
        if (norm.isNotBlank()) {
            CLUB_LOGOS[norm]?.let {
                logoUrlCache[teamName] = it
                return it
            }
        }

        // 2. Direct logo nếu hợp lệ và không phải nguồn lỗi (apiv3.apifootball.com / favicon)
        val isBadLogo = directLogo.isNullOrBlank() || directLogo.contains("apiv3.apifootball.com/badges") || directLogo.contains("favicon")
        if (!directLogo.isNullOrBlank() && directLogo.startsWith("http") && !isBadLogo) {
            return directLogo
        }

        val cached = logoUrlCache[teamName]
        if (cached != null) {
            return if (cached.isEmpty()) null else cached
        }

        if (norm.isBlank()) {
            logoUrlCache[teamName] = ""
            return null
        }

        // 2. Kiểm tra Country Flags
        val flagCode = COUNTRY_FLAGS[norm] ?: COUNTRY_FLAGS.entries.firstOrNull {
            norm.contains(it.key) || it.key.contains(norm)
        }?.value

        if (!flagCode.isNullOrBlank()) {
            val url = "https://flagcdn.com/w80/$flagCode.png"
            logoUrlCache[teamName] = url
            return url
        }

        logoUrlCache[teamName] = ""
        return null
    }
}


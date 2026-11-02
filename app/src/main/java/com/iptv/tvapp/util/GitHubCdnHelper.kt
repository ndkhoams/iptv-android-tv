package com.iptv.tvapp.util

object GitHubCdnHelper {

    // Regex bắt các dạng link raw.githubusercontent.com:
    // https://raw.githubusercontent.com/:user/:repo/:branch/:path
    // https://raw.githubusercontent.com/:user/:repo/refs/heads/:branch/:path
    private val RAW_GITHUB_REGEX = Regex(
        """^https?://raw\.githubusercontent\.com/([^/]+)/([^/]+)/(?:refs/heads/)?([^/]+)/(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Regex bắt các dạng link blob/raw trên github.com:
    // https://github.com/:user/:repo/raw/:branch/:path
    // https://github.com/:user/:repo/blob/:branch/:path
    private val GITHUB_BLOB_RAW_REGEX = Regex(
        """^https?://github\.com/([^/]+)/([^/]+)/(?:raw|blob)/(?:refs/heads/)?([^/]+)/(.+)$""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Kiểm tra xem một URL có phải là link nội dung GitHub (raw/blob) cần tăng tốc qua CDN không
     */
    fun isRawGitHubUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("raw.githubusercontent.com") ||
                (lower.contains("github.com") && (lower.contains("/raw/") || lower.contains("/blob/")))
    }

    /**
     * Phân tách thông tin GitHub repository từ URL: (user, repo, branch, path, queryParams)
     */
    private fun extractGitHubDetails(url: String): GitHubDetails? {
        val trimmed = url.trim()
        val queryIndex = trimmed.indexOf('?')
        val baseUrl = if (queryIndex != -1) trimmed.substring(0, queryIndex) else trimmed
        val queryParam = if (queryIndex != -1) trimmed.substring(queryIndex) else ""

        RAW_GITHUB_REGEX.find(baseUrl)?.let { match ->
            return GitHubDetails(
                user = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4].trimStart('/'),
                query = queryParam
            )
        }

        GITHUB_BLOB_RAW_REGEX.find(baseUrl)?.let { match ->
            return GitHubDetails(
                user = match.groupValues[1],
                repo = match.groupValues[2],
                branch = match.groupValues[3],
                path = match.groupValues[4].trimStart('/'),
                query = queryParam
            )
        }

        return null
    }

    /**
     * Sinh danh sách các URL dự phòng đa tầng (Multi-CDN Fallback):
     * 1. Fastly jsDelivr CDN (Ưu tiên số 1 - Tốc độ cao tại VN, không bị chặn)
     * 2. Cloudflare jsDelivr CDN (Ưu tiên số 2)
     * 3. Statically CDN (Ưu tiên số 3)
     * 4. URL gốc (Dự phòng cuối cùng)
     * Nếu không phải link GitHub, trả về danh sách chỉ chứa URL ban đầu.
     */
    fun buildFallbackUrls(url: String): List<String> {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return emptyList()

        val details = extractGitHubDetails(trimmed) ?: return listOf(trimmed)

        val u = details.user
        val r = details.repo
        val b = details.branch
        val p = details.path
        val q = details.query

        return listOf(
            "https://fastly.jsdelivr.net/gh/$u/$r@$b/$p$q",
            "https://cdn.jsdelivr.net/gh/$u/$r@$b/$p$q",
            "https://cdn.statically.io/gh/$u/$r/$b/$p$q",
            trimmed
        )
    }

    /**
     * Lấy URL CDN tối ưu nhất cho 1 tài nguyên (ví dụ ảnh Logo kênh)
     */
    fun getOptimalCdnUrl(url: String): String {
        val trimmed = url.trim()
        val details = extractGitHubDetails(trimmed) ?: return trimmed
        return "https://fastly.jsdelivr.net/gh/${details.user}/${details.repo}@${details.branch}/${details.path}${details.query}"
    }

    private data class GitHubDetails(
        val user: String,
        val repo: String,
        val branch: String,
        val path: String,
        val query: String
    )
}


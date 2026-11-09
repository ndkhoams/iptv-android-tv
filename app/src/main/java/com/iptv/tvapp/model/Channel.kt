package com.iptv.tvapp.model

import com.iptv.tvapp.util.FptLogoHelper
import java.io.Serializable

data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val groupTitle: String = "Mặc định",
    var number: Int = 0,
    var isFavorite: Boolean = false,
    var isDefaultStartup: Boolean = false,
    var resolutionBadge: String? = null,
    var cachedResolvedLogo: String? = null
) : Serializable {

    fun getOrComputeResolution(): String {
        resolutionBadge?.let { return it }
        val upper = name.uppercase()
        val res = when {
            upper.contains("4K") -> "4K"
            upper.contains("720") -> "720p"
            upper.contains("FHD") || upper.contains("1080") || upper.contains("HD") -> "FHD"
            else -> ""
        }
        resolutionBadge = res
        return res
    }

    fun getOrResolveLogo(): String {
        cachedResolvedLogo?.let { return it }
        val resolved = FptLogoHelper.getResolvedLogoUrl(name, logoUrl)
        cachedResolvedLogo = resolved
        return resolved
    }
}

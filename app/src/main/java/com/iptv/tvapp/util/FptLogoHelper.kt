package com.iptv.tvapp.util

import java.util.Locale

object FptLogoHelper {

    // Kho logo chính hãng CDN của FPT Play & IPTV HD
    private val FPT_CDN_BASE = "https://images.fptplay53.net/media/channels/OTT_ECS/2026/07/10/"

    private val CHANNEL_SLUG_MAP = mapOf(
        "vtv1" to "vtv1-hd",
        "vtv2" to "vtv2-hd",
        "vtv3" to "vtv3-hd",
        "vtv4" to "vtv4",
        "vtv5" to "vtv5",
        "vtv7" to "vtv7",
        "vtv8" to "vtv8",
        "vtv9" to "vtv9-hd",
        "htv1" to "htv1",
        "htv2" to "htv2",
        "htv3" to "htv3",
        "htv7" to "htv7-hd",
        "htv9" to "htv9-hd",
        "thvl1" to "thvl1-hd",
        "thvl2" to "thvl2-hd",
        "thvl3" to "thvl3-hd",
        "thvl4" to "thvl4-hd",
        "vtc1" to "vtc1",
        "vtc3" to "vtc3-hd",
        "vtc14" to "vtc14",
        "hanoitv1" to "hanoi-1",
        "hanoitv2" to "hanoi-2",
        "qpvnd" to "qpvn",
        "truyenhinhquocphong" to "qpvn",
        "nhandan" to "nhan-dan-hd",
        "antv" to "antv",
        "kplusaction" to "kplus-action",
        "kpluscine" to "kplus-cine",
        "kplussport1" to "kplus-sport-1",
        "kplussport2" to "kplus-sport-2",
        "fptplay" to "fpt-play",
        "hbo" to "hbo-hd",
        "cinemax" to "cinemax-hd",
        "axn" to "axn-hd",
        "warner" to "warner-tv",
        "cartoonnetwork" to "cartoon-network",
        "discovery" to "discovery-channel",
        "animax" to "animax",
        "paramount" to "paramount-network"
    )

    /**
     * Tự động lấy Logo sắc nét:
     * - Tối ưu CDN Fastly nếu logo gốc từ GitHub
     * - Tự động ánh xạ logo chính hãng FPT Play nếu kênh chưa có logo hoặc logo trống
     */
    fun getResolvedLogoUrl(channelName: String, originalLogoUrl: String?): String {
        if (!originalLogoUrl.isNullOrBlank() && (originalLogoUrl.startsWith("http") || originalLogoUrl.startsWith("android.resource"))) {
            return GitHubCdnHelper.getOptimalCdnUrl(originalLogoUrl)
        }

        val normalized = channelName.lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .replace("hd", "")
            .replace("fhd", "")

        val slug = CHANNEL_SLUG_MAP[normalized] ?: CHANNEL_SLUG_MAP.entries.firstOrNull {
            normalized.contains(it.key)
        }?.value

        return if (slug != null) {
            "$FPT_CDN_BASE$slug.png"
        } else {
            originalLogoUrl ?: ""
        }
    }
}

package com.iptv.tvapp.util

import android.os.Build

object DeviceUtils {

    /**
     * Tự động phát hiện xem ứng dụng có đang chạy trên môi trường giả lập (LDPlayer, BlueStacks, Nox, Genymotion, v.v.) hay không
     */
    val isEmulator: Boolean by lazy {
        val fp = (Build.FINGERPRINT ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val device = (Build.DEVICE ?: "").lowercase()
        val product = (Build.PRODUCT ?: "").lowercase()
        val hardware = (Build.HARDWARE ?: "").lowercase()

        fp.startsWith("generic") ||
        fp.startsWith("unknown") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("android sdk") ||
        model.contains("droid4x") ||
        manufacturer.contains("genymotion") ||
        manufacturer.contains("changxing") || // LDPlayer (Công ty ChangXing)
        manufacturer.contains("ldplayer") ||
        manufacturer.contains("nox") ||
        manufacturer.contains("bluestacks") ||
        brand.startsWith("generic") ||
        device.startsWith("generic") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        hardware.contains("vbox") ||
        hardware.contains("ld_") ||           // LDPlayer hardware ID
        hardware.contains("nox") ||
        product.contains("sdk") ||
        product.contains("google_sdk") ||
        product.contains("emulator") ||
        product.contains("vbox") ||
        product.contains("nox") ||
        product.contains("ld_")
    }
}


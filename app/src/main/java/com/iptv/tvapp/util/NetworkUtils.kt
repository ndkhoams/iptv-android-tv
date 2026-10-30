package com.iptv.tvapp.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Lấy địa chỉ IPv4 cục bộ của Android TV (ví dụ: 192.168.1.15)
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        // Ưu tiên các dải IP mạng LAN thông dụng (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
                        if (hostAddress != null && !hostAddress.startsWith("127.")) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}

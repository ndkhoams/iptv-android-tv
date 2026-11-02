package com.iptv.tvapp.util

import android.util.Log
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trình phân giải DNS an toàn tích hợp dự phòng đa tầng (SafeDns):
 * 1. Phân giải qua DNS hệ thống (System DNS của TV / Router)
 * 2. Phân giải qua Preset Anycast IPs (cho raw.githubusercontent.com và api.github.com khi bị chặn)
 * 3. Phân giải qua DoH (DNS-over-HTTPS Cloudflare 1.1.1.1 & Google 8.8.8.8) trực tiếp qua IP (áp dụng cho MỌI tên miền)
 */
object SafeDns : Dns {
    private const val TAG = "SafeDns"

    // IP Anycast Fastly / GitHub chính thức (Dự phòng tức thì 0ms khi DNS nhà mạng VN bị chặn/lỗi)
    private val PRESET_IPS = mapOf(
        "raw.githubusercontent.com" to listOf(
            "185.199.108.133",
            "185.199.109.133",
            "185.199.110.133",
            "185.199.111.133"
        ),
        "api.github.com" to listOf(
            "20.205.243.168",
            "140.82.114.6",
            "140.82.113.6"
        )
    )

    private val trustAllSslContext: SSLContext? by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, trustAllCerts, SecureRandom())
            ssl
        } catch (e: Exception) {
            null
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // 1. Thử phân giải chuẩn qua System DNS của thiết bị
        try {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isNotEmpty()) {
                return addresses
            }
        } catch (e: UnknownHostException) {
            Log.w(TAG, "System DNS lookup failed for $hostname: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "System DNS unexpected error for $hostname: ${e.message}")
        }

        // 2. Dự phòng: Kiểm tra danh sách IP cố định cho host GitHub
        val preset = PRESET_IPS[hostname.lowercase()]
        if (!preset.isNullOrEmpty()) {
            val resolved = preset.mapNotNull { ip ->
                try {
                    InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
                } catch (e: Exception) {
                    null
                }
            }
            if (resolved.isNotEmpty()) {
                Log.i(TAG, "Resolved $hostname via Preset Anycast IPs: $preset")
                return resolved
            }
        }

        // 3. Dự phòng cho MỌI TÊN MIỀN: Tra cứu qua DoH (Cloudflare 1.1.1.1 & Google 8.8.8.8) trực tiếp qua IP
        try {
            val dohAddresses = resolveViaDoh(hostname)
            if (dohAddresses.isNotEmpty()) {
                Log.i(TAG, "Resolved $hostname via DoH Fallback")
                return dohAddresses
            }
        } catch (e: Exception) {
            Log.w(TAG, "DoH lookup failed for $hostname: ${e.message}")
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\": No address associated with hostname")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://8.8.8.8/resolve?name=$hostname&type=A"
        )
        for (endpoint in endpoints) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL(endpoint)
                conn = url.openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection && trustAllSslContext != null) {
                    conn.sslSocketFactory = trustAllSslContext!!.socketFactory
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("Accept", "application/dns-json")
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    if (json.has("Answer")) {
                        val answerArray = json.getJSONArray("Answer")
                        val ips = mutableListOf<InetAddress>()
                        for (i in 0 until answerArray.length()) {
                            val record = answerArray.getJSONObject(i)
                            val type = record.optInt("type", 1)
                            if (type == 1) { // A record (IPv4)
                                val data = record.optString("data")
                                if (data.isNotBlank()) {
                                    val ipBytes = InetAddress.getByName(data).address
                                    ips.add(InetAddress.getByAddress(hostname, ipBytes))
                                }
                            }
                        }
                        if (ips.isNotEmpty()) return ips
                    }
                }
            } catch (e: Exception) {
                // Tiếp tục thử endpoint DoH tiếp theo
            } finally {
                try {
                    conn?.disconnect()
                } catch (e: Exception) {}
            }
        }
        return emptyList()
    }
}

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

    /**
     * Tối ưu kết nối HTTPS cho thiết bị Android:
     * - Tích hợp SafeDns (vượt chặn DNS, DNS fallback Cloudflare / Google)
     * - Bỏ qua lỗi chứng chỉ Let's Encrypt ISRG Root X1 đã hết hạn trên các phiên bản Android cũ (Lollipop 5.0 / 5.1)
     */
    fun configureTrustAllSsl(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        builder.dns(SafeDns)
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
            builder
        } catch (e: Exception) {
            builder
        }
    }
}

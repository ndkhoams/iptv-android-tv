package com.iptv.tvapp.epg

import android.content.Context
import android.util.Log
import android.util.Xml
import com.iptv.tvapp.model.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class EpgManager(private val context: Context) {

    private val httpClient: OkHttpClient = try {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    } catch (e: Exception) {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val epgMap = ConcurrentHashMap<String, List<EpgProgram>>()
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    var isLoaded = false
        private set

    companion object {
        private const val TAG = "EpgManager"
        const val PRIMARY_EPG_URL = "https://lichphatsong.io.vn/epg.xml"
        const val FALLBACK_EPG_URL = "https://epg.io.vn/epgc.xml"
        private const val CACHE_FILE_NAME = "epg_cache_v2.xml"
        private const val CACHE_VALIDITY_MS = 6 * 60 * 60 * 1000L // 6 giờ
    }

    private fun getCacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    suspend fun loadEpg(url: String = PRIMARY_EPG_URL, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val cacheFile = getCacheFile()
        val now = System.currentTimeMillis()

        val needDownload = forceRefresh || !cacheFile.exists() || (now - cacheFile.lastModified() > CACHE_VALIDITY_MS) || cacheFile.length() < 1024L
        if (needDownload) {
            val success = downloadWithFallback(url, cacheFile)
            if (!success && !cacheFile.exists()) {
                Log.w(TAG, "Tải EPG từ các nguồn đều thất bại.")
            }
        }

        if (cacheFile.exists() && cacheFile.length() > 0L) {
            try {
                parseEpgXml(cacheFile)
                isLoaded = true
                Log.d(TAG, "Nạp thành công EPG: ${epgMap.size} kênh có lịch phát sóng.")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi phân tích file cache EPG: ${e.message}", e)
                cacheFile.delete()
            }
        }
    }

    private fun downloadWithFallback(primaryUrl: String, targetFile: File): Boolean {
        // Thử tải nguồn chính (lichphatsong.io.vn)
        var downloaded = downloadEpg(primaryUrl, targetFile)
        if (downloaded && targetFile.length() > 1024L) return true

        // Nếu lỗi, thử nguồn dự phòng (epg.io.vn)
        if (primaryUrl != FALLBACK_EPG_URL) {
            downloaded = downloadEpg(FALLBACK_EPG_URL, targetFile)
            if (downloaded && targetFile.length() > 1024L) return true
        }
        return false
    }

    private fun downloadEpg(url: String, targetFile: File): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (SmartTV; Android 5.0) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return false

            val body = response.body ?: return false
            val tempFile = File(context.cacheDir, "temp_epg.xml")
            FileOutputStream(tempFile).use { output ->
                body.byteStream().copyTo(output)
            }

            if (tempFile.length() > 1024L) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tải EPG từ $url: ${e.message}")
            false
        }
    }

    private fun parseEpgXml(file: File) {
        val tempMap = mutableMapOf<String, MutableList<EpgProgram>>()
        val now = System.currentTimeMillis()
        val keepStartThreshold = now - 2 * 60 * 60 * 1000L // Giữ lại chương trình từ 2 tiếng trước
        val keepEndThreshold = now + 36 * 60 * 60 * 1000L   // Giữ lại chương trình tới 36 tiếng tới

        FileInputStream(file).use { rawFis ->
            val bis = BufferedInputStream(rawFis)
            bis.mark(4)
            val b1 = bis.read()
            val b2 = bis.read()
            bis.reset()

            // Tự động nhận diện và giải nén nếu file ở dạng GZIP
            val inputStream = if (b1 == 0x1f && b2 == 0x8b) {
                GZIPInputStream(bis)
            } else {
                bis
            }

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var currentChannelId: String? = null
            var currentStart: Long = 0L
            var currentStop: Long = 0L
            var currentTitle: String? = null
            var currentDesc: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tagName) {
                            "programme" -> {
                                currentChannelId = parser.getAttributeValue(null, "channel")?.lowercase(Locale.ROOT)
                                val startStr = parser.getAttributeValue(null, "start")
                                val stopStr = parser.getAttributeValue(null, "stop")
                                currentStart = parseTime(startStr)
                                currentStop = parseTime(stopStr)
                                currentTitle = null
                                currentDesc = null
                            }
                            "title" -> {
                                if (currentChannelId != null) {
                                    currentTitle = parser.nextText()?.trim()
                                }
                            }
                            "desc" -> {
                                if (currentChannelId != null) {
                                    currentDesc = parser.nextText()?.trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName == "programme") {
                            if (!currentChannelId.isNullOrBlank() && !currentTitle.isNullOrBlank()) {
                                if (currentStop >= keepStartThreshold && currentStart <= keepEndThreshold) {
                                    val prog = EpgProgram(
                                        channelId = currentChannelId,
                                        startTime = currentStart,
                                        endTime = currentStop,
                                        title = currentTitle,
                                        desc = currentDesc
                                    )
                                    val list = tempMap.getOrPut(currentChannelId) { mutableListOf() }
                                    list.add(prog)
                                }
                            }
                            currentChannelId = null
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        // Sắp xếp theo thời gian
        epgMap.clear()
        for ((k, list) in tempMap) {
            list.sortBy { it.startTime }
            epgMap[k] = list
        }
    }

    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(timeStr.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Lấy danh sách timeline lịch phát sóng của kênh
     */
    fun getTimelineForChannel(channelId: String?, channelName: String?): List<EpgProgram> {
        val id = channelId?.lowercase(Locale.ROOT)?.trim()
        if (!id.isNullOrBlank()) {
            epgMap[id]?.let { return it }
            // Thử khớp không có 'hd' hoặc thêm 'hd'
            if (id.endsWith("hd")) {
                epgMap[id.removeSuffix("hd")]?.let { return it }
            } else {
                epgMap["${id}hd"]?.let { return it }
            }
        }

        // Nếu không khớp theo tvg-id, tìm khớp theo tên kênh chuẩn hóa
        val norm = normalizeName(channelName ?: "")
        if (norm.isNotBlank()) {
            for ((key, list) in epgMap) {
                val normKey = normalizeName(key)
                if (normKey == norm || normKey.contains(norm) || norm.contains(normKey)) {
                    return list
                }
            }
        }

        return emptyList()
    }

    /**
     * Lấy chương trình đang phát hiện tại
     */
    fun getCurrentProgram(channelId: String?, channelName: String?): EpgProgram? {
        val timeline = getTimelineForChannel(channelId, channelName)
        val now = System.currentTimeMillis()
        return timeline.firstOrNull { it.isCurrent(now) }
            ?: timeline.firstOrNull { it.startTime >= now }
    }

    private fun normalizeName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
            .replace("(", "")
            .replace(")", "")
            .replace("hd", "")
            .replace("fhd", "")
            .replace("8.5mbps", "")
            .replace("8mbps", "")
            .replace("4mbps", "")
            .trim()
    }
}

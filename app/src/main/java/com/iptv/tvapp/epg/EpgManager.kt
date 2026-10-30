package com.iptv.tvapp.epg

import android.content.Context
import android.util.Xml
import com.iptv.tvapp.model.EpgProgram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class EpgManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val epgMap = ConcurrentHashMap<String, List<EpgProgram>>()
    private val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

    var isLoaded = false
        private set

    companion object {
        const val DEFAULT_EPG_URL = "https://epg.io.vn/epgc.xml"
        private const val CACHE_FILE_NAME = "epgc_cache.xml"
        private const val CACHE_VALIDITY_MS = 8 * 60 * 60 * 1000L // 8 giờ
    }

    private fun getCacheFile(): File = File(context.cacheDir, CACHE_FILE_NAME)

    suspend fun loadEpg(url: String = DEFAULT_EPG_URL, forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val cacheFile = getCacheFile()
            val now = System.currentTimeMillis()

            val needDownload = forceRefresh || !cacheFile.exists() || (now - cacheFile.lastModified() > CACHE_VALIDITY_MS) || cacheFile.length() == 0L
            if (needDownload) {
                downloadEpg(url, cacheFile)
            }

            if (cacheFile.exists() && cacheFile.length() > 0L) {
                parseEpgXml(cacheFile)
                isLoaded = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Nếu lỗi tải mới nhưng có cache cũ, cố gắng parse cache
            val cacheFile = getCacheFile()
            if (cacheFile.exists() && cacheFile.length() > 0L && epgMap.isEmpty()) {
                try {
                    parseEpgXml(cacheFile)
                    isLoaded = true
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun downloadEpg(url: String, targetFile: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (SmartTV; Android 5.0) AppleWebKit/537.36")
            .header("Accept-Encoding", "gzip")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) return

        val body = response.body ?: return
        val tempFile = File(context.cacheDir, "temp_epg.xml")
        FileOutputStream(tempFile).use { output ->
            body.byteStream().copyTo(output)
        }

        if (tempFile.length() > 0L) {
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
        }
    }

    private fun parseEpgXml(file: File) {
        val tempMap = mutableMapOf<String, MutableList<EpgProgram>>()
        val now = System.currentTimeMillis()
        val keepStartThreshold = now - 2 * 60 * 60 * 1000L // Giữ lại chương trình cách đây 2 tiếng
        val keepEndThreshold = now + 36 * 60 * 60 * 1000L   // Giữ lại chương trình tới 36 tiếng tới

        FileInputStream(file).use { fis ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(fis, "UTF-8")

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
                                // Chỉ giữ lại các chương trình trong khung thời gian hợp lệ
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
            val list = epgMap[id]
            if (!list.isNullOrEmpty()) return list
        }

        // Nếu không khớp theo tvg-id, tìm khớp theo tên kênh chuẩn hóa
        val norm = normalizeName(channelName ?: "")
        if (norm.isNotBlank()) {
            for ((key, list) in epgMap) {
                if (key.contains(norm) || norm.contains(key)) {
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
            .replace("8mbps", "")
            .replace("4mbps", "")
            .trim()
    }
}

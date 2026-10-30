package com.iptv.tvapp.data

import android.content.Context
import com.iptv.tvapp.model.Category
import com.iptv.tvapp.model.Channel
import com.iptv.tvapp.parser.M3UParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class PlaylistManager(private val context: Context) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cacheFile: File
        get() = File(context.cacheDir, "playlist_cache.m3u")

    /**
     * Tải danh sách kênh từ URL hoặc file cache
     */
    suspend fun loadPlaylist(url: String, forceRefresh: Boolean = false): List<Category> =
        withContext(Dispatchers.IO) {
            val channels = mutableListOf<Channel>()

            try {
                if (forceRefresh || !cacheFile.exists() || cacheFile.length() == 0L) {
                    downloadAndCache(url)
                }

                if (cacheFile.exists() && cacheFile.length() > 0L) {
                    val stream = FileInputStream(cacheFile)
                    channels.addAll(M3UParser.parse(stream))
                }
            } catch (e: Exception) {
                // Nếu tải lỗi mà có cache cũ, đọc từ cache
                if (cacheFile.exists() && cacheFile.length() > 0L && channels.isEmpty()) {
                    val stream = FileInputStream(cacheFile)
                    channels.addAll(M3UParser.parse(stream))
                } else {
                    throw e
                }
            }

            // Gán lại số thứ tự kênh cho chuẩn (1..N)
            channels.forEachIndexed { index, channel ->
                channel.number = index + 1
            }

            // Gom nhóm kênh theo Category
            return@withContext groupChannels(channels)
        }

    private fun downloadAndCache(url: String) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (SmartTV; Android 5.0) AppleWebKit/537.36 (KHTML, like Gecko) ExoPlayer")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP Error: ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        FileOutputStream(cacheFile).use { output ->
            body.byteStream().copyTo(output)
        }
    }

    private fun groupChannels(channels: List<Channel>): List<Category> {
        val result = mutableListOf<Category>()

        if (channels.isEmpty()) return result

        // Nhóm "Tất cả" chứa toàn bộ kênh
        val allCategory = Category("Tất cả", channels.toMutableList())
        result.add(allCategory)

        // Gom theo nhóm trong M3U (group-title)
        val groupedMap = channels.groupBy { it.groupTitle }
        for ((groupName, groupChannels) in groupedMap) {
            if (groupName.isNotBlank() && groupName != "Tất cả") {
                result.add(Category(groupName, groupChannels.toMutableList()))
            }
        }

        return result
    }
}

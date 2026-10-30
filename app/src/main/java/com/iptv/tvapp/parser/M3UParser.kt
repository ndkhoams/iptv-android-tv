package com.iptv.tvapp.parser

import com.iptv.tvapp.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

object M3UParser {

    private val EXTINF_TAG = "#EXTINF:"
    private val REGEX_TVG_ID = Pattern.compile("(?i)tvg-id=\"([^\"]*)\"")
    private val REGEX_TVG_NAME = Pattern.compile("(?i)tvg-name=\"([^\"]*)\"")
    private val REGEX_TVG_LOGO = Pattern.compile("(?i)tvg-logo=\"([^\"]*)\"")
    private val REGEX_GROUP_TITLE = Pattern.compile("(?i)group-title=\"([^\"]*)\"")

    /**
     * Phân tích luồng M3U / M3U8 thành danh sách Channel
     */
    fun parse(inputStream: InputStream): List<Channel> {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

        var currentId = ""
        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = "Tất cả"
        var channelIndex = 1

        var line: String? = reader.readLine()
        while (line != null) {
            val trimmedLine = line.trim()

            if (trimmedLine.startsWith(EXTINF_TAG)) {
                // Đọc thông tin #EXTINF
                currentId = extractAttribute(trimmedLine, REGEX_TVG_ID) ?: "ch_$channelIndex"
                val tvgName = extractAttribute(trimmedLine, REGEX_TVG_NAME)
                currentLogo = extractAttribute(trimmedLine, REGEX_TVG_LOGO)?.takeIf { it.isNotBlank() }
                currentGroup = extractAttribute(trimmedLine, REGEX_GROUP_TITLE)?.takeIf { it.isNotBlank() } ?: "Khác"

                // Lấy tên kênh sau dấu phẩy cuối cùng
                val commaIndex = trimmedLine.lastIndexOf(',')
                currentName = if (commaIndex != -1 && commaIndex < trimmedLine.length - 1) {
                    trimmedLine.substring(commaIndex + 1).trim()
                } else {
                    tvgName ?: "Kênh $channelIndex"
                }

                if (currentName.isBlank() && !tvgName.isNullOrBlank()) {
                    currentName = tvgName
                }
            } else if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                // Dòng này là link stream (http / https / rtsp / ...)
                val streamUrl = trimmedLine

                if (currentName.isBlank()) {
                    currentName = "Kênh $channelIndex"
                }

                channels.add(
                    Channel(
                        id = currentId.ifBlank { "ch_$channelIndex" },
                        name = currentName,
                        logoUrl = currentLogo,
                        streamUrl = streamUrl,
                        groupTitle = currentGroup,
                        number = channelIndex
                    )
                )

                // Reset tạm thời cho kênh tiếp theo
                channelIndex++
                currentId = ""
                currentName = ""
                currentLogo = null
                currentGroup = "Khác"
            }

            line = reader.readLine()
        }

        reader.close()
        return channels
    }

    private fun extractAttribute(line: String, pattern: Pattern): String? {
        val matcher = pattern.matcher(line)
        return if (matcher.find()) {
            matcher.group(1)?.trim()
        } else {
            null
        }
    }
}

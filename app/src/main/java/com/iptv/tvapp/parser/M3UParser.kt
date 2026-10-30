package com.iptv.tvapp.parser

import com.iptv.tvapp.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.util.regex.Pattern

object M3UParser {

    private val REGEX_TVG_ID = Pattern.compile("(?i)tvg-id=\"([^\"]*)\"")
    private val REGEX_TVG_NAME = Pattern.compile("(?i)tvg-name=\"([^\"]*)\"")
    private val REGEX_TVG_LOGO = Pattern.compile("(?i)tvg-logo=\"([^\"]*)\"")
    private val REGEX_GROUP_TITLE = Pattern.compile("(?i)group-title=\"([^\"]*)\"")

    /**
     * Phân tích luồng M3U / M3U8 thành danh sách Channel
     * Hỗ trợ UTF-8 BOM, case-insensitive #EXTINF, #EXTGRP, link tương đối và danh sách định dạng text
     */
    fun parse(inputStream: InputStream, baseUrl: String? = null): List<Channel> {
        val channels = mutableListOf<Channel>()
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

        var currentId = ""
        var currentName = ""
        var currentLogo: String? = null
        var currentGroup = "Tất cả"
        var channelIndex = 1
        var isFirstLine = true
        var isHtmlContent = false

        var line: String? = reader.readLine()
        while (line != null) {
            var trimmedLine = line.trim()

            // Loại bỏ ký tự UTF-8 BOM nếu có ở đầu dòng đầu tiên
            if (isFirstLine) {
                if (trimmedLine.startsWith("\uFEFF")) {
                    trimmedLine = trimmedLine.substring(1).trim()
                }
                val lowerFirst = trimmedLine.lowercase()
                if (lowerFirst.startsWith("<!doctype html") || lowerFirst.startsWith("<html")) {
                    isHtmlContent = true
                }
                isFirstLine = false
            }

            if (trimmedLine.startsWith("#EXTINF", ignoreCase = true)) {
                // Đọc thông tin #EXTINF (case-insensitive)
                currentId = extractAttribute(trimmedLine, REGEX_TVG_ID) ?: "ch_$channelIndex"
                val tvgName = extractAttribute(trimmedLine, REGEX_TVG_NAME)
                currentLogo = extractAttribute(trimmedLine, REGEX_TVG_LOGO)?.takeIf { it.isNotBlank() }
                val parsedGroup = extractAttribute(trimmedLine, REGEX_GROUP_TITLE)?.takeIf { it.isNotBlank() }
                if (parsedGroup != null) {
                    currentGroup = parsedGroup
                }

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
            } else if (trimmedLine.startsWith("#EXTGRP:", ignoreCase = true)) {
                // Hỗ trợ thẻ nhóm riêng biệt #EXTGRP:
                val groupVal = trimmedLine.substringAfter(':').trim()
                if (groupVal.isNotBlank()) {
                    currentGroup = groupVal
                }
            } else if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                // Dòng này chứa link stream (http / https / rtsp / udp / rtp / ...)
                var streamUrl = trimmedLine

                // Giải quyết trường hợp dòng chứa dạng: "Tên Kênh,http://..."
                if (!streamUrl.startsWith("http://", ignoreCase = true) &&
                    !streamUrl.startsWith("https://", ignoreCase = true) &&
                    !streamUrl.startsWith("rtsp://", ignoreCase = true) &&
                    !streamUrl.startsWith("udp://", ignoreCase = true) &&
                    !streamUrl.startsWith("rtp://", ignoreCase = true)
                ) {
                    val splitIdx = streamUrl.indexOfAny(charArrayOf(',', '|', '#'))
                    if (splitIdx != -1) {
                        val potentialName = streamUrl.substring(0, splitIdx).trim()
                        val potentialUrl = streamUrl.substring(splitIdx + 1).trim()
                        if (potentialUrl.contains("://")) {
                            if (currentName.isBlank() || currentName.startsWith("Kênh ")) {
                                currentName = potentialName
                            }
                            streamUrl = potentialUrl
                        }
                    }
                }

                // Xử lý link tương đối (relative URL) nếu có baseUrl
                if (baseUrl != null && !streamUrl.contains("://") && !streamUrl.startsWith("udp://", ignoreCase = true)) {
                    try {
                        val baseUri = URI(baseUrl)
                        streamUrl = baseUri.resolve(streamUrl).toString()
                    } catch (e: Exception) {
                        // Giữ nguyên streamUrl nếu lỗi resolve
                    }
                }

                // Làm sạch URL
                streamUrl = streamUrl.trim().removeSurrounding("\"").removeSurrounding("'")

                if (currentName.isBlank()) {
                    currentName = "Kênh $channelIndex"
                }

                if (streamUrl.isNotBlank()) {
                    channels.add(
                        Channel(
                            id = currentId.ifBlank { "ch_$channelIndex" },
                            name = currentName,
                            logoUrl = currentLogo,
                            streamUrl = streamUrl,
                            groupTitle = currentGroup.ifBlank { "Khác" },
                            number = channelIndex
                        )
                    )

                    // Chuẩn bị cho kênh tiếp theo
                    channelIndex++
                    currentId = ""
                    currentName = ""
                    currentLogo = null
                    currentGroup = "Khác"
                }
            }

            line = reader.readLine()
        }

        reader.close()

        if (channels.isEmpty() && isHtmlContent) {
            throw IllegalArgumentException("Nguồn M3U trả về trang web HTML, không phải file danh sách M3U hợp lệ.")
        }

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

package com.iptv.tvapp.data

import android.content.Context
import android.content.SharedPreferences
import com.iptv.tvapp.model.PlaylistSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===== QUẢN LÝ NHIỀU DANH SÁCH M3U =====
    fun getPlaylists(): MutableList<PlaylistSource> {
        val jsonString = prefs.getString(KEY_PLAYLISTS, null)
        val list = mutableListOf<PlaylistSource>()

        if (jsonString.isNullOrBlank()) {
            // Danh sách mặc định ban đầu
            val defaultSource = PlaylistSource(
                id = UUID.randomUUID().toString(),
                name = "FPT IPTV (udpxy)",
                url = DEFAULT_URL,
                isSelected = true
            )
            list.add(defaultSource)
            savePlaylists(list)
            return list
        }

        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PlaylistSource(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        isSelected = obj.optBoolean("isSelected", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Tự động nâng cấp danh sách mặc định cũ sang link FPT udpxy
        var updatedDefault = false
        for (i in 0 until list.size) {
            val item = list[i]
            if (item.url.contains("iptv-org/iptv/master/streams/vn.m3u")) {
                list[i] = item.copy(
                    name = "FPT IPTV (udpxy)",
                    url = DEFAULT_URL
                )
                updatedDefault = true
            }
        }
        if (updatedDefault) {
            savePlaylists(list)
        }

        // Đảm bảo luôn có ít nhất 1 danh sách được chọn
        if (list.isNotEmpty() && list.none { it.isSelected }) {
            list[0].isSelected = true
        }

        return list
    }

    fun savePlaylists(playlists: List<PlaylistSource>) {
        val jsonArray = JSONArray()
        for (item in playlists) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("url", item.url)
            obj.put("isSelected", item.isSelected)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLISTS, jsonArray.toString()).apply()
    }

    fun getActivePlaylist(): PlaylistSource {
        val list = getPlaylists()
        return list.firstOrNull { it.isSelected } ?: list.first()
    }

    fun setActivePlaylist(playlistId: String) {
        val list = getPlaylists()
        list.forEach { it.isSelected = (it.id == playlistId) }
        savePlaylists(list)
    }

    // ===== KÊNH ĐANG PHÁT / VỪA XEM =====
    var lastChannelUrl: String?
        get() = prefs.getString(KEY_LAST_CHANNEL_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL_URL, value).apply()

    // ===== DANH SÁCH KÊNH YÊU THÍCH (LƯU BẰNG STREAM URL) =====
    fun getFavoriteUrls(): MutableSet<String> {
        return prefs.getStringSet(KEY_FAVORITES, HashSet())?.toMutableSet() ?: mutableSetOf()
    }

    fun isFavorite(streamUrl: String): Boolean {
        return getFavoriteUrls().contains(streamUrl)
    }

    fun toggleFavorite(streamUrl: String): Boolean {
        val favs = getFavoriteUrls()
        val isFavNow = if (favs.contains(streamUrl)) {
            favs.remove(streamUrl)
            false
        } else {
            favs.add(streamUrl)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
        return isFavNow
    }

    // ===== CÀI ĐẶT KHỞI ĐỘNG CÙNG ANDROID TV =====
    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START_BOOT, value).apply()

    // Chế độ khởi động: "LAST_WATCHED" hoặc "FIXED_CHANNEL"
    var startupMode: String
        get() = prefs.getString(KEY_STARTUP_MODE, MODE_LAST_WATCHED) ?: MODE_LAST_WATCHED
        set(value) = prefs.edit().putString(KEY_STARTUP_MODE, value).apply()

    var defaultStartupChannelUrl: String?
        get() = prefs.getString(KEY_DEFAULT_STARTUP_URL, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_STARTUP_URL, value).apply()

    var defaultStartupChannelName: String?
        get() = prefs.getString(KEY_DEFAULT_STARTUP_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_STARTUP_NAME, value).apply()

    // Trình phát video: VLC (Khử răng cưa 1080i) hoặc EXOPLAYER
    var playerEngine: String
        get() = prefs.getString(KEY_PLAYER_ENGINE, ENGINE_VLC) ?: ENGINE_VLC
        set(value) = prefs.edit().putString(KEY_PLAYER_ENGINE, value).apply()

    companion object {
        private const val PREFS_NAME = "iptv_tv_prefs"
        private const val KEY_PLAYLISTS = "key_playlists_v2"
        private const val KEY_LAST_CHANNEL_URL = "key_last_channel_url"
        private const val KEY_FAVORITES = "key_favorite_channels"
        private const val KEY_AUTO_START_BOOT = "key_auto_start_boot"
        private const val KEY_STARTUP_MODE = "key_startup_mode"
        private const val KEY_DEFAULT_STARTUP_URL = "key_default_startup_url"
        private const val KEY_DEFAULT_STARTUP_NAME = "key_default_startup_name"
        private const val KEY_PLAYER_ENGINE = "key_player_engine"

        const val ENGINE_VLC = "VLC"
        const val ENGINE_EXOPLAYER = "EXOPLAYER"

        const val MODE_LAST_WATCHED = "LAST_WATCHED"
        const val MODE_FIXED_CHANNEL = "FIXED_CHANNEL"

        const val DEFAULT_URL = "https://121124.xyz/tv/fpt?udpxy=10.0.0.222:4022"
    }
}

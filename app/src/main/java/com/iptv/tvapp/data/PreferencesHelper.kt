package com.iptv.tvapp.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.iptv.tvapp.model.PlaylistSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PreferencesHelper(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===== QUẢN LÝ NHIỀU DANH SÁCH M3U =====
    fun getPlaylists(): MutableList<PlaylistSource> {
        val jsonString = prefs.getString(KEY_PLAYLISTS, null)
        val list = mutableListOf<PlaylistSource>()

        if (jsonString.isNullOrBlank()) {
            // Thử tự động khôi phục từ file backup nếu có trước khi tạo mới
            if (restoreAutoBackupIfAvailable()) {
                val restored = prefs.getString(KEY_PLAYLISTS, null)
                if (!restored.isNullOrBlank()) {
                    return getPlaylists()
                }
            }

            // Danh sách mặc định ban đầu
            val defaultSource = PlaylistSource(
                id = UUID.randomUUID().toString(),
                name = "FPT IPTV",
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

        // Tự động nâng cấp danh sách mặc định cũ sang link FPT gốc (đã có udpxyServer cấu hình riêng)
        var updatedDefault = false
        for (i in 0 until list.size) {
            val item = list[i]
            if (item.url.contains("iptv-org/iptv/master/streams/vn.m3u") || item.url == "https://121124.xyz/tv/fpt?udpxy=10.0.0.222:4022") {
                list[i] = item.copy(
                    name = "FPT IPTV",
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
        saveAutoBackup()
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
        saveAutoBackup()
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

    // ===== CÀI ĐẶT MÁY CHỦ UDPXY (SERVER:PORT) =====
    var udpxyServer: String
        get() = prefs.getString(KEY_UDPXY_SERVER, "10.0.0.222:4022") ?: "10.0.0.222:4022"
        set(value) = prefs.edit().putString(KEY_UDPXY_SERVER, value.trim()).apply()

    // ===== SAO LƯU & KHÔI PHỤC TOÀN BỘ CẤU HÌNH (BACKUP / RESTORE) =====
    fun createBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("appName", "TV Play IPTV")
        root.put("backupTime", System.currentTimeMillis())

        // 1. Playlists
        val playlistsArray = JSONArray()
        for (pl in getPlaylists()) {
            val o = JSONObject()
            o.put("id", pl.id)
            o.put("name", pl.name)
            o.put("url", pl.url)
            o.put("isSelected", pl.isSelected)
            playlistsArray.put(o)
        }
        root.put("playlists", playlistsArray)

        // 2. Favorites
        val favArray = JSONArray()
        for (fav in getFavoriteUrls()) {
            favArray.put(fav)
        }
        root.put("favorites", favArray)

        // 3. Settings
        root.put("udpxyServer", udpxyServer)
        root.put("playerEngine", playerEngine)
        root.put("autoStartOnBoot", autoStartOnBoot)
        root.put("startupMode", startupMode)
        root.put("defaultStartupChannelUrl", defaultStartupChannelUrl ?: "")
        root.put("defaultStartupChannelName", defaultStartupChannelName ?: "")
        root.put("lastChannelUrl", lastChannelUrl ?: "")

        return root.toString(2)
    }

    fun restoreFromBackupJson(jsonString: String): Boolean {
        try {
            val root = JSONObject(jsonString)

            // 1. Playlists
            if (root.has("playlists")) {
                val arr = root.getJSONArray("playlists")
                val list = mutableListOf<PlaylistSource>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        PlaylistSource(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            name = o.getString("name"),
                            url = o.getString("url"),
                            isSelected = o.optBoolean("isSelected", false)
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    if (list.none { it.isSelected }) list[0].isSelected = true
                    savePlaylists(list)
                }
            }

            // 2. Favorites
            if (root.has("favorites")) {
                val arr = root.getJSONArray("favorites")
                val favs = mutableSetOf<String>()
                for (i in 0 until arr.length()) {
                    favs.add(arr.getString(i))
                }
                prefs.edit().putStringSet(KEY_FAVORITES, favs).apply()
            }

            // 3. Settings
            if (root.has("udpxyServer")) udpxyServer = root.getString("udpxyServer")
            if (root.has("playerEngine")) playerEngine = root.getString("playerEngine")
            if (root.has("autoStartOnBoot")) autoStartOnBoot = root.getBoolean("autoStartOnBoot")
            if (root.has("startupMode")) startupMode = root.getString("startupMode")
            if (root.has("defaultStartupChannelUrl")) defaultStartupChannelUrl = root.getString("defaultStartupChannelUrl").takeIf { it.isNotBlank() }
            if (root.has("defaultStartupChannelName")) defaultStartupChannelName = root.getString("defaultStartupChannelName").takeIf { it.isNotBlank() }
            if (root.has("lastChannelUrl")) lastChannelUrl = root.getString("lastChannelUrl").takeIf { it.isNotBlank() }

            saveAutoBackup()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun saveAutoBackup(): File? {
        return try {
            val backupJson = createBackupJson()
            // 1. Lưu bản sao nội bộ trong app
            val internalFile = File(appContext.filesDir, "tvplay_backup_auto.json")
            internalFile.writeText(backupJson, Charsets.UTF_8)

            // 2. Lưu bản sao vào thư mục Download công cộng nếu có quyền
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
                val extFile = File(downloadDir, "tvplay_backup.json")
                extFile.writeText(backupJson, Charsets.UTF_8)
                extFile
            } else {
                internalFile
            }
        } catch (e: Exception) {
            null
        }
    }

    fun restoreAutoBackupIfAvailable(): Boolean {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val extFile = File(downloadDir, "tvplay_backup.json")
        val internalFile = File(appContext.filesDir, "tvplay_backup_auto.json")

        val targetFile = when {
            extFile.exists() && extFile.length() > 0 -> extFile
            internalFile.exists() && internalFile.length() > 0 -> internalFile
            else -> null
        }

        return if (targetFile != null) {
            try {
                restoreFromBackupJson(targetFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }
    }

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
        private const val KEY_UDPXY_SERVER = "key_udpxy_server"

        const val ENGINE_VLC = "VLC"
        const val ENGINE_EXOPLAYER = "EXOPLAYER"

        const val MODE_LAST_WATCHED = "LAST_WATCHED"
        const val MODE_FIXED_CHANNEL = "FIXED_CHANNEL"

        const val DEFAULT_URL = "https://121124.xyz/tv/fpt"
    }
}

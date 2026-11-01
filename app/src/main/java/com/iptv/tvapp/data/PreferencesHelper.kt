package com.iptv.tvapp.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

            // Danh sách mặc định ban đầu: Duy nhất KTV Playlist
            val defaultSource = PlaylistSource(
                id = UUID.randomUUID().toString(),
                name = "KTV Playlist",
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

        // Tự động làm sạch danh sách: Chỉ loại bỏ TV Play Pro / Builtin cũ nếu còn sót lại
        val cleanedList = mutableListOf<PlaylistSource>()
        for (item in list) {
            if (item.url == BUILTIN_PLAYLIST_URL || item.id == BUILTIN_PLAYLIST_ID || item.name.contains("TV Play Pro")) {
                continue // Bỏ qua TV Play Pro cũ
            }
            cleanedList.add(item)
        }

        // Nếu danh sách rỗng (do lỗi hoặc xoá hết), tạo lại 1 danh sách mặc định để app luôn chạy được
        if (cleanedList.isEmpty()) {
            cleanedList.add(
                PlaylistSource(
                    id = UUID.randomUUID().toString(),
                    name = "KTV Playlist",
                    url = DEFAULT_URL,
                    isSelected = true
                )
            )
        }

        // Đảm bảo luôn có ít nhất 1 danh sách được chọn (nếu chưa có thì chọn danh sách đầu tiên)
        if (cleanedList.none { it.isSelected }) {
            cleanedList[0].isSelected = true
        }

        if (cleanedList != list) {
            savePlaylists(cleanedList)
        }

        return cleanedList
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

    // Chế độ khởi động: "FIRST_CHANNEL" (mặc định), "LAST_WATCHED" hoặc "FIXED_CHANNEL"
    var startupMode: String
        get() = prefs.getString(KEY_STARTUP_MODE, MODE_FIRST_CHANNEL) ?: MODE_FIRST_CHANNEL
        set(value) = prefs.edit().putString(KEY_STARTUP_MODE, value).apply()

    fun ensureKtvActiveOnStartup() {
        // Không ép buộc chọn KTV Playlist nữa - tôn trọng lựa chọn playlist của người dùng
    }

    var defaultStartupChannelUrl: String?
        get() = prefs.getString(KEY_DEFAULT_STARTUP_URL, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_STARTUP_URL, value).apply()

    var defaultStartupChannelName: String?
        get() = prefs.getString(KEY_DEFAULT_STARTUP_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEFAULT_STARTUP_NAME, value).apply()

    // Trình phát video: VLC hoặc EXOPLAYER (Mặc định VLC tối ưu phần cứng cho Android TV)
    var playerEngine: String
        get() {
            val v = prefs.getString(KEY_PLAYER_ENGINE, ENGINE_VLC) ?: ENGINE_VLC
            return if (v == ENGINE_EXOPLAYER) ENGINE_EXOPLAYER else ENGINE_VLC
        }
        set(value) = prefs.edit().putString(KEY_PLAYER_ENGINE, value).apply()

    // Thuật toán khử xé hình / deinterlace cho luồng 1080i (Mặc định: yadif - sắc nét như PC)
    var deinterlaceMode: String
        get() = prefs.getString(KEY_DEINTERLACE_MODE, DEINTERLACE_YADIF) ?: DEINTERLACE_YADIF
        set(value) = prefs.edit().putString(KEY_DEINTERLACE_MODE, value).apply()

    // HTTP User Agent tùy chỉnh (Mặc định: Phổ thông chuẩn Android / Chrome)
    var httpUserAgent: String
        get() = prefs.getString(KEY_HTTP_USER_AGENT, DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        set(value) = prefs.edit().putString(KEY_HTTP_USER_AGENT, value.trim().ifBlank { DEFAULT_USER_AGENT }).apply()

    // ===== CÀI ĐẶT MÁY CHỦ UDPXY (SERVER:PORT) =====
    var udpxyServer: String
        get() = prefs.getString(KEY_UDPXY_SERVER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_UDPXY_SERVER, value.trim()).apply()

    // ===== ĐƯỜNG DẪN FILE THỂ THAO TRỰC TIẾP (THETHAO.M3U TỪ GITHUB / ARMBIAN) =====
    var sportsPlaylistUrl: String
        get() = prefs.getString(KEY_SPORTS_PLAYLIST_URL, SportsLiveManager.DEFAULT_SPORTS_URL) ?: SportsLiveManager.DEFAULT_SPORTS_URL
        set(value) = prefs.edit().putString(KEY_SPORTS_PLAYLIST_URL, value.trim().ifBlank { SportsLiveManager.DEFAULT_SPORTS_URL }).apply()

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
        root.put("deinterlaceMode", deinterlaceMode)
        root.put("httpUserAgent", httpUserAgent)
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
            if (root.has("deinterlaceMode")) deinterlaceMode = root.getString("deinterlaceMode")
            if (root.has("httpUserAgent")) httpUserAgent = root.getString("httpUserAgent")
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
        val backupJson = try {
            createBackupJson()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        // 1. Luôn lưu bản sao nội bộ an toàn (100% thành công, không cần quyền)
        val internalFile = File(appContext.filesDir, "tvplay_backup_auto.json")
        try {
            internalFile.writeText(backupJson, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Lưu bản sao vào thư mục ngoài của ứng dụng (External Files Dir - không cần quyền trên mọi Android)
        var appExtFile: File? = null
        try {
            val appExtDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.getExternalFilesDir(null)
            if (appExtDir != null && (appExtDir.exists() || appExtDir.mkdirs())) {
                val f = File(appExtDir, "tvplay_backup.json")
                f.writeText(backupJson, Charsets.UTF_8)
                if (f.exists() && f.length() > 0) {
                    appExtFile = f
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Thử lưu vào thư mục Download công cộng (/sdcard/Download/tvplay_backup.json)
        val publicFile = writeToPublicDownload(backupJson)

        // Trả về file có mức truy cập thuận tiện nhất cho người dùng
        return publicFile?.takeIf { it.exists() && it.length() > 0 }
            ?: appExtFile?.takeIf { it.exists() && it.length() > 0 }
            ?: internalFile.takeIf { it.exists() && it.length() > 0 }
    }

    private fun writeToPublicDownload(backupJson: String): File? {
        // A. Thử ghi trực tiếp bằng File API trước (nhanh và tương thích rộng rãi trên Android TV / ROM tuỳ biến)
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
                val extFile = File(downloadDir, "tvplay_backup.json")
                extFile.writeText(backupJson, Charsets.UTF_8)
                if (extFile.exists() && extFile.length() > 0) {
                    return extFile
                }
            }
        } catch (e: Exception) {
            // EACCES trên Android 10+ với Scoped Storage - chuyển sang MediaStore bên dưới
        }

        // B. Nếu Android >= 29 (Android 10+), thử ghi qua MediaStore Downloads (hoạt động không cần quyền đặc biệt)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = appContext.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

                // Tìm file cũ nếu có để ghi đè
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf("tvplay_backup.json")

                var existingUri: Uri? = null
                try {
                    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                            existingUri = ContentUris.withAppendedId(collection, id)
                        }
                    }
                } catch (ignored: Exception) {}

                var written = false
                if (existingUri != null) {
                    try {
                        resolver.openOutputStream(existingUri!!, "rwt")?.use { os ->
                            os.write(backupJson.toByteArray(Charsets.UTF_8))
                            os.flush()
                            written = true
                        }
                    } catch (e: Exception) {
                        try {
                            resolver.delete(existingUri!!, null, null)
                        } catch (ignored: Exception) {}
                    }
                }

                if (!written) {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "tvplay_backup.json")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val newUri = resolver.insert(collection, contentValues)
                    if (newUri != null) {
                        resolver.openOutputStream(newUri)?.use { os ->
                            os.write(backupJson.toByteArray(Charsets.UTF_8))
                            os.flush()
                        }
                    }
                }

                val publicFile = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "tvplay_backup.json")
                if (publicFile.exists() && publicFile.length() > 0) {
                    return publicFile
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }

    fun getAvailableBackupFile(): File? {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val extFile = if (downloadDir != null) File(downloadDir, "tvplay_backup.json") else null
        val appExtFile = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "tvplay_backup.json") }
        val internalFile = File(appContext.filesDir, "tvplay_backup_auto.json")

        val candidates = listOfNotNull(extFile, appExtFile, internalFile)
        return candidates.firstOrNull { it.exists() && it.canRead() && it.length() > 0 }
    }

    fun restoreAutoBackupIfAvailable(): Boolean {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val extFile = if (downloadDir != null) File(downloadDir, "tvplay_backup.json") else null
        val appExtFile = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "tvplay_backup.json") }
        val internalFile = File(appContext.filesDir, "tvplay_backup_auto.json")

        val candidates = listOfNotNull(extFile, appExtFile, internalFile)
        for (file in candidates) {
            try {
                if (file.exists() && file.canRead() && file.length() > 0) {
                    val content = file.readText(Charsets.UTF_8)
                    if (restoreFromBackupJson(content)) {
                        return true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
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
        private const val KEY_DEINTERLACE_MODE = "key_deinterlace_mode"
        private const val KEY_HTTP_USER_AGENT = "key_http_user_agent"
        private const val KEY_UDPXY_SERVER = "key_udpxy_server"
        private const val KEY_SPORTS_PLAYLIST_URL = "key_sports_playlist_url"

        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        const val ENGINE_VLC = "VLC"
        const val ENGINE_VLC_SW = "VLC_SW"
        const val ENGINE_EXOPLAYER = "EXOPLAYER"

        const val DEINTERLACE_YADIF = "yadif"
        const val DEINTERLACE_LINEAR = "linear"
        const val DEINTERLACE_BLEND = "blend"
        const val DEINTERLACE_OFF = "off"

        const val MODE_FIRST_CHANNEL = "FIRST_CHANNEL"
        const val MODE_LAST_WATCHED = "LAST_WATCHED"
        const val MODE_FIXED_CHANNEL = "FIXED_CHANNEL"

        const val DEFAULT_URL = "https://raw.githubusercontent.com/ndkhoams/iptv/refs/heads/main/ktv.m3u"
        const val BUILTIN_PLAYLIST_ID = "builtin_pro_playlist"
        const val BUILTIN_PLAYLIST_URL = "asset://builtin_playlist.m3u"
    }
}

package com.iptv.tvapp.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = prefs.getString(KEY_PLAYLIST_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_PLAYLIST_URL, value).apply()

    var lastChannelUrl: String?
        get() = prefs.getString(KEY_LAST_CHANNEL_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_CHANNEL_URL, value).apply()

    companion object {
        private const val PREFS_NAME = "iptv_tv_prefs"
        private const val KEY_PLAYLIST_URL = "key_playlist_url"
        private const val KEY_LAST_CHANNEL_URL = "key_last_channel_url"

        // Default open-source IPTV list (iptv-org free legal channels or VN channels)
        const val DEFAULT_URL = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/vn.m3u"
    }
}

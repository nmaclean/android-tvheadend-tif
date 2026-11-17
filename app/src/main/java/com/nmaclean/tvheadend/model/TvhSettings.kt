package com.nmaclean.tvheadend.model

import android.content.Context
import android.content.SharedPreferences

/**
 * SharedPreferences manager for storing Tvheadend server IP, ports, and authentication credentials.
 */
class TvhSettings(context: Context) {
    companion object {
        private const val PREFS_NAME = "tvh_settings"
        private const val KEY_HOST = "host"
        private const val KEY_HTSP_PORT = "htsp_port"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"

        @Volatile
        var isStreamingDebugCached: Boolean = false
            private set
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        isStreamingDebugCached = prefs.getBoolean("streaming_debug", false)
    }

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var htspPort: Int
        get() = prefs.getInt(KEY_HTSP_PORT, TvhConstants.DEFAULT_HTSP_PORT)
        set(value) = prefs.edit().putInt(KEY_HTSP_PORT, value).apply()

    var httpPort: Int
        get() = prefs.getInt(KEY_HTTP_PORT, TvhConstants.DEFAULT_HTTP_PORT)
        set(value) = prefs.edit().putInt(KEY_HTTP_PORT, value).apply()

    var username: String
        get() = prefs.getString(KEY_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASS, value).apply()

    var isFirstInstallCleanupDone: Boolean
        get() = prefs.getBoolean("first_install_cleanup_done", false)
        set(value) = prefs.edit().putBoolean("first_install_cleanup_done", value).apply()

    var subtitleBottomPaddingFraction: Float
        get() = prefs.getFloat("subtitle_bottom_padding", 0.08f)
        set(value) = prefs.edit().putFloat("subtitle_bottom_padding", value).apply()

    var isStreamingDebugEnabled: Boolean
        get() = prefs.getBoolean("streaming_debug", false).also { isStreamingDebugCached = it }
        set(value) {
            isStreamingDebugCached = value
            prefs.edit().putBoolean("streaming_debug", value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

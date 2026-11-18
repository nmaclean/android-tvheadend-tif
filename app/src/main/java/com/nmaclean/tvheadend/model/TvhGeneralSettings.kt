package com.nmaclean.tvheadend.model

import android.content.Context
import android.content.SharedPreferences

/**
 * Isolated SharedPreferences store for General app settings
 * (`isStreamingDebugEnabled`, `subtitleBottomPaddingFraction`), separate from TIF server settings.
 */
class TvhGeneralSettings(context: Context) {
    companion object {
        private const val GENERAL_PREFS_NAME = "tvh_general_settings"
        private const val KEY_STREAMING_DEBUG = "streaming_debug"
        private const val KEY_SUBTITLE_PADDING = "subtitle_bottom_padding"

        @Volatile
        var isStreamingDebugCached: Boolean = false
            private set

        fun refreshStreamingDebug(context: Context): Boolean {
            val prefs = context.getSharedPreferences(GENERAL_PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_STREAMING_DEBUG, false).also { isStreamingDebugCached = it }
        }
    }

    private val generalPrefs: SharedPreferences = context.getSharedPreferences(GENERAL_PREFS_NAME, Context.MODE_PRIVATE)

    init {
        isStreamingDebugCached = generalPrefs.getBoolean(KEY_STREAMING_DEBUG, false)
    }

    var subtitleBottomPaddingFraction: Float
        get() = generalPrefs.getFloat(KEY_SUBTITLE_PADDING, 0.08f)
        set(value) = generalPrefs.edit().putFloat(KEY_SUBTITLE_PADDING, value).apply()

    var isStreamingDebugEnabled: Boolean
        get() = generalPrefs.getBoolean(KEY_STREAMING_DEBUG, false).also { isStreamingDebugCached = it }
        set(value) {
            isStreamingDebugCached = value
            generalPrefs.edit().putBoolean(KEY_STREAMING_DEBUG, value).apply()
        }

    fun clear() {
        isStreamingDebugCached = false
        generalPrefs.edit().clear().apply()
    }
}

package com.nmaclean.tvheadend

import android.content.Context
import android.content.SharedPreferences

class TvhSettings(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences(TvhConstants.PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(TvhConstants.KEY_HOST, TvhConstants.DEFAULT_HOST) ?: ""
        set(value) = prefs.edit().putString(TvhConstants.KEY_HOST, value).apply()

    var httpPort: Int
        get() = prefs.getInt(TvhConstants.KEY_HTTP_PORT, TvhConstants.DEFAULT_HTTP_PORT)
        set(value) = prefs.edit().putInt(TvhConstants.KEY_HTTP_PORT, value).apply()

    var htspPort: Int
        get() = prefs.getInt(TvhConstants.KEY_HTSP_PORT, TvhConstants.DEFAULT_HTSP_PORT)
        set(value) = prefs.edit().putInt(TvhConstants.KEY_HTSP_PORT, value).apply()

    var username: String
        get() = prefs.getString(TvhConstants.KEY_USERNAME, TvhConstants.DEFAULT_USERNAME) ?: ""
        set(value) = prefs.edit().putString(TvhConstants.KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(TvhConstants.KEY_PASSWORD, TvhConstants.DEFAULT_PASSWORD) ?: ""
        set(value) = prefs.edit().putString(TvhConstants.KEY_PASSWORD, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}

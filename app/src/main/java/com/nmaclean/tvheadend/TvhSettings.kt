package com.nmaclean.tvheadend

import android.content.Context
import android.content.SharedPreferences

class TvhSettings(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value).apply()

    var httpPort: Int
        get() = prefs.getInt("httpPort", 9981)
        set(value) = prefs.edit().putInt("httpPort", value).apply()

    var htspPort: Int
        get() = prefs.getInt("port", 9982)
        set(value) = prefs.edit().putInt("port", value).apply()

    var username: String
        get() = prefs.getString("user", "") ?: ""
        set(value) = prefs.edit().putString("user", value).apply()

    var password: String
        get() = prefs.getString("pass", "") ?: ""
        set(value) = prefs.edit().putString("pass", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}

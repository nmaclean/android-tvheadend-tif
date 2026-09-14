package com.nmaclean.tvheadend

import android.content.Context
import android.content.SharedPreferences

class TvhSettings(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("tvh_settings", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "192.168.4.100") ?: "192.168.4.100"
        set(value) = prefs.edit().putString("host", value).apply()

    var httpPort: Int
        get() = prefs.getInt("http_port", 9981)
        set(value) = prefs.edit().putInt("http_port", value).apply()

    var htspPort: Int
        get() = prefs.getInt("htsp_port", 9982)
        set(value) = prefs.edit().putInt("htsp_port", value).apply()

    var username: String
        get() = prefs.getString("username", "admin") ?: "admin"
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "ab1903") ?: "ab1903"
        set(value) = prefs.edit().putString("password", value).apply()
}

package com.nmaclean.tvheadend

import android.content.Context
import android.content.SharedPreferences

class TvhSettings(context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "192.168.4.100") ?: ""
        set(value) = prefs.edit().putString("host", value).apply()

    var httpPort: Int
        get() = prefs.getInt("httpPort", 9983)
        set(value) = prefs.edit().putInt("httpPort", value).apply()

    var htspPort: Int
        get() = prefs.getInt("port", 9984)
        set(value) = prefs.edit().putInt("port", value).apply()

    var username: String
        get() = prefs.getString("user", "admin") ?: ""
        set(value) = prefs.edit().putString("user", value).apply()

    var password: String
        get() = prefs.getString("pass", "ab1903") ?: ""
        set(value) = prefs.edit().putString("pass", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}

package com.nmaclean.tvheadend.iptv.epg

import android.content.Context
import androidx.preference.PreferenceManager

class IptvEpgSettingsStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var isEpgEnabled: Boolean
        get() = prefs.getBoolean("iptv_epg_enabled", true)
        set(value) = prefs.edit().putBoolean("iptv_epg_enabled", value).apply()

    var epgUrl: String
        get() = prefs.getString("iptv_epg_url", IptvEpgConstants.DEFAULT_IPTV_EPG_URL) ?: IptvEpgConstants.DEFAULT_IPTV_EPG_URL
        set(value) = prefs.edit().putString("iptv_epg_url", value).apply()

    var epgFallbackUrl: String
        get() = prefs.getString("iptv_epg_fallback_url", "") ?: ""
        set(value) = prefs.edit().putString("iptv_epg_fallback_url", value).apply()

    var lookaheadHours: Int
        get() = prefs.getInt("iptv_epg_lookahead_hours", 48)
        set(value) = prefs.edit().putInt("iptv_epg_lookahead_hours", value).apply()

    var offsetMinutes: Int
        get() = prefs.getInt("iptv_epg_offset_minutes", 0)
        set(value) = prefs.edit().putInt("iptv_epg_offset_minutes", value).apply()

    var matchByName: Boolean
        get() = prefs.getBoolean("iptv_epg_match_by_name", true)
        set(value) = prefs.edit().putBoolean("iptv_epg_match_by_name", value).apply()

    var isLogosEnabled: Boolean
        get() = prefs.getBoolean("iptv_logos_enabled", true)
        set(value) = prefs.edit().putBoolean("iptv_logos_enabled", value).apply()

    var useEpgIcon: Boolean
        get() = prefs.getBoolean("iptv_logo_use_epg_icon", true)
        set(value) = prefs.edit().putBoolean("iptv_logo_use_epg_icon", value).apply()
}

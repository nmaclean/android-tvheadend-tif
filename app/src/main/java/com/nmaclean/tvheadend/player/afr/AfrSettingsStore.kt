package com.nmaclean.tvheadend.player.afr

import android.content.Context
import android.content.SharedPreferences

class AfrSettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("afr_settings", Context.MODE_PRIVATE)

    var afrMode: AfrMode
        get() {
            val name = prefs.getString("afr_mode", AfrMode.SEAMLESS_ONLY.name)
            return try {
                AfrMode.valueOf(name ?: AfrMode.SEAMLESS_ONLY.name)
            } catch (_: Exception) {
                AfrMode.SEAMLESS_ONLY
            }
        }
        set(value) {
            prefs.edit().putString("afr_mode", value.name).apply()
        }

    var restoreOnExit: Boolean
        get() = prefs.getBoolean("afr_restore_on_exit", true)
        set(value) {
            prefs.edit().putBoolean("afr_restore_on_exit", value).apply()
        }
}

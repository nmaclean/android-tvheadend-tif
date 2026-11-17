package com.nmaclean.tvheadend.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * SharedPreferences manager for storing Tvheadend server IP, ports, and encrypted authentication credentials.
 */
class TvhSettings(context: Context) {
    companion object {
        private const val TAG = "TvhSettings"
        private const val PREFS_NAME = "tvh_settings"
        private const val SECURE_PREFS_NAME = "tvh_secure_settings"
        private const val KEY_HOST = "host"
        private const val KEY_HTSP_PORT = "htsp_port"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"

        @Volatile
        var isStreamingDebugCached: Boolean = false
            private set

        @Volatile
        private var cachedSecurePrefs: SharedPreferences? = null

        fun refreshStreamingDebug(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("streaming_debug", false).also { isStreamingDebugCached = it }
        }

        internal fun getOrCreateEncryptedPrefs(context: Context): SharedPreferences? {
            cachedSecurePrefs?.let { return it }
            return synchronized(this) {
                cachedSecurePrefs ?: createEncryptedPrefs(context)?.also { cachedSecurePrefs = it }
            }
        }

        internal fun createEncryptedPrefs(context: Context): SharedPreferences? {
            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    SECURE_PREFS_NAME,
                    masterKeyAlias,
                    context.applicationContext ?: context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences unavailable; falling back to standard SharedPreferences: ${e.message}")
                null
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = getOrCreateEncryptedPrefs(context) ?: prefs

    init {
        isStreamingDebugCached = prefs.getBoolean("streaming_debug", false)
        migrateLegacyCredentials()
    }

    private fun migrateLegacyCredentials() {
        if (securePrefs === prefs) return
        val hasLegacyUser = prefs.contains(KEY_USER)
        val hasLegacyPass = prefs.contains(KEY_PASS)
        if (!hasLegacyUser && !hasLegacyPass) return

        val secureEditor = securePrefs.edit()
        val legacyEditor = prefs.edit()
        if (hasLegacyUser) {
            if (!securePrefs.contains(KEY_USER)) {
                secureEditor.putString(KEY_USER, prefs.getString(KEY_USER, "") ?: "")
            }
            legacyEditor.remove(KEY_USER)
        }
        if (hasLegacyPass) {
            if (!securePrefs.contains(KEY_PASS)) {
                secureEditor.putString(KEY_PASS, prefs.getString(KEY_PASS, "") ?: "")
            }
            legacyEditor.remove(KEY_PASS)
        }
        secureEditor.apply()
        legacyEditor.apply()
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
        get() = securePrefs.getString(KEY_USER, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = securePrefs.getString(KEY_PASS, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_PASS, value).apply()

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
        if (securePrefs !== prefs) {
            securePrefs.edit().clear().apply()
        }
    }
}

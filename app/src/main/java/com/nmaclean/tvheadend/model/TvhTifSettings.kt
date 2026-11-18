package com.nmaclean.tvheadend.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Isolated SharedPreferences store for Android TV TIF server connection & input settings
 * (`host`, `htspPort`, `httpPort`, encrypted `username`/`password`, and `isFirstInstallCleanupDone`).
 */
class TvhTifSettings(context: Context) {
    companion object {
        private const val TAG = "TvhTifSettings"
        private const val LEGACY_PREFS_NAME = "tvh_settings"
        private const val TIF_PREFS_NAME = "tvh_tif_settings"
        private const val SECURE_PREFS_NAME = "tvh_secure_settings"

        private const val KEY_HOST = "host"
        private const val KEY_HTSP_PORT = "htsp_port"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_USER = "user"
        private const val KEY_PASS = "pass"
        private const val KEY_CLEANUP_DONE = "first_install_cleanup_done"

        @Volatile
        private var cachedSecurePrefs: SharedPreferences? = null

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

    private val legacyPrefs: SharedPreferences = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    private val tifPrefs: SharedPreferences = context.getSharedPreferences(TIF_PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = getOrCreateEncryptedPrefs(context) ?: tifPrefs

    init {
        migrateFromLegacyIfNeeded()
    }

    private fun migrateFromLegacyIfNeeded() {
        val tifEditor = tifPrefs.edit()
        val legacyEditor = legacyPrefs.edit()
        var tifChanged = false
        var legacyChanged = false

        if (legacyPrefs.contains(KEY_HOST)) {
            if (!tifPrefs.contains(KEY_HOST)) {
                tifEditor.putString(KEY_HOST, legacyPrefs.getString(KEY_HOST, "") ?: "")
                tifChanged = true
            }
            legacyEditor.remove(KEY_HOST)
            legacyChanged = true
        }
        if (legacyPrefs.contains(KEY_HTSP_PORT)) {
            if (!tifPrefs.contains(KEY_HTSP_PORT)) {
                tifEditor.putInt(KEY_HTSP_PORT, legacyPrefs.getInt(KEY_HTSP_PORT, TvhConstants.DEFAULT_HTSP_PORT))
                tifChanged = true
            }
            legacyEditor.remove(KEY_HTSP_PORT)
            legacyChanged = true
        }
        if (legacyPrefs.contains(KEY_HTTP_PORT)) {
            if (!tifPrefs.contains(KEY_HTTP_PORT)) {
                tifEditor.putInt(KEY_HTTP_PORT, legacyPrefs.getInt(KEY_HTTP_PORT, TvhConstants.DEFAULT_HTTP_PORT))
                tifChanged = true
            }
            legacyEditor.remove(KEY_HTTP_PORT)
            legacyChanged = true
        }
        if (legacyPrefs.contains(KEY_CLEANUP_DONE)) {
            if (!tifPrefs.contains(KEY_CLEANUP_DONE)) {
                tifEditor.putBoolean(KEY_CLEANUP_DONE, legacyPrefs.getBoolean(KEY_CLEANUP_DONE, false))
                tifChanged = true
            }
            legacyEditor.remove(KEY_CLEANUP_DONE)
            legacyChanged = true
        }

        if (securePrefs !== legacyPrefs) {
            val secureEditor = securePrefs.edit()
            var secureChanged = false
            if (legacyPrefs.contains(KEY_USER)) {
                if (!securePrefs.contains(KEY_USER)) {
                    secureEditor.putString(KEY_USER, legacyPrefs.getString(KEY_USER, "") ?: "")
                    secureChanged = true
                }
                legacyEditor.remove(KEY_USER)
                legacyChanged = true
            }
            if (legacyPrefs.contains(KEY_PASS)) {
                if (!securePrefs.contains(KEY_PASS)) {
                    secureEditor.putString(KEY_PASS, legacyPrefs.getString(KEY_PASS, "") ?: "")
                    secureChanged = true
                }
                legacyEditor.remove(KEY_PASS)
                legacyChanged = true
            }
            if (secureChanged) secureEditor.apply()
        }

        if (tifChanged) tifEditor.apply()
        if (legacyChanged) legacyEditor.apply()
    }

    var host: String
        get() = tifPrefs.getString(KEY_HOST, "") ?: ""
        set(value) = tifPrefs.edit().putString(KEY_HOST, value).apply()

    var htspPort: Int
        get() = tifPrefs.getInt(KEY_HTSP_PORT, TvhConstants.DEFAULT_HTSP_PORT)
        set(value) = tifPrefs.edit().putInt(KEY_HTSP_PORT, value).apply()

    var httpPort: Int
        get() = tifPrefs.getInt(KEY_HTTP_PORT, TvhConstants.DEFAULT_HTTP_PORT)
        set(value) = tifPrefs.edit().putInt(KEY_HTTP_PORT, value).apply()

    var username: String
        get() = securePrefs.getString(KEY_USER, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = securePrefs.getString(KEY_PASS, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_PASS, value).apply()

    var isFirstInstallCleanupDone: Boolean
        get() = tifPrefs.getBoolean(KEY_CLEANUP_DONE, false)
        set(value) = tifPrefs.edit().putBoolean(KEY_CLEANUP_DONE, value).apply()

    fun clear() {
        tifPrefs.edit().clear().apply()
        if (securePrefs !== tifPrefs) {
            securePrefs.edit().clear().apply()
        }
    }
}

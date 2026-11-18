package com.nmaclean.tvheadend.model

import android.content.Context

/**
 * Unified facade delegating TIF server settings to [TvhTifSettings] and General app settings
 * to [TvhGeneralSettings].
 */
class TvhSettings(context: Context) {
    companion object {
        val isStreamingDebugCached: Boolean
            get() = TvhGeneralSettings.isStreamingDebugCached

        fun refreshStreamingDebug(context: Context): Boolean =
            TvhGeneralSettings.refreshStreamingDebug(context)
    }

    val tif: TvhTifSettings = TvhTifSettings(context)
    val general: TvhGeneralSettings = TvhGeneralSettings(context)

    var host: String
        get() = tif.host
        set(value) { tif.host = value }

    var htspPort: Int
        get() = tif.htspPort
        set(value) { tif.htspPort = value }

    var httpPort: Int
        get() = tif.httpPort
        set(value) { tif.httpPort = value }

    var username: String
        get() = tif.username
        set(value) { tif.username = value }

    var password: String
        get() = tif.password
        set(value) { tif.password = value }

    var isFirstInstallCleanupDone: Boolean
        get() = tif.isFirstInstallCleanupDone
        set(value) { tif.isFirstInstallCleanupDone = value }

    var subtitleBottomPaddingFraction: Float
        get() = general.subtitleBottomPaddingFraction
        set(value) { general.subtitleBottomPaddingFraction = value }

    var isStreamingDebugEnabled: Boolean
        get() = general.isStreamingDebugEnabled
        set(value) { general.isStreamingDebugEnabled = value }

    fun clear() {
        tif.clear()
    }
}

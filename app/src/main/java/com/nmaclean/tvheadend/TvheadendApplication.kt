package com.nmaclean.tvheadend

import android.app.Application
import android.util.Log

class TvheadendApplication : Application() {
    companion object {
        var proxyServer: HtspProxyServer? = null
    }

    override fun onCreate() {
        super.onCreate()
        try {
            proxyServer = HtspProxyServer(this, 8999)
            proxyServer?.start()
            Log.d("TvhApp", "HTSP Local Proxy Server started on port 8999")
        } catch (e: Exception) {
            Log.e("TvhApp", "Failed to start HTSP proxy server", e)
        }
    }
}

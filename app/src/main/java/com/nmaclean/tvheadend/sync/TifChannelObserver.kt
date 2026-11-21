package com.nmaclean.tvheadend.sync

import android.content.ContentResolver
import android.database.ContentObserver
import android.media.tv.TvContract
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Reusable, debounced ContentObserver that monitors TvContract.Channels for real-time channel updates
 * across all TV input services without flooding the UI thread during bulk EPG ingestion.
 */
class TifChannelObserver(
    private val contentResolver: ContentResolver,
    private val tag: String = "TifChannelObserver",
    private val observePrograms: Boolean = false,
    private val debounceMs: Long = 250L,
    private val onChangeCallback: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null

    override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null as Uri?)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        pendingDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable {
            Log.i(tag, "Debounced TvContract update (uri=$uri); triggering reload...")
            onChangeCallback()
        }
        pendingDebounceRunnable = runnable
        debounceHandler.postDelayed(runnable, debounceMs)
    }

    fun register() {
        try {
            contentResolver.registerContentObserver(TvContract.Channels.CONTENT_URI, true, this)
            if (observePrograms) {
                contentResolver.registerContentObserver(TvContract.Programs.CONTENT_URI, true, this)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to register TifChannelObserver: ${e.message}")
        }
    }

    fun unregister() {
        try {
            pendingDebounceRunnable?.let { debounceHandler.removeCallbacks(it) }
            pendingDebounceRunnable = null
            contentResolver.unregisterContentObserver(this)
        } catch (e: Exception) {
            Log.w(tag, "Failed to unregister TifChannelObserver: ${e.message}")
        }
    }
}

package com.nmaclean.tvheadend

import android.app.Service
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.media.tv.TvContract
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SyncService : Service() {

    companion object {
        const val TAG = "SyncService"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SyncService started. Fetching channels from Tvheadend...")
        
        val settings = TvhSettings(this)
        val host = settings.host
        val port = settings.htspPort
        val httpPort = settings.httpPort
        val user = settings.username
        val pass = settings.password

        serviceScope.launch {
            try {
                val client = HtspClient(host, port)
                if (client.connect(user, pass)) {
                    val channels = client.fetchChannels()
                    client.disconnect()

                    syncChannelsToProvider(host, httpPort, channels)
                } else {
                    Log.e(TAG, "Failed to connect to HTSP server during sync")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during channel sync task", e)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun syncChannelsToProvider(host: String, httpPort: Int, channels: List<TvhChannel>) {
        val inputId = TvContract.buildInputId(ComponentName(this, TvheadendInputService::class.java))
        val resolver = contentResolver

        Log.d(TAG, "Syncing ${channels.size} channels to Android TIF provider...")
        
        // Optionally clear registry if you use one, but here we focus on Provider data
        // ChannelRegistry.clear() 

        // Delete old channels for this input
        resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)

        for (ch in channels) {
            val logoUri = "http://$host:$httpPort/imagecache/channels/${ch.uuid}"
            val uuidToStore = if (ch.uuid.isNotEmpty()) ch.uuid else ch.id.toString()

            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, ch.number.toString())
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                put(TvContract.Channels.COLUMN_APP_LINK_ICON_URI, logoUri)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, uuidToStore.toByteArray(Charsets.UTF_8))
            }

            try {
                val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values)
                Log.d(TAG, "Inserted channel '${ch.name}' (UUID: $uuidToStore) -> URI: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert channel ${ch.name}", e)
            }
        }
        
        Log.d(TAG, "TIF Channel sync completed successfully.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

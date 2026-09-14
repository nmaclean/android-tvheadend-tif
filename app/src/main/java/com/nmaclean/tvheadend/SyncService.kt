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
        
        val prefs = getSharedPreferences("TvhPrefs", MODE_PRIVATE)
        val host = prefs.getString("host", "192.168.4.100") ?: "192.168.4.100"
        val port = prefs.getInt("port", 9982)
        val user = prefs.getString("user", "admin") ?: "admin"
        val pass = prefs.getString("pass", "ab1903") ?: "ab1903"

        serviceScope.launch {
            try {
                val client = HtspClient(host, port)
                if (client.connect(user, pass)) {
                    val channels = client.fetchChannels()
                    client.disconnect()

                    syncChannelsToProvider(channels)
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

    private fun syncChannelsToProvider(channels: List<TvhChannel>) {
        val inputId = TvContract.buildInputId(ComponentName(this, TvheadendInputService::class.java))
        val resolver = contentResolver

        Log.d(TAG, "Syncing ${channels.size} channels to Android TIF provider...")
        ChannelRegistry.clear()

        for (channel in channels) {
            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, channel.number.toString())
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.uuid)
            }

            try {
                val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values)
                val rowId = uri?.lastPathSegment
                if (rowId != null) {
                    // Map both the SQLite row ID and the display number to the true Tvheadend UUID
                    ChannelRegistry.register(rowId, channel.uuid)
                    ChannelRegistry.register(channel.number.toString(), channel.uuid)
                }
                Log.d(TAG, "Inserted channel '${channel.name}' (UUID: ${channel.uuid}) -> URI: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert channel ${channel.name}", e)
            }
        }
        
        Log.d(TAG, "TIF Channel sync completed successfully.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

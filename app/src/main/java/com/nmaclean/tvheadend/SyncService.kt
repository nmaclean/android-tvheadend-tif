package com.nmaclean.tvheadend

import android.app.Service
import android.content.Intent
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
        Log.d(TAG, "SyncService started. Executing background EPG and logo sync...")

        serviceScope.launch {
            try {
                val result = TvhSyncManager.performEpgAndLogoSync(this@SyncService)
                if (result.success) {
                    Log.d(TAG, "SyncService completed successfully: ${result.eventCount} programs synced.")
                } else {
                    Log.e(TAG, "SyncService failed: ${result.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in SyncService scope", e)
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}

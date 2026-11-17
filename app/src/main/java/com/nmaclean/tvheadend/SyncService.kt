package com.nmaclean.tvheadend

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.nmaclean.tvheadend.sync.TvhSyncManager
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
                TvhSyncManager.performEpgAndLogoSync(this@SyncService)
                Log.d(TAG, "SyncService completed successfully.")
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

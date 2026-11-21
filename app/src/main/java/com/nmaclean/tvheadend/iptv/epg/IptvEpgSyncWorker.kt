package com.nmaclean.tvheadend.iptv.epg

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nmaclean.tvheadend.sync.IptvEpgSyncStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class IptvEpgSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "IptvEpgSyncWorker"
        const val WORK_NAME = "iptv_periodic_epg_sync_work"

        fun schedulePeriodicSync(context: Context) {
            val strategy = IptvEpgSyncStrategy()
            if (!strategy.isEnabled(context)) return

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<IptvEpgSyncWorker>(
                strategy.repeatIntervalHours,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
            Log.i(TAG, "Successfully scheduled ${strategy.repeatIntervalHours}-hour periodic IPTV XMLTV EPG sync worker.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val strategy = IptvEpgSyncStrategy()
        strategy.performSync(applicationContext)
    }
}

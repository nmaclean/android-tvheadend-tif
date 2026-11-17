package com.nmaclean.tvheadend.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker responsible for polling EPG and channel updates from Tvheadend every 24 hours.
 */
class TvhSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val TAG = "TvhSyncWorker"
        const val WORK_NAME = "tvh_periodic_sync_work"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicWorkRequest = PeriodicWorkRequestBuilder<TvhSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWorkRequest
            )
            Log.i(TAG, "Successfully scheduled 24-hour periodic EPG & channel sync worker.")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Starting periodic 24-hour EPG and channel sync...")
            val result = TvhSyncManager.performSync(applicationContext)
            if (result.success) {
                Log.i(TAG, "Periodic sync completed successfully: ${result.channelCount} channels, ${result.programCount} programs, ${result.logoCount} logos.")
                Result.success()
            } else {
                Log.w(TAG, "Periodic sync failed: ${result.error}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during periodic sync worker", e)
            Result.failure()
        }
    }
}

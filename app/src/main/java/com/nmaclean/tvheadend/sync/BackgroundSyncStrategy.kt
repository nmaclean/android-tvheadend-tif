package com.nmaclean.tvheadend.sync

import android.content.ComponentName
import android.content.Context
import androidx.work.ListenableWorker.Result
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSyncer
import com.nmaclean.tvheadend.iptv.epg.StreamingXmltvParser
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import com.nmaclean.tvheadend.iptv.tif.IptvTvInputService
import com.nmaclean.tvheadend.model.TvhSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

interface BackgroundSyncStrategy {
    val workName: String
    val repeatIntervalHours: Long
    val componentName: ComponentName
    fun isEnabled(context: Context): Boolean
    suspend fun performSync(context: Context): Result
}

class TvheadendSyncStrategy : BackgroundSyncStrategy {
    override val workName: String = TvhSyncWorker.WORK_NAME
    override val repeatIntervalHours: Long = 24L
    override val componentName: ComponentName = ComponentName("com.nmaclean.tvheadend", "com.nmaclean.tvheadend.TvheadendInputService")

    override fun isEnabled(context: Context): Boolean {
        val settings = TvhSettings(context)
        return settings.host.isNotBlank()
    }

    override suspend fun performSync(context: Context): Result {
        val syncRes = TvhSyncManager.performSync(context)
        return if (syncRes.success) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}

class IptvEpgSyncStrategy : BackgroundSyncStrategy {
    override val workName: String = "iptv_periodic_epg_sync_work"
    override val repeatIntervalHours: Long = 12L
    override val componentName: ComponentName = ComponentName("com.nmaclean.tvheadend", "com.nmaclean.tvheadend.iptv.tif.IptvTvInputService")

    override fun isEnabled(context: Context): Boolean {
        val store = IptvEpgSettingsStore(context)
        return store.isEpgEnabled && store.epgUrl.trim().startsWith("http")
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override suspend fun performSync(context: Context): Result {
        if (!isEnabled(context)) return Result.success()

        val store = IptvEpgSettingsStore(context)
        val epgUrlStr = store.epgUrl.trim()
        val localEpg = File(context.filesDir, "iptv_epg.xml")

        try {
            val url = URL(epgUrlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.inputStream.use { netStream ->
                localEpg.outputStream().use { fileOut ->
                    netStream.copyTo(fileOut)
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
            val fallbackUrlStr = store.epgFallbackUrl.trim()
            if (fallbackUrlStr.startsWith("http")) {
                try {
                    val url = URL(fallbackUrlStr)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    conn.inputStream.use { netStream ->
                        localEpg.outputStream().use { fileOut ->
                            netStream.copyTo(fileOut)
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    return Result.retry()
                }
            } else {
                return Result.retry()
            }
        }

        if (!localEpg.exists() || localEpg.length() == 0L) return Result.retry()

        try {
            val minEpoch = System.currentTimeMillis() - 12 * 3600 * 1000L
            val parseResult = localEpg.inputStream().use { StreamingXmltvParser.parse(it, minEpochMs = minEpoch) }
            val allowlistRepo = AllowlistRepository(context.filesDir)
            val iptvComponent = ComponentName(context, IptvTvInputService::class.java)
            val epgSyncer = IptvEpgSyncer(context.contentResolver, iptvComponent, allowlistRepo)
            epgSyncer.syncPrograms(parseResult)
            return Result.success()
        } catch (_: Exception) {
            return Result.failure()
        }
    }
}

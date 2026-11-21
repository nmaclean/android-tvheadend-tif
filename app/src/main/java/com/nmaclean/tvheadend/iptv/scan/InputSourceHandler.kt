package com.nmaclean.tvheadend.iptv.scan

import android.content.ComponentName
import android.content.Context
import android.media.tv.TvContract
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSyncer
import com.nmaclean.tvheadend.iptv.epg.StreamingXmltvParser
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import com.nmaclean.tvheadend.iptv.tif.TifAllowlistSyncer
import com.nmaclean.tvheadend.sync.TvhSyncManager
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

interface InputSourceHandler {
    val source: ScanSource
    val componentName: ComponentName
    suspend fun scan(context: Context, onStep: (String, Int) -> Unit): String
    suspend fun clear(context: Context): ClearResult
    suspend fun getChannelCount(context: Context): Int
}

class TvheadendSourceHandler : InputSourceHandler {
    override val source: ScanSource = ScanSource.TVHEADEND
    override val componentName: ComponentName = ComponentName("com.nmaclean.tvheadend", "com.nmaclean.tvheadend.TvheadendInputService")

    override suspend fun scan(context: Context, onStep: (String, Int) -> Unit): String {
        onStep("Syncing Tvheadend HTSP channels & EPG...", 40)
        val syncRes = TvhSyncManager.performSync(context)
        return if (syncRes.success) {
            "Tvheadend: ${syncRes.channelCount} channels, ${syncRes.programCount} EPG events."
        } else {
            "Tvheadend: ${syncRes.error ?: "Sync completed with warnings"}"
        }
    }

    override suspend fun clear(context: Context): ClearResult {
        val stats = TvhSyncManager.clearTvheadendData(context)
        return ClearResult(stats.clearedChannels, stats.clearedPrograms, stats.clearedLogos)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override suspend fun getChannelCount(context: Context): Int {
        var count = 0
        try {
            val tvhInputId = TvContract.buildInputId(componentName)
            val uri = TvContract.buildChannelsUriForInput(tvhInputId)
            val projection = arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    val providerId = if (providerIdCol >= 0) cursor.getString(providerIdCol) ?: "" else ""
                    if (!providerId.startsWith("iptv:")) count++
                }
            }
        } catch (_: Exception) {}
        return count
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class IptvSourceHandler : InputSourceHandler {
    override val source: ScanSource = ScanSource.IPTV
    override val componentName: ComponentName = ComponentName("com.nmaclean.tvheadend", "com.nmaclean.tvheadend.iptv.tif.IptvTvInputService")

    override suspend fun scan(context: Context, onStep: (String, Int) -> Unit): String {
        onStep("Syncing IPTV allowlist & EPG...", 75)
        val allowlistRepo = AllowlistRepository(context.filesDir)
        val allowlistSyncer = TifAllowlistSyncer(context.contentResolver, componentName, allowlistRepo)
        val channelCount = allowlistSyncer.sync()

        var epgCount = 0
        try {
            val epgSettings = IptvEpgSettingsStore(context)
            val localEpg = File(context.filesDir, "iptv_epg.xml")
            val epgUrlStr = epgSettings.epgUrl.trim()
            if (epgSettings.isEpgEnabled && epgUrlStr.isNotEmpty() && epgUrlStr.startsWith("http")) {
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
                } catch (_: Exception) {}
            }

            val inputStream: InputStream? = if (localEpg.exists() && localEpg.length() > 0L) {
                localEpg.inputStream()
            } else {
                try {
                    context.assets.open("curation/xmltv.xml")
                } catch (_: Exception) {
                    null
                }
            }
            if (inputStream != null) {
                val minEpoch = System.currentTimeMillis() - 12 * 3600 * 1000L
                val parseResult = inputStream.use { StreamingXmltvParser.parse(it, minEpochMs = minEpoch) }
                val epgSyncer = IptvEpgSyncer(context.contentResolver, componentName, allowlistRepo)
                epgCount = epgSyncer.syncPrograms(parseResult)
            }
        } catch (_: Exception) {}

        return "IPTV Scan Completed: $channelCount channels synced, $epgCount EPG programs synced."
    }

    override suspend fun clear(context: Context): ClearResult {
        var deletedChannels = 0
        var deletedPrograms = 0
        var deletedLogos = 0
        try {
            val inputId = try { TvContract.buildInputId(componentName) } catch (_: Throwable) { "" }
            val channelsUri = if (inputId.isNotEmpty()) try { TvContract.buildChannelsUriForInput(inputId) } catch (_: Throwable) { null } else null

            if (channelsUri != null) {
                context.contentResolver.query(channelsUri, arrayOf(TvContract.Channels._ID), null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    while (cursor.moveToNext()) {
                        val rowId = cursor.getLong(idCol)
                        try {
                            val pCount = context.contentResolver.delete(TvContract.buildProgramsUriForChannel(rowId), null, null)
                            deletedPrograms += pCount.coerceAtLeast(0)
                        } catch (_: Throwable) {}
                        try {
                            val lCount = context.contentResolver.delete(TvContract.buildChannelLogoUri(rowId), null, null)
                            if (lCount > 0) deletedLogos++
                        } catch (_: Throwable) {}
                        try {
                            val cCount = context.contentResolver.delete(android.content.ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, rowId), null, null)
                            if (cCount > 0) deletedChannels++
                        } catch (_: Throwable) {}
                    }
                }
            }

            val iptvLogoDir = File(context.filesDir, "iptv_logos")
            if (iptvLogoDir.exists()) {
                iptvLogoDir.listFiles()?.forEach { file ->
                    if (file.delete()) deletedLogos++
                }
            }

            try {
                context.contentResolver.notifyChange(TvContract.Channels.CONTENT_URI, null)
                context.contentResolver.notifyChange(TvContract.Programs.CONTENT_URI, null)
            } catch (_: Throwable) {}
        } catch (_: Exception) {}
        return ClearResult(deletedChannels, deletedPrograms, deletedLogos)
    }

    override suspend fun getChannelCount(context: Context): Int {
        var count = 0
        try {
            val inputId = TvContract.buildInputId(componentName)
            val uri = TvContract.buildChannelsUriForInput(inputId)
            val projection = arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                while (cursor.moveToNext()) {
                    val providerId = if (providerIdCol >= 0) cursor.getString(providerIdCol) ?: "" else ""
                    if (providerId.startsWith("iptv:")) count++
                }
            }
        } catch (_: Exception) {}
        return count
    }
}

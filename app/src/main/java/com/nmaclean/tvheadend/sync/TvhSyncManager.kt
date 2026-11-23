package com.nmaclean.tvheadend.sync

import android.content.ComponentName
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.TvheadendInputService
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.htsp.HtspClient
import com.nmaclean.tvheadend.model.TvhSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sync manager responsible for connecting to Tvheadend via HTSP, fetching channels, EPG events,
 * and channel logo images, and inserting them into Android's system TvContract database.
 */
@OptIn(UnstableApi::class)
object TvhSyncManager {
    private const val TAG = "TvhSyncManager"

    data class ConnectionTestResult(
        val success: Boolean,
        val channelCount: Int = 0,
        val httpReachable: Boolean = false,
        val message: String
    )

    data class ChannelLogoPreviewItem(
        val channelId: Long,
        val number: Int,
        val name: String,
        val logoBitmap: Bitmap? = null,
        val hasLogoUrl: Boolean = false,
        val logoFailed: Boolean = false
    )

    data class SyncProgressUpdate(
        val importedChannels: Int,
        val totalChannels: Int,
        val logosLoaded: Int,
        val currentChannelNumber: Int = 0,
        val currentChannelName: String = "",
        val latestPreviewItem: ChannelLogoPreviewItem? = null
    )

    data class SyncResult(
        val success: Boolean,
        val channelCount: Int = 0,
        val logoCount: Int = 0,
        val programCount: Int = 0,
        val error: String? = null
    )

    fun testServerConnection(
        host: String,
        htspPort: Int,
        httpPort: Int,
        username: String,
        password: String
    ): ConnectionTestResult {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) {
            return ConnectionTestResult(false, message = "Enter a Server IP / Host first")
        }

        return try {
            val client = HtspClient(cleanHost, htspPort)
            if (!client.connect(username, password)) {
                client.disconnect()
                return ConnectionTestResult(
                    false,
                    message = "HTSP authentication failed at $cleanHost:$htspPort"
                )
            }
            val channels = client.fetchChannels()
            client.disconnect()

            val httpOk = try {
                Socket().use { sock ->
                    val targetHost = if (cleanHost.equals("localhost", ignoreCase = true)) "127.0.0.1" else cleanHost
                    sock.connect(InetSocketAddress(targetHost, httpPort), 2000)
                }
                true
            } catch (_: Exception) {
                false
            }

            val httpLabel = if (httpOk) "HTTP OK" else "HTTP unreachable on :$httpPort"
            ConnectionTestResult(
                success = true,
                channelCount = channels.size,
                httpReachable = httpOk,
                message = "Connected • ${channels.size} channels found ($httpLabel)"
            )
        } catch (e: Exception) {
            ConnectionTestResult(
                false,
                message = "Connection failed: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    fun performSync(
        context: Context,
        onProgress: ((SyncProgressUpdate) -> Unit)? = null
    ): SyncResult {
        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort

        if (host.isEmpty()) {
            return SyncResult(false, error = "Host is empty")
        }

        val inputId = TvContract.buildInputId(ComponentName(context, TvheadendInputService::class.java))
        Log.i(TAG, "=== STARTING FULL SYNC for Input: '$inputId' (Server: '$host:$port') ===")

        try {
            val client = HtspClient(host, port)
            if (!client.connect(settings.username, settings.password)) {
                return SyncResult(false, error = "Failed to connect or authenticate with Tvheadend at $host:$port")
            }

            val rawChannels = client.fetchChannels()
            client.disconnect()

            if (rawChannels.isEmpty()) {
                Log.w(TAG, "No channels fetched from Tvheadend server!")
                return SyncResult(false, error = "No channels found on Tvheadend server")
            }

            val channels = rawChannels.distinctBy {
                if (it.uuid.isNotEmpty()) it.uuid else if (it.number > 0) "num_${it.number}" else "id_${it.id}"
            }

            ChannelRegistry.updateChannels(channels)

            Log.i(TAG, "Fetched ${channels.size} unique channels from Tvheadend. Synchronizing with TvProvider...")
            val resolver = context.contentResolver

            val existingChannelsMap = mutableMapOf<String, Long>() // streamKey -> channelDbId
            val existingIds = mutableSetOf<Long>()

            try {
                resolver.query(
                    TvContract.Channels.CONTENT_URI,
                    arrayOf(
                        TvContract.Channels._ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                        TvContract.Channels.COLUMN_INPUT_ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID
                    ),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    val inputIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                    val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)

                    while (cursor.moveToNext()) {
                        val channelDbId = cursor.getLong(idCol)
                        val chInputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: "" else ""
                        val providerId = if (providerIdCol >= 0) cursor.getString(providerIdCol) ?: "" else ""

                        // Strictly skip IPTV channels (preserve them from Tvheadend channel pruning)
                        if (providerId.startsWith("iptv:") || chInputId.contains("IptvTvInputService")) {
                            continue
                        }

                        if (chInputId == inputId || chInputId.isEmpty() || chInputId.contains("TvheadendInputService")) {
                            existingIds.add(channelDbId)
                            val bytes = if (dataCol >= 0) cursor.getBlob(dataCol) else null
                            if (bytes != null && bytes.isNotEmpty()) {
                                val streamKey = String(bytes, Charsets.UTF_8).trim()
                                if (streamKey.isNotEmpty()) {
                                    existingChannelsMap[streamKey] = channelDbId
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not query existing channels: ${e.message}")
            }

            val syncedDbIds = mutableSetOf<Long>()
            var channelCount = 0
            val totalChannels = channels.size
            val ops = ArrayList<ContentProviderOperation>()
            val channelsUri = TvContract.buildChannelsUriForInput(inputId)

            for (ch in channels) {
                val streamKey = if (ch.uuid.isNotEmpty()) ch.uuid else ch.id.toString()
                val existingDbId = existingChannelsMap[streamKey] ?: existingChannelsMap[ch.id.toString()]

                val values = ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, ch.number.toString())
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                    put(TvContract.Channels.COLUMN_SERVICE_ID, (ch.id and 0x7FFFFFFF).toInt())
                    put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, ch.number)
                    put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, 1)
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, streamKey.toByteArray(Charsets.UTF_8))
                }

                if (existingDbId != null) {
                    val updateUri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, existingDbId)
                    ops.add(ContentProviderOperation.newUpdate(updateUri).withValues(values).build())
                    syncedDbIds.add(existingDbId)
                } else {
                    ops.add(ContentProviderOperation.newInsert(channelsUri).withValues(values).build())
                }
                channelCount++

                onProgress?.invoke(
                    SyncProgressUpdate(
                        importedChannels = channelCount,
                        totalChannels = totalChannels,
                        logosLoaded = 0,
                        currentChannelNumber = ch.number,
                        currentChannelName = ch.name,
                        latestPreviewItem = ChannelLogoPreviewItem(
                            channelId = ch.id,
                            number = ch.number,
                            name = ch.name,
                            logoBitmap = null,
                            hasLogoUrl = ch.icon.isNotEmpty(),
                            logoFailed = false
                        )
                    )
                )
            }

            // Remove channels that no longer exist on Tvheadend
            val idsToDelete = existingIds.filter { it !in syncedDbIds }
            for (channelDbId in idsToDelete) {
                val deleteUri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelDbId)
                ops.add(ContentProviderOperation.newDelete(deleteUri).build())
                ops.add(ContentProviderOperation.newDelete(TvContract.buildProgramsUriForChannel(channelDbId)).build())
            }

            if (ops.isNotEmpty()) {
                val chunkSize = 200
                for (i in ops.indices step chunkSize) {
                    val chunk = ArrayList(ops.subList(i, minOf(i + chunkSize, ops.size)))
                    try {
                        resolver.applyBatch(TvContract.AUTHORITY, chunk)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply channel batch operation chunk: ${e.message}")
                    }
                }
            }

            try {
                val scopedUri = TvContract.buildChannelsUriForInput(inputId)
                resolver.notifyChange(scopedUri, null)
                resolver.notifyChange(TvContract.Channels.CONTENT_URI, null)
            } catch (_: Throwable) {}

            Log.i(TAG, "=== Channel sync complete: $channelCount channels synchronized ===")

            val (logoCount, programCount) = performEpgAndLogoSyncWithStats(
                context = context,
                totalChannels = channelCount,
                onProgress = onProgress
            )

            return SyncResult(true, channelCount = channelCount, logoCount = logoCount, programCount = programCount)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            return SyncResult(false, error = e.message ?: "Unknown error during sync")
        }
    }

    fun performEpgAndLogoSync(context: Context) {
        performEpgAndLogoSyncWithStats(context)
    }

    private fun performEpgAndLogoSyncWithStats(
        context: Context,
        totalChannels: Int = 0,
        onProgress: ((SyncProgressUpdate) -> Unit)? = null
    ): Pair<Int, Int> {
        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort
        if (host.isEmpty()) return Pair(0, 0)

        val inputId = TvContract.buildInputId(ComponentName(context, TvheadendInputService::class.java))
        Log.i(TAG, "=== Starting background EPG & Logo sync ===")

        try {
            val client = HtspClient(host, port)
            if (!client.connect(settings.username, settings.password)) {
                Log.e(TAG, "Failed to connect HTSP for EPG sync")
                return Pair(0, 0)
            }

            val fetchedChannels = client.fetchChannels()
            ChannelRegistry.updateChannels(fetchedChannels)
            val events = client.fetchEvents()
            client.disconnect()

            val resolver = context.contentResolver
            val channelMap = mutableMapOf<Long, Long>()
            val logoUrlMap = mutableMapOf<Long, String>()

            for (ch in fetchedChannels) {
                if (ch.icon.isNotEmpty()) {
                    logoUrlMap[ch.id] = ch.icon
                }
            }

            val channelsUri = TvContract.buildChannelsUriForInput(inputId)
            resolver.query(channelsUri, arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_SERVICE_ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val serviceIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)
                val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)

                while (cursor.moveToNext()) {
                    val dbId = cursor.getLong(idCol)
                    val serviceId = cursor.getLong(serviceIdCol)
                    channelMap[serviceId] = dbId

                    val streamKey = if (dataCol >= 0) {
                        val bytes = cursor.getBlob(dataCol)
                        if (bytes != null && bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else ""
                    } else ""

                    if (streamKey.isNotEmpty()) {
                        val keyLong = streamKey.toLongOrNull()
                        if (keyLong != null) {
                            channelMap[keyLong] = dbId
                        }
                    }
                }
            }

            // 1. Sync EPG Programs FIRST so guide and OSD populate immediately
            var programCount = 0
            if (events.isNotEmpty()) {
                // Delete stale programs for Tvheadend channels only (preserving IPTV programs)
                try {
                    for (dbId in channelMap.values.distinct()) {
                        try {
                            resolver.delete(TvContract.buildProgramsUriForChannel(dbId), null, null)
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to purge existing Tvheadend programs: ${e.message}")
                }

                val contentValuesList = mutableListOf<ContentValues>()
                for (prog in events) {
                    val tifChannelId = channelMap[prog.channelId] ?: continue
                    val values = ContentValues().apply {
                        put(TvContract.Programs.COLUMN_CHANNEL_ID, tifChannelId)
                        put(TvContract.Programs.COLUMN_TITLE, prog.title)
                        put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, prog.summary)
                        put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, prog.startTime)
                        put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, prog.stopTime)
                        if (prog.eventId > 0L) {
                            put(TvContract.Programs.COLUMN_EVENT_ID, prog.eventId.toString())
                            put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA, "${prog.eventId}:${prog.channelId}".toByteArray(Charsets.UTF_8))
                        }
                    }
                    contentValuesList.add(values)
                }

                if (contentValuesList.isNotEmpty()) {
                    // Batch insert in chunks of 500
                    val batchSize = 500
                    for (chunk in contentValuesList.chunked(batchSize)) {
                        val inserted = resolver.bulkInsert(TvContract.Programs.CONTENT_URI, chunk.toTypedArray())
                        if (inserted > 0) {
                            programCount += inserted
                        }
                    }
                }
                Log.i(TAG, "=== EPG Programs inserted: $programCount programs ===")
            }

            // 2. Sync Logos concurrently in parallel
            var logoCount = 0
            if (logoUrlMap.isNotEmpty()) {
                val logosCounter = AtomicInteger(0)
                val effectiveTotal = if (totalChannels > 0) totalChannels else fetchedChannels.size
                val channelMetaMap = fetchedChannels.associateBy { it.id }

                val logoResults = runBlocking(Dispatchers.IO) {
                    logoUrlMap.mapNotNull { (htspChId, logoUrl) ->
                        val dbId = channelMap[htspChId] ?: return@mapNotNull null
                        val fullUrl = if (!logoUrl.startsWith("http://") && !logoUrl.startsWith("https://")) {
                            "http://$host:${settings.httpPort}/$logoUrl"
                        } else logoUrl

                        val logoUri = TvContract.buildChannelLogoUri(dbId)
                        val chMeta = channelMetaMap[htspChId]
                        async {
                            try {
                                val bitmap = fetchBitmap(fullUrl)
                                if (bitmap != null) {
                                    resolver.openOutputStream(logoUri)?.use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    val count = logosCounter.incrementAndGet()
                                    if (chMeta != null) {
                                        onProgress?.invoke(
                                            SyncProgressUpdate(
                                                importedChannels = effectiveTotal,
                                                totalChannels = effectiveTotal,
                                                logosLoaded = count,
                                                currentChannelNumber = chMeta.number,
                                                currentChannelName = chMeta.name,
                                                latestPreviewItem = ChannelLogoPreviewItem(
                                                    channelId = chMeta.id,
                                                    number = chMeta.number,
                                                    name = chMeta.name,
                                                    logoBitmap = bitmap,
                                                    hasLogoUrl = true,
                                                    logoFailed = false
                                                )
                                            )
                                        )
                                    }
                                    true
                                } else {
                                    if (chMeta != null) {
                                        onProgress?.invoke(
                                            SyncProgressUpdate(
                                                importedChannels = effectiveTotal,
                                                totalChannels = effectiveTotal,
                                                logosLoaded = logosCounter.get(),
                                                currentChannelNumber = chMeta.number,
                                                currentChannelName = chMeta.name,
                                                latestPreviewItem = ChannelLogoPreviewItem(
                                                    channelId = chMeta.id,
                                                    number = chMeta.number,
                                                    name = chMeta.name,
                                                    logoBitmap = null,
                                                    hasLogoUrl = true,
                                                    logoFailed = true
                                                )
                                            )
                                        )
                                    }
                                    false
                                }
                            } catch (_: Exception) {
                                if (chMeta != null) {
                                    onProgress?.invoke(
                                        SyncProgressUpdate(
                                            importedChannels = effectiveTotal,
                                            totalChannels = effectiveTotal,
                                            logosLoaded = logosCounter.get(),
                                            currentChannelNumber = chMeta.number,
                                            currentChannelName = chMeta.name,
                                            latestPreviewItem = ChannelLogoPreviewItem(
                                                channelId = chMeta.id,
                                                number = chMeta.number,
                                                name = chMeta.name,
                                                logoBitmap = null,
                                                hasLogoUrl = true,
                                                logoFailed = true
                                            )
                                        )
                                    )
                                }
                                false
                            }
                        }
                    }.awaitAll()
                }
                logoCount = logoResults.count { it }
            }

            Log.i(TAG, "=== EPG/Logo sync completed: $logoCount logos, $programCount EPG programs inserted ===")
            return Pair(logoCount, programCount)
        } catch (e: Exception) {
            Log.e(TAG, "Error in EPG & Logo sync", e)
            return Pair(0, 0)
        }
    }

    private fun fetchBitmap(urlStr: String): Bitmap? {
        var conn: HttpURLConnection? = null
        var inputStream: InputStream? = null
        return try {
            val url = URL(urlStr)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doInput = true
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = conn.inputStream
                BitmapFactory.decodeStream(inputStream)
            } else null
        } catch (e: Exception) {
            null
        } finally {
            try { inputStream?.close() } catch (ignored: Exception) {}
            conn?.disconnect()
        }
    }

data class ClearTvheadendStats(
    val clearedChannels: Int = 0,
    val clearedPrograms: Int = 0,
    val clearedLogos: Int = 0
)

    fun clearTvheadendData(context: Context): ClearTvheadendStats {
        var clearedChannels = 0
        var clearedPrograms = 0
        var clearedLogos = 0
        try {
            val resolver = context.contentResolver
            val tvhInputId = try {
                TvContract.buildInputId(ComponentName(context, TvheadendInputService::class.java))
            } catch (_: Throwable) {
                ""
            }

            val tvhChannelRowIds = mutableListOf<Long>()
            resolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_INPUT_ID,
                    TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID
                ),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val inputIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)

                while (cursor.moveToNext()) {
                    val dbId = cursor.getLong(idCol)
                    val chInputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: "" else ""
                    val providerId = if (providerIdCol >= 0) cursor.getString(providerIdCol) ?: "" else ""

                    if ((tvhInputId.isNotEmpty() && chInputId == tvhInputId) ||
                        (!providerId.startsWith("iptv:") && !chInputId.contains("IptvTvInputService"))
                    ) {
                        tvhChannelRowIds.add(dbId)
                    }
                }
            }

            for (dbId in tvhChannelRowIds) {
                try {
                    val progsDeleted = resolver.delete(TvContract.buildProgramsUriForChannel(dbId), null, null)
                    clearedPrograms += progsDeleted.coerceAtLeast(0)
                } catch (_: Throwable) {}

                try {
                    val logoDeleted = resolver.delete(TvContract.buildChannelLogoUri(dbId), null, null)
                    if (logoDeleted > 0) clearedLogos++
                } catch (_: Throwable) {}

                try {
                    val chDeleted = resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, dbId), null, null)
                    if (chDeleted > 0) clearedChannels++
                } catch (_: Throwable) {}
            }

            ChannelRegistry.updateChannels(emptyList())

            try {
                resolver.notifyChange(TvContract.Channels.CONTENT_URI, null)
                resolver.notifyChange(TvContract.Programs.CONTENT_URI, null)
            } catch (_: Throwable) {}

            Log.i(TAG, "Cleared $clearedChannels Tvheadend channels, $clearedPrograms EPG programs, and $clearedLogos logos.")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Tvheadend data: ${e.message}", e)
        }
        return ClearTvheadendStats(clearedChannels, clearedPrograms, clearedLogos)
    }

    fun clearAllChannelsAndPrograms(context: Context) {
        clearTvheadendData(context)
    }
}

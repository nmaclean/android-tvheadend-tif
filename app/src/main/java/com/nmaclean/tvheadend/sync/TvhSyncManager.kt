package com.nmaclean.tvheadend.sync

import android.content.ComponentName
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
import java.net.URL

/**
 * Sync manager responsible for connecting to Tvheadend via HTSP, fetching channels, EPG events,
 * and channel logo images, and inserting them into Android's system TvContract database.
 */
@OptIn(UnstableApi::class)
object TvhSyncManager {
    private const val TAG = "TvhSyncManager"

    data class SyncResult(
        val success: Boolean,
        val channelCount: Int = 0,
        val logoCount: Int = 0,
        val programCount: Int = 0,
        val error: String? = null
    )

    fun performSync(context: Context): SyncResult {
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
                    arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, TvContract.Channels.COLUMN_INPUT_ID),
                    null, null, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    val inputIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                    val myPackage = context.packageName

                    while (cursor.moveToNext()) {
                        val channelDbId = cursor.getLong(idCol)
                        val chInputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: "" else ""
                        if (chInputId.contains(myPackage, ignoreCase = true) || chInputId.isEmpty() || chInputId == inputId) {
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

            for (ch in channels) {
                val streamKey = if (ch.uuid.isNotEmpty()) ch.uuid else ch.id.toString()
                val existingDbId = existingChannelsMap[streamKey] ?: existingChannelsMap[ch.id.toString()]

                Log.i(TAG, "Upserting channel #${ch.number}: '${ch.name}' (streamKey=$streamKey, existingDbId=$existingDbId)")
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
                    try {
                        resolver.update(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, existingDbId), values, null, null)
                        syncedDbIds.add(existingDbId)
                        channelCount++
                        Log.d(TAG, "Updated existing channel #${ch.number} '${ch.name}' (dbId=$existingDbId)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update channel dbId=$existingDbId: ${e.message}")
                    }
                } else {
                    val insertedUri = resolver.insert(TvContract.Channels.CONTENT_URI, values)
                    if (insertedUri != null) {
                        try {
                            val newId = ContentUris.parseId(insertedUri)
                            syncedDbIds.add(newId)
                            channelCount++
                            Log.d(TAG, "Inserted new channel #${ch.number} '${ch.name}' -> $insertedUri (dbId=$newId)")
                        } catch (e: Exception) {
                            channelCount++
                            Log.d(TAG, "Inserted new channel #${ch.number} '${ch.name}' -> $insertedUri")
                        }
                    } else {
                        Log.w(TAG, "Failed to insert channel #${ch.number} '${ch.name}' into TvProvider")
                    }
                }
            }

            // Remove channels that no longer exist on Tvheadend
            val idsToDelete = existingIds.filter { it !in syncedDbIds }
            for (channelDbId in idsToDelete) {
                try {
                    resolver.delete(TvContract.buildProgramsUriForChannel(channelDbId), null, null)
                    resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelDbId), null, null)
                    Log.i(TAG, "Deleted stale channel dbId=$channelDbId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete stale channel dbId=$channelDbId: ${e.message}")
                }
            }

            Log.i(TAG, "=== Channel sync complete: $channelCount channels synchronized ===")

            val (logoCount, programCount) = performEpgAndLogoSyncWithStats(context)

            return SyncResult(true, channelCount = channelCount, logoCount = logoCount, programCount = programCount)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            return SyncResult(false, error = e.message ?: "Unknown error during sync")
        }
    }

    fun performEpgAndLogoSync(context: Context) {
        performEpgAndLogoSyncWithStats(context)
    }

    private fun performEpgAndLogoSyncWithStats(context: Context): Pair<Int, Int> {
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
                // Delete stale programs across all synced channels in a single operation
                try {
                    resolver.delete(TvContract.Programs.CONTENT_URI, null, null)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to purge existing programs: ${e.message}")
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
                val logoResults = runBlocking(Dispatchers.IO) {
                    logoUrlMap.mapNotNull { (htspChId, logoUrl) ->
                        val dbId = channelMap[htspChId] ?: return@mapNotNull null
                        val fullUrl = if (!logoUrl.startsWith("http://") && !logoUrl.startsWith("https://")) {
                            "http://$host:${settings.httpPort}/$logoUrl"
                        } else logoUrl

                        val logoUri = TvContract.buildChannelLogoUri(dbId)
                        async {
                            try {
                                val bitmap = fetchBitmap(fullUrl)
                                if (bitmap != null) {
                                    resolver.openOutputStream(logoUri)?.use { out ->
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                    }
                                    true
                                } else false
                            } catch (_: Exception) {
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

    fun clearAllChannelsAndPrograms(context: Context) {
        try {
            val resolver = context.contentResolver
            val myPackage = context.packageName
            val inputId = TvContract.buildInputId(ComponentName(context, TvheadendInputService::class.java))

            try {
                resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Bulk delete by inputId failed: ${e.message}")
            }

            val idsToDelete = mutableListOf<Long>()
            resolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INPUT_ID),
                null, null, null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val inputIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)

                while (cursor.moveToNext()) {
                    val channelDbId = cursor.getLong(idCol)
                    val chInputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: "" else ""

                    if (chInputId.contains(myPackage, ignoreCase = true) || chInputId.isEmpty() || chInputId == inputId) {
                        idsToDelete.add(channelDbId)
                    }
                }
            }

            for (channelDbId in idsToDelete) {
                try {
                    resolver.delete(TvContract.buildProgramsUriForChannel(channelDbId), null, null)
                    resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, channelDbId), null, null)
                    Log.i(TAG, "Purged stale channel $channelDbId")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to purge channel $channelDbId: ${e.message}")
                }
            }

            Log.i(TAG, "Successfully cleared all channels and programs for package $myPackage")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing channels and programs", e)
        }
    }
}

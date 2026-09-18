package com.nmaclean.tvheadend

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages synchronization of channels, Electronic Program Guide (EPG) events, and channel logos
 * from the Tvheadend server into Android's system [TvContract] provider.
 */
@OptIn(UnstableApi::class)
object TvhSyncManager {
    private const val TAG = "TvhSyncManager"

    /**
     * Data class holding the result of a channel or EPG synchronization operation.
     *
     * @property success `true` if synchronization completed without errors; `false` otherwise.
     * @property channelCount Number of channels imported or updated.
     * @property eventCount Number of EPG guide events inserted.
     * @property error Error message detailing failure, or `null` on success.
     */
    data class SyncResult(val success: Boolean, val channelCount: Int, val eventCount: Int, val error: String? = null)

    /**
     * Performs fast synchronous channel import so channels appear immediately in Live TV.
     *
     * Clears any existing channels for this input service, fetches current channel definitions via HTSP,
     * and inserts them into Android's [TvContract.Channels] database.
     *
     * @param context Application context used to query shared preferences and content resolver.
     * @return [SyncResult] detailing the outcome of the channel import.
     */
    fun performChannelSync(context: Context): SyncResult {
        Log.i(TAG, "=== Starting fast channel sync ===")
        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort
        val user = settings.username
        val pass = settings.password

        if (host.isBlank()) {
            Log.w(TAG, "Channel sync aborted: Server host is not configured.")
            return SyncResult(false, 0, 0, "Server host is not configured.")
        }

        try {
            val rawChannels = HtspClient(host, port).use { client ->
                if (!client.connect(user, pass)) {
                    Log.e(TAG, "Channel sync failed: Authentication or connection error on $host:$port")
                    return SyncResult(false, 0, 0, "Authentication or connection failed.")
                }
                client.fetchChannels()
            }

            // Ensure channels are strictly sorted by channel number for vendor TV apps (like Sony TV) that order by row insertion/ID
            val channels = rawChannels.sortedWith(compareBy({ it.number }, { it.name }))
            Log.i(TAG, "Successfully fetched and sorted ${channels.size} channels from Tvheadend ($host:$port)")

            val resolver = context.contentResolver
            val inputId = TvContract.buildInputId(
                ComponentName(context, TvheadendInputService::class.java)
            )

            // Clear old channels for this input
            val deletedRows = resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)
            Log.i(TAG, "Cleared $deletedRows existing channel database entries for inputId: $inputId")

            for (ch in channels) {
                val streamKey = ch.id.toString()
                val values = ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, ch.number.toString())
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                    put(TvContract.Channels.COLUMN_SERVICE_ID, ch.id.toInt())
                    put(TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID, ch.number)
                    put(TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID, 1)
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, streamKey.toByteArray(Charsets.UTF_8))
                }
                val insertedUri = resolver.insert(TvContract.Channels.CONTENT_URI, values)
                Log.i(TAG, "Inserted channel '${ch.name}' (#${ch.number}, id=${ch.id}, uuid='${ch.uuid}') -> URI: $insertedUri")
            }

            Log.i(TAG, "=== Channel sync completed successfully: ${channels.size} channels imported ===")
            return SyncResult(true, channels.size, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error during channel sync", e)
            return SyncResult(false, 0, 0, e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Performs asynchronous background sync for Electronic Program Guide (EPG) events and channel logos.
     *
     * Downloads channel icons over HTTP and writes them to [TvContract.buildChannelLogoUri],
     * and inserts EPG events into [TvContract.Programs] in bulk chunks.
     *
     * @param context Application context used to resolve content URIs and open HTTP streams.
     * @return [SyncResult] detailing the outcome of the background EPG and logo sync.
     */
    fun performEpgAndLogoSync(context: Context): SyncResult {
        Log.i(TAG, "=== Starting EPG and Logo sync ===")
        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort
        val httpPort = settings.httpPort
        val user = settings.username
        val pass = settings.password

        if (host.isBlank()) {
            Log.w(TAG, "EPG/Logo sync aborted: Server host is not configured.")
            return SyncResult(false, 0, 0, "Server host is not configured.")
        }

        try {
            val (channels, events) = HtspClient(host, port).use { client ->
                if (!client.connect(user, pass)) {
                    Log.e(TAG, "EPG/Logo sync failed: Connection or authentication error")
                    return SyncResult(false, 0, 0, "Authentication or connection failed.")
                }
                val chs = client.fetchChannels()
                val evs = client.fetchEvents(timeoutMs = TvhConstants.ASYNC_METADATA_TIMEOUT_MS)
                Pair(chs, evs)
            }

            Log.i(TAG, "Background sync fetched ${channels.size} channels and ${events.size} EPG events")

            val resolver = context.contentResolver
            val inputId = TvContract.buildInputId(
                ComponentName(context, TvheadendInputService::class.java)
            )

            // Build map of HTSP channel id -> TIF database row ID by querying existing channels
            val channelMap = mutableMapOf<Long, Long>()
            val channelsUri = TvContract.buildChannelsUriForInput(inputId)
            resolver.query(channelsUri, arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_SERVICE_ID), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val serviceIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)
                while (cursor.moveToNext()) {
                    val dbId = cursor.getLong(idCol)
                    val serviceId = cursor.getLong(serviceIdCol)
                    channelMap[serviceId] = dbId
                }
            }

            Log.i(TAG, "Mapped ${channelMap.size} database channel row IDs for logo/EPG insertion")

            var downloadedLogos = 0
            for (ch in channels) {
                val dbId = channelMap[ch.id] ?: continue
                if (ch.icon.isNotEmpty()) {
                    try {
                        val targetLogoUrl = if (ch.icon.startsWith("http://", ignoreCase = true) || ch.icon.startsWith("https://", ignoreCase = true)) {
                            ch.icon
                        } else {
                            val separator = if (ch.icon.startsWith("/")) "" else "/"
                            "http://$host:$httpPort$separator${ch.icon}"
                        }

                        val logoUrl = URL(targetLogoUrl)
                        val connection = logoUrl.openConnection() as HttpURLConnection

                        if (targetLogoUrl.contains("$host:$httpPort")) {
                            if (user.isNotEmpty() && pass.isNotEmpty()) {
                                val credentials = "$user:$pass"
                                val encoded = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                connection.setRequestProperty("Authorization", "Basic $encoded")
                            }
                        }

                        connection.connect()
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            connection.inputStream.use { input ->
                                val logoUri = TvContract.buildChannelLogoUri(dbId)
                                resolver.openOutputStream(logoUri)?.use { output ->
                                    input.copyTo(output)
                                }
                            }
                            downloadedLogos++
                            Log.i(TAG, "Downloaded logo for '${ch.name}' -> $targetLogoUrl")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception downloading logo for '${ch.name}'", e)
                    }
                }
            }

            Log.i(TAG, "Finished channel logo downloads: $downloadedLogos logos saved")

            // Insert EPG programs in chunks
            var totalInsertedEpg = 0
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

                if (contentValuesList.size >= 100) {
                    val count = resolver.bulkInsert(TvContract.Programs.CONTENT_URI, contentValuesList.toTypedArray())
                    totalInsertedEpg += count
                    contentValuesList.clear()
                }
            }
            if (contentValuesList.isNotEmpty()) {
                val count = resolver.bulkInsert(TvContract.Programs.CONTENT_URI, contentValuesList.toTypedArray())
                totalInsertedEpg += count
            }

            Log.i(TAG, "=== EPG/Logo sync completed: $downloadedLogos logos, $totalInsertedEpg EPG programs inserted ===")
            return SyncResult(true, channels.size, events.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error during background EPG/logo sync", e)
            return SyncResult(false, 0, 0, e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Performs complete full sync: first channel import, then background EPG and logo sync.
     *
     * @param context Application context used for database and network operations.
     * @return [SyncResult] detailing the outcome of the complete synchronization.
     */
    fun performSync(context: Context): SyncResult {
        val channelResult = performChannelSync(context)
        if (!channelResult.success) return channelResult
        return performEpgAndLogoSync(context)
    }
}

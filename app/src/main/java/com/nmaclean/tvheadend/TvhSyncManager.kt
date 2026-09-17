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

@OptIn(UnstableApi::class)
object TvhSyncManager {
    private const val TAG = "TvhSyncManager"

    data class SyncResult(val success: Boolean, val channelCount: Int, val eventCount: Int, val error: String? = null)

    /**
     * Performs synchronous channel import (fast) so channels appear immediately in Live TV.
     */
    fun performChannelSync(context: Context): SyncResult {
        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort
        val user = settings.username
        val pass = settings.password

        if (host.isBlank()) {
            return SyncResult(false, 0, 0, "Server host is not configured.")
        }

        try {
            val client = HtspClient(host, port)
            if (!client.connect(user, pass)) {
                return SyncResult(false, 0, 0, "Authentication or connection failed.")
            }

            val channels = client.fetchChannels()
            client.disconnect()

            Log.d(TAG, "Fetched ${channels.size} channels for fast channel sync.")

            val resolver = context.contentResolver
            val inputId = TvContract.buildInputId(
                ComponentName(context, TvheadendInputService::class.java)
            )

            // Clear old channels for this input
            resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)

            for (ch in channels) {
                val streamKey = ch.id.toString()
                val values = ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, ch.number.toString())
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                    put(TvContract.Channels.COLUMN_SERVICE_ID, ch.id.toInt())
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_DVB_T)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, streamKey.toByteArray(Charsets.UTF_8))
                }
                resolver.insert(TvContract.Channels.CONTENT_URI, values)
            }

            return SyncResult(true, channels.size, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error during channel sync", e)
            return SyncResult(false, 0, 0, e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Performs asynchronous background sync for EPG events and channel logos.
     */
    fun performEpgAndLogoSync(context: Context): SyncResult {
        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort
        val httpPort = settings.httpPort
        val user = settings.username
        val pass = settings.password

        if (host.isBlank()) {
            return SyncResult(false, 0, 0, "Server host is not configured.")
        }

        try {
            val client = HtspClient(host, port)
            if (!client.connect(user, pass)) {
                return SyncResult(false, 0, 0, "Authentication or connection failed.")
            }

            val channels = client.fetchChannels()
            val events = client.fetchEvents(timeoutMs = 8000)
            client.disconnect()

            Log.d(TAG, "Background sync fetched ${channels.size} channels and ${events.size} EPG events.")

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

            // Download logos for channels that have icons
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
                            Log.d(TAG, "Successfully downloaded logo for ${ch.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception downloading logo for ${ch.name}", e)
                    }
                }
            }

            // Insert EPG programs in chunks
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
                    resolver.bulkInsert(TvContract.Programs.CONTENT_URI, contentValuesList.toTypedArray())
                    contentValuesList.clear()
                }
            }
            if (contentValuesList.isNotEmpty()) {
                resolver.bulkInsert(TvContract.Programs.CONTENT_URI, contentValuesList.toTypedArray())
            }

            return SyncResult(true, channels.size, events.size)
        } catch (e: Exception) {
            Log.e(TAG, "Error during background EPG/logo sync", e)
            return SyncResult(false, 0, 0, e.localizedMessage ?: "Unknown error")
        }
    }

    fun performSync(context: Context): SyncResult {
        val channelResult = performChannelSync(context)
        if (!channelResult.success) return channelResult
        return performEpgAndLogoSync(context)
    }
}

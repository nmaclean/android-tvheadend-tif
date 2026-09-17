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

    fun performSync(context: Context): SyncResult {
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

            // 1. Fetch channels fast (epg = 0)
            val channels = client.fetchChannels()
            // 2. Fetch EPG events asynchronously (epg = 1)
            val events = client.fetchEvents(timeoutMs = 8000)
            client.disconnect()

            Log.d(TAG, "Fetched ${channels.size} channels and ${events.size} EPG events from Tvheadend.")

            val resolver = context.contentResolver
            val inputId = TvContract.buildInputId(
                ComponentName(context, TvheadendInputService::class.java)
            )

            // Clear old channels for this input
            resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)
            val channelMap = mutableMapOf<Long, Long>()

            // Insert channels and write logos
            for (ch in channels) {
                // Use the HTSP channelId (Long) as the primary identifier for tuning, 
                // as it's what the HTSP subscribe method expects.
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

                val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values)
                uri?.lastPathSegment?.toLongOrNull()?.let { dbId ->
                    channelMap[ch.id] = dbId

                    // Download logo using the official channelIcon path or full URL from HTSP if available
                    if (ch.icon.isNotEmpty()) {
                        try {
                            val targetLogoUrl = if (ch.icon.startsWith("http://", ignoreCase = true) || ch.icon.startsWith("https://", ignoreCase = true)) {
                                ch.icon
                            } else {
                                val separator = if (ch.icon.startsWith("/")) "" else "/"
                                "http://$host:$httpPort$separator${ch.icon}"
                            }

                            Log.d(TAG, "Trying to download logo for '${ch.name}' from URL: $targetLogoUrl (raw icon field: '${ch.icon}')")

                            val logoUrl = URL(targetLogoUrl)
                            val connection = logoUrl.openConnection() as HttpURLConnection

                            // Only attach Basic Auth if it's pointing to your local Tvheadend server
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
                            } else {
                                Log.d(TAG, "Logo HTTP error for ${ch.name}: HTTP ${connection.responseCode} at $targetLogoUrl")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Exception downloading logo for ${ch.name}", e)
                        }
                    } else {
                        Log.d(TAG, "Channel '${ch.name}' has an empty icon field from HTSP.")
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
            Log.e(TAG, "Error during sync execution", e)
            return SyncResult(false, 0, 0, e.localizedMessage ?: "Unknown error")
        }
    }
}
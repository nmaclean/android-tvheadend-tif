package com.nmaclean.tvheadend.iptv.tif

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ComponentName
import android.content.ContentUris
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.nmaclean.tvheadend.iptv.model.IptvProviderData
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TifAllowlistSyncer(
    private val contentResolver: ContentResolver,
    private val inputId: String,
    private val allowlistRepository: AllowlistRepository
) {
    constructor(contentResolver: ContentResolver, inputComponent: ComponentName, allowlistRepository: AllowlistRepository) :
            this(contentResolver, try { TvContract.buildInputId(inputComponent) } catch (_: Exception) { inputComponent.flattenToString() }, allowlistRepository)

    companion object {
        const val TAG = "TifAllowlistSyncer"
        private fun logI(msg: String) { try { Log.i(TAG, msg) } catch (_: Throwable) {} }
        private fun logD(msg: String) { try { Log.d(TAG, msg) } catch (_: Throwable) {} }
        private fun logW(msg: String) { try { Log.w(TAG, msg) } catch (_: Throwable) {} }
        private fun logE(msg: String, tr: Throwable? = null) { try { Log.e(TAG, msg, tr) } catch (_: Throwable) {} }
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun sync(): Int {
        val snapshot = allowlistRepository.load()
        val allowlistChannels = snapshot.channels
        logI("Starting IPTV allowlist sync for inputId=$inputId with ${allowlistChannels.size} channels.")

        val channelsUri: Uri? = try {
            TvContract.buildChannelsUriForInput(inputId)
        } catch (_: Throwable) {
            null
        }

        // 1. Fetch existing channels for this input
        val existingIptvRows = mutableMapOf<String, Long>() // internalProviderId -> channelId
        val projection = arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)

        if (channelsUri != null) {
            try {
                contentResolver.query(channelsUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                    while (cursor.moveToNext()) {
                        if (idCol != -1 && providerIdCol != -1) {
                            val rowId = cursor.getLong(idCol)
                            val providerId = cursor.getString(providerIdCol)
                            if (providerId != null && providerId.startsWith("iptv:")) {
                                existingIptvRows[providerId] = rowId
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logW("Failed to query existing IPTV channels: ${e.message}")
            }
        }

        val ops = ArrayList<ContentProviderOperation>()
        val activeProviderIds = mutableSetOf<String>()

        allowlistChannels.forEachIndexed { index, channel ->
            val providerId = "iptv:${channel.id}"
            activeProviderIds.add(providerId)
            logD("Syncing channel [${index + 1}/${allowlistChannels.size}]: id=${channel.id}, name=${channel.name}, stream=${channel.streamUrl}, logo=${channel.logoUrl}")

            try {
                val providerData = IptvProviderData(
                    streamUrl = channel.streamUrl,
                    headers = channel.headers
                )
                val providerDataBytes = json.encodeToString(providerData).toByteArray(Charsets.UTF_8)

                val values = android.content.ContentValues().apply {
                    put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                    put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                    put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
                    put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
                    put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, (index + 1).toString())
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, providerId)
                    put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, providerDataBytes)
                    put(TvContract.Channels.COLUMN_SEARCHABLE, 1)
                    if (!channel.logoUrl.isNullOrEmpty()) {
                        put(TvContract.Channels.COLUMN_APP_LINK_POSTER_ART_URI, channel.logoUrl)
                    }
                }

                val existingRowId = existingIptvRows[providerId]
                if (existingRowId != null) {
                    val updateUri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, existingRowId)
                    ops.add(ContentProviderOperation.newUpdate(updateUri).withValues(values).build())
                } else if (channelsUri != null) {
                    ops.add(ContentProviderOperation.newInsert(channelsUri).withValues(values).build())
                }
            } catch (e: Throwable) {
                logW("Failed to prepare channel operation for ${channel.name}: ${e.message}")
            }
        }

        // 2. Prune IPTV channels no longer in allowlist
        existingIptvRows.forEach { (providerId, rowId) ->
            if (!activeProviderIds.contains(providerId)) {
                logI("Pruning removed IPTV channel providerId=$providerId, rowId=$rowId")
                try {
                    val deleteUri = ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, rowId)
                    ops.add(ContentProviderOperation.newDelete(deleteUri).build())
                } catch (_: Throwable) {}
            }
        }

        if (ops.isNotEmpty()) {
            try {
                contentResolver.applyBatch(TvContract.AUTHORITY, ops)
                logI("Successfully applied batch channel operations (${ops.size} ops).")
                try {
                    if (channelsUri != null) {
                        contentResolver.notifyChange(channelsUri, null)
                    }
                    contentResolver.notifyChange(TvContract.Channels.CONTENT_URI, null)
                } catch (_: Throwable) {}
            } catch (e: Exception) {
                logE("Failed to apply channel batch operations", e)
            }
        }

        // 3. Sync channel logos into TvContract.buildChannelLogoUri(rowId)
        if (channelsUri != null) {
            try {
                contentResolver.query(channelsUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                    while (cursor.moveToNext()) {
                        val rowId = cursor.getLong(idCol)
                        val providerId = cursor.getString(providerIdCol) ?: ""
                        if (providerId.startsWith("iptv:")) {
                            val channelIdSuffix = providerId.removePrefix("iptv:")
                            val channel = allowlistChannels.find { it.id == channelIdSuffix }
                            if (channel != null && !channel.logoUrl.isNullOrEmpty()) {
                                try {
                                    val logoUri = TvContract.buildChannelLogoUri(rowId)
                                    val url = java.net.URL(channel.logoUrl)
                                    val conn = url.openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 4000
                                    conn.readTimeout = 4000
                                    conn.inputStream.use { input ->
                                        contentResolver.openOutputStream(logoUri)?.use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    conn.disconnect()
                                    logD("Successfully loaded & cached logo for channel ${channel.name} to logoUri=$logoUri")
                                } catch (e: Exception) {
                                    logW("Failed to load logo for channel ${channel.name} from ${channel.logoUrl}: ${e.message}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logW("Error during logo sync query: ${e.message}")
            }
        }

        logI("IPTV allowlist sync completed: ${allowlistChannels.size} channels processed.")
        return allowlistChannels.size
    }
}


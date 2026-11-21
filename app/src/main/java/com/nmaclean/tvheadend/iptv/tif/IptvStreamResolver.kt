package com.nmaclean.tvheadend.iptv.tif

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.nmaclean.tvheadend.iptv.model.IptvProviderData
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import kotlinx.serialization.json.Json

data class ResolvedIptvStream(
    val streamUrl: String,
    val headers: Map<String, String>
)

object IptvStreamResolver {
    const val TAG = "IptvStreamResolver"
    private val json = Json { ignoreUnknownKeys = true }

    fun resolve(contentResolver: ContentResolver, channelUri: Uri, context: Context? = null): ResolvedIptvStream? {
        Log.i(TAG, "Resolving IPTV stream for channelUri=$channelUri")
        val projection = arrayOf(
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
            TvContract.Channels.COLUMN_DISPLAY_NAME
        )

        try {
            contentResolver.query(channelUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)
                    val providerDataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)

                    val providerId = if (providerIdCol != -1) cursor.getString(providerIdCol) else null
                    Log.d(TAG, "Resolved providerId=$providerId for uri=$channelUri")

                    val jsonStr = if (providerDataCol != -1) readProviderDataString(cursor, providerDataCol) else null
                    if (!jsonStr.isNullOrEmpty()) {
                        try {
                            val providerData = json.decodeFromString<IptvProviderData>(jsonStr)
                            if (providerData.streamUrl.isNotEmpty()) {
                                Log.i(TAG, "Successfully resolved IPTV streamUrl=${providerData.streamUrl} with ${providerData.headers.size} headers")
                                return ResolvedIptvStream(providerData.streamUrl, providerData.headers)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse IptvProviderData json: ${e.message}, trying fallback map")
                            try {
                                val map = json.decodeFromString<Map<String, String>>(jsonStr)
                                val streamUrl = map["streamUrl"]
                                if (!streamUrl.isNullOrEmpty()) {
                                    Log.i(TAG, "Successfully resolved fallback IPTV streamUrl=$streamUrl")
                                    return ResolvedIptvStream(streamUrl, emptyMap())
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving IPTV stream for uri=$channelUri", e)
        }

        if (context != null) {
            try {
                val allowlistRepo = AllowlistRepository(context.filesDir)
                val snapshot = allowlistRepo.load()
                if (snapshot.channels.isNotEmpty()) {
                    val channelIdSegment = channelUri.lastPathSegment ?: ""
                    val matchedCh = snapshot.channels.firstOrNull { it.id == channelIdSegment || it.name == channelIdSegment }
                        ?: snapshot.channels.firstOrNull()
                    if (matchedCh != null && matchedCh.streamUrl.isNotEmpty()) {
                        Log.i(TAG, "Fallback matched allowlist channel '${matchedCh.name}' -> ${matchedCh.streamUrl}")
                        return ResolvedIptvStream(matchedCh.streamUrl, matchedCh.headers)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Allowlist fallback resolution failed: ${e.message}")
            }
        }

        Log.w(TAG, "Failed to resolve IPTV stream for uri=$channelUri")
        return null
    }

    private fun readProviderDataString(cursor: Cursor, dataCol: Int): String? {
        if (dataCol == -1 || cursor.isNull(dataCol)) return null
        return try {
            when (cursor.getType(dataCol)) {
                Cursor.FIELD_TYPE_BLOB -> {
                    val bytes = cursor.getBlob(dataCol)
                    if (bytes != null && bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else null
                }
                Cursor.FIELD_TYPE_STRING -> cursor.getString(dataCol)
                else -> cursor.getString(dataCol)
            }
        } catch (_: Exception) {
            try { cursor.getString(dataCol) } catch (_: Exception) { null }
        }
    }
}


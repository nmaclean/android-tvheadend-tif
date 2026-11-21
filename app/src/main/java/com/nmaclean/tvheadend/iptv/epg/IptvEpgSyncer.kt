package com.nmaclean.tvheadend.iptv.epg

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ComponentName
import android.content.ContentValues
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository

class IptvEpgSyncer(
    private val contentResolver: ContentResolver,
    private val inputId: String,
    private val allowlistRepository: AllowlistRepository
) {
    constructor(contentResolver: ContentResolver, inputComponent: ComponentName, allowlistRepository: AllowlistRepository) :
            this(contentResolver, try { TvContract.buildInputId(inputComponent) } catch (_: Exception) { inputComponent.flattenToString() }, allowlistRepository)

    companion object {
        const val TAG = "IptvEpgSyncer"
    }

    fun syncPrograms(parseResult: EpgParseResult): Int {
        val snapshot = allowlistRepository.load()
        val allowlistChannels = snapshot.channels
        Log.i(TAG, "Starting EPG program sync for inputId=$inputId with ${allowlistChannels.size} allowlist channels and ${parseResult.programmesByChannel.size} XMLTV channels.")
        if (allowlistChannels.isEmpty() || parseResult.programmesByChannel.isEmpty()) {
            Log.w(TAG, "Allowlist channels or EPG programmes are empty. Skipped EPG sync.")
            return 0
        }

        // 1. Fetch existing IPTV channels for this input ID to map tvgId / name -> channelId
        val channelsUri: Uri? = try {
            TvContract.buildChannelsUriForInput(inputId)
        } catch (_: Throwable) {
            null
        }

        if (channelsUri == null) return 0

        val channelMap = mutableMapOf<String, Long>() // key -> channelId
        val existingRows = mutableMapOf<String, Long>() // channelIdSuffix -> rowId
        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_DISPLAY_NAME
        )

        try {
            contentResolver.query(channelsUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val providerIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID)

                while (cursor.moveToNext()) {
                    if (idCol != -1 && providerIdCol != -1) {
                        val rowId = cursor.getLong(idCol)
                        val providerId = cursor.getString(providerIdCol) ?: ""

                        if (providerId.startsWith("iptv:")) {
                            val channelIdSuffix = providerId.removePrefix("iptv:")
                            existingRows[channelIdSuffix] = rowId
                            val allowlistCh = allowlistChannels.find { it.id == channelIdSuffix }
                            if (allowlistCh != null) {
                                channelMap[allowlistCh.id.lowercase().trim()] = rowId
                                if (!allowlistCh.tvgId.isNullOrEmpty()) {
                                    channelMap[allowlistCh.tvgId.lowercase().trim()] = rowId
                                    channelMap[normalizeName(allowlistCh.tvgId)] = rowId
                                }
                                if (allowlistCh.name.isNotEmpty()) {
                                    channelMap[allowlistCh.name.lowercase().trim()] = rowId
                                    channelMap[normalizeName(allowlistCh.name)] = rowId
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query channels for EPG sync: ${e.message}")
            return 0
        }

        // Also check parseResult.channelNames (xmltvId -> displayName) to link xmltv channel IDs & display names
        for ((xmltvId, displayName) in parseResult.channelNames) {
            val lowerId = xmltvId.lowercase().trim()
            val normName = normalizeName(displayName)
            for (allowlistCh in allowlistChannels) {
                val rowId = existingRows[allowlistCh.id] ?: continue
                val matchesId = (!allowlistCh.tvgId.isNullOrEmpty() && allowlistCh.tvgId.lowercase().trim() == lowerId) ||
                        allowlistCh.id.lowercase().trim() == lowerId
                val matchesName = normalizeName(allowlistCh.name) == normName ||
                        allowlistCh.name.lowercase().trim() == displayName.lowercase().trim()

                if (matchesId || matchesName) {
                    channelMap[lowerId] = rowId
                    channelMap[xmltvId] = rowId
                    channelMap[normName] = rowId
                    channelMap[displayName.lowercase().trim()] = rowId
                }
            }
        }

        if (channelMap.isEmpty()) return 0

        val ops = ArrayList<ContentProviderOperation>()
        var insertedCount = 0

        // 2. For each matched channel, clear old programs and insert new ones
        for (entry in parseResult.programmesByChannel.entries) {
            val rawKey = entry.key
            val programmes = entry.value
            val normalizedKey = rawKey.lowercase().trim()
            val channelId = channelMap[rawKey] ?: channelMap[normalizedKey] ?: channelMap[normalizeName(rawKey)]

            if (channelId == null || channelId <= 0L) continue

            try {
                val channelProgramsUri = TvContract.buildProgramsUriForChannel(channelId)
                ops.add(ContentProviderOperation.newDelete(channelProgramsUri).build())
            } catch (_: Throwable) {}

            for (prog in programmes) {
                try {
                    val values = ContentValues().apply {
                        put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId)
                        put(TvContract.Programs.COLUMN_TITLE, prog.title)
                        if (!prog.subTitle.isNullOrEmpty()) {
                            put(TvContract.Programs.COLUMN_EPISODE_TITLE, prog.subTitle)
                        }
                        if (!prog.description.isNullOrEmpty()) {
                            put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, prog.description)
                        }
                        if (!prog.category.isNullOrEmpty()) {
                            put(TvContract.Programs.COLUMN_CANONICAL_GENRE, prog.category)
                        }
                        put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, prog.startTimeEpochMs)
                        put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, prog.endTimeEpochMs)
                        if (!prog.posterArtUri.isNullOrEmpty()) {
                            put(TvContract.Programs.COLUMN_POSTER_ART_URI, prog.posterArtUri)
                        }
                    }
                    ops.add(ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI).withValues(values).build())
                    insertedCount++
                } catch (_: Throwable) {}
            }
        }

        if (ops.isNotEmpty()) {
            val chunkSize = 400
            for (i in ops.indices step chunkSize) {
                val chunk = ArrayList(ops.subList(i, minOf(i + chunkSize, ops.size)))
                try {
                    contentResolver.applyBatch(TvContract.AUTHORITY, chunk)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply EPG batch chunk: ${e.message}")
                }
            }
            Log.i(TAG, "Successfully synced $insertedCount IPTV EPG programmes to TvContract.Programs.")
            try {
                contentResolver.notifyChange(TvContract.Programs.CONTENT_URI, null)
            } catch (_: Throwable) {}
        }

        return insertedCount
    }

    private fun normalizeName(name: String): String {
        return name.lowercase().replace(Regex("[^\\w]"), "")
    }
}

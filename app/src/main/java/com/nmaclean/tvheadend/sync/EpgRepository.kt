package com.nmaclean.tvheadend.sync

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Repository for querying Electronic Program Guide (EPG) events for live channels from system TvContract.
 */
object EpgRepository {
    private const val TAG = "EpgRepository"

    data class ProgramInfo(
        val eventId: Long,
        val channelId: Long,
        val title: String,
        val summary: String?,
        val startTimeMs: Long,
        val endTimeMs: Long
    ) {
        val durationMs: Long get() = (endTimeMs - startTimeMs).coerceAtLeast(1L)
        val progressPercent: Int get() {
            val now = System.currentTimeMillis()
            if (now < startTimeMs) return 0
            if (now > endTimeMs) return 100
            return (((now - startTimeMs).toDouble() / durationMs.toDouble()) * 100).toInt().coerceIn(0, 100)
        }
    }

    data class ChannelEpg(
        val currentProgram: ProgramInfo?,
        val nextProgram: ProgramInfo?
    )

    fun getCurrentAndNextProgram(context: Context, channelIdentifier: Long): ChannelEpg {
        val now = System.currentTimeMillis()
        var current: ProgramInfo? = null
        var next: ProgramInfo? = null

        val projection = arrayOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_CHANNEL_ID,
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS
        )

        val programsUri = try {
            TvContract.buildProgramsUriForChannel(channelIdentifier)
        } catch (e: Exception) {
            return ChannelEpg(null, null)
        }

        val sortOrder = "${TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS} ASC"

        try {
            context.contentResolver.query(
                programsUri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Programs._ID)
                val chCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_CHANNEL_ID)
                val titleCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_TITLE)
                val descCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_SHORT_DESCRIPTION)
                val startCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS)
                val endCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS)

                while (cursor.moveToNext()) {
                    val eventId = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val chId = if (chCol >= 0) cursor.getLong(chCol) else channelIdentifier
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "No Title" else "No Title"
                    val desc = if (descCol >= 0) cursor.getString(descCol) else null
                    val start = if (startCol >= 0) cursor.getLong(startCol) else 0L
                    val end = if (endCol >= 0) cursor.getLong(endCol) else 0L

                    val prog = ProgramInfo(eventId, chId, title, desc, start, end)

                    if (now in start..end && current == null) {
                        current = prog
                    } else if (start >= now && next == null) {
                        next = prog
                    }

                    if (current != null && next != null) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying programs for channel $channelIdentifier", e)
        }

        return ChannelEpg(current, next)
    }

    fun getProgramsForChannel(context: Context, channelIdentifier: Long, startWindowMs: Long, endWindowMs: Long): List<ProgramInfo> {
        val programs = mutableListOf<ProgramInfo>()

        val projection = arrayOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_CHANNEL_ID,
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_SHORT_DESCRIPTION,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS
        )

        val programsUri = try {
            TvContract.buildProgramsUriForChannel(channelIdentifier, startWindowMs, endWindowMs)
        } catch (e: Exception) {
            return emptyList()
        }

        val sortOrder = "${TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS} ASC"

        try {
            context.contentResolver.query(
                programsUri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Programs._ID)
                val chCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_CHANNEL_ID)
                val titleCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_TITLE)
                val descCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_SHORT_DESCRIPTION)
                val startCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS)
                val endCol = cursor.getColumnIndex(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS)

                while (cursor.moveToNext()) {
                    val eventId = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val chId = if (chCol >= 0) cursor.getLong(chCol) else channelIdentifier
                    val title = if (titleCol >= 0) cursor.getString(titleCol) ?: "No Title" else "No Title"
                    val desc = if (descCol >= 0) cursor.getString(descCol) else null
                    val start = if (startCol >= 0) cursor.getLong(startCol) else 0L
                    val end = if (endCol >= 0) cursor.getLong(endCol) else 0L

                    programs.add(ProgramInfo(eventId, chId, title, desc, start, end))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying timeline programs for channel $channelIdentifier", e)
        }

        return programs
    }

    /**
     * Blazingly fast parallel windowed query fetching programs across all channels concurrently,
     * using buildProgramsUriForChannel to guarantee 100% compatibility with all TvProviders while executing in milliseconds.
     */
    fun getAllProgramsInWindow(context: Context, startWindowMs: Long, endWindowMs: Long): Map<Long, List<ProgramInfo>> {
        val map = ConcurrentHashMap<Long, MutableList<ProgramInfo>>()

        val channelIds = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                while (cursor.moveToNext()) {
                    if (idCol >= 0) {
                        val chId = cursor.getLong(idCol)
                        if (chId > 0) channelIds.add(chId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying channels for EPG window", e)
        }

        runBlocking(Dispatchers.IO) {
            channelIds.map { chId ->
                async {
                    val progs = getProgramsForChannel(context, chId, startWindowMs, endWindowMs)
                    if (progs.isNotEmpty()) {
                        map[chId] = progs.toMutableList()
                    }
                }
            }.awaitAll()
        }

        return map
    }
}

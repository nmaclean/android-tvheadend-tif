package com.nmaclean.tvheadend.ui

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.htsp.HtspClient
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhRecording
import com.nmaclean.tvheadend.model.TvhSeriesRule
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.EpgRepository
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * UI data repository and pure layout/filtering helpers for the Compose TV launcher screens.
 */
object TvhUiRepository {
    private const val TAG = "TvhUiRepository"

    val CHANNEL_CATEGORIES = listOf(
        "All Channels",
        "Favourites",
        "Entertainment",
        "News",
        "Sport",
        "Movies",
        "Kids"
    )

    private val timeFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    fun formatTimeRange(startMs: Long, endMs: Long): String {
        if (startMs <= 0L || endMs <= 0L) return ""
        val fmt = timeFormat.get()!!
        return "${fmt.format(Date(startMs))} - ${fmt.format(Date(endMs))}"
    }

    /**
     * Computes horizontal pixel-independent Dp offset for the "NOW" vertical tracker line on the EPG grid.
     */
    fun computeNowOffsetDp(nowMs: Long, windowStartMs: Long, dpPerMinute: Float): Float {
        val deltaMin = ((nowMs - windowStartMs).coerceAtLeast(0L)) / 60_000f
        return deltaMin * dpPerMinute
    }

    /**
     * Computes clamped cell width in Dp for a program block clipped to `[windowStartMs, windowEndMs]`.
     */
    fun computeProgramWidthDp(
        startMs: Long,
        endMs: Long,
        windowStartMs: Long,
        windowEndMs: Long,
        dpPerMinute: Float,
        minWidthDp: Float = 84f
    ): Float {
        val clampedStart = maxOf(startMs, windowStartMs)
        val clampedEnd = minOf(endMs, windowEndMs)
        val durationMin = ((clampedEnd - clampedStart).coerceAtLeast(60_000L)) / 60_000f
        return maxOf(minWidthDp, durationMin * dpPerMinute)
    }

    /**
     * Filters channels by category pill using channel metadata and currently airing program info.
     */
    fun filterChannelsByCategory(
        channels: List<TvhChannel>,
        category: String,
        currentEpg: Map<Long, EpgRepository.ChannelEpg>,
        favoriteChannelIds: Set<Long> = emptySet()
    ): List<TvhChannel> {
        return when (category) {
            "All Channels" -> channels
            "Favourites" -> {
                if (favoriteChannelIds.isNotEmpty()) {
                    channels.filter { it.id in favoriteChannelIds }
                } else {
                    channels.take(12)
                }
            }
            "News" -> channels.filter { matchesKeywords(it, currentEpg[it.id], listOf("news", "bbc news", "sky news", "cnn", "gb news", "parliament", "weather")) }
            "Sport" -> channels.filter { matchesKeywords(it, currentEpg[it.id], listOf("sport", "football", "match", "racing", "f1", "cricket", "rugby", "tennis", "golf")) }
            "Movies" -> channels.filter { matchesKeywords(it, currentEpg[it.id], listOf("film", "movie", "cinema", "tcm")) }
            "Kids" -> channels.filter { matchesKeywords(it, currentEpg[it.id], listOf("cbbc", "cbeebies", "kids", "cartoon", "disney", "nick", "citv")) }
            "Entertainment" -> channels.filter {
                matchesKeywords(it, currentEpg[it.id], listOf("bbc one", "bbc two", "itv", "channel 4", "channel 5", "drama", "comedy", "e4", "more4", "dave", "gold"))
            }
            else -> channels
        }
    }

    private fun matchesKeywords(
        channel: TvhChannel,
        epg: EpgRepository.ChannelEpg?,
        keywords: List<String>
    ): Boolean {
        val nameLower = channel.name.lowercase(Locale.getDefault())
        val progLower = epg?.currentProgram?.title?.lowercase(Locale.getDefault()) ?: ""
        val summaryLower = epg?.currentProgram?.summary?.lowercase(Locale.getDefault()) ?: ""
        return keywords.any { kw ->
            nameLower.contains(kw) || progLower.contains(kw) || summaryLower.contains(kw)
        }
    }

    suspend fun loadChannels(context: Context): List<TvhChannel> = withContext(Dispatchers.IO) {
        val cached = ChannelRegistry.getAllChannels()
        if (cached.isNotEmpty()) return@withContext cached

        val fromTvProvider = queryChannelsFromTvProvider(context)
        if (fromTvProvider.isNotEmpty()) {
            ChannelRegistry.updateChannels(fromTvProvider)
            return@withContext fromTvProvider
        }

        val settings = TvhSettings(context)
        if (settings.host.isBlank()) return@withContext emptyList()

        try {
            val client = HtspClient(settings.host, settings.htspPort)
            if (client.connect(settings.username, settings.password)) {
                val fetched = client.fetchChannels()
                client.disconnect()
                if (fetched.isNotEmpty()) {
                    ChannelRegistry.updateChannels(fetched)
                }
                return@withContext fetched
            }
            client.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load channels via HTSP: ${e.message}")
        }
        emptyList()
    }

    private fun queryChannelsFromTvProvider(context: Context): List<TvhChannel> {
        val result = mutableListOf<TvhChannel>()
        try {
            val projection = arrayOf(
                TvContract.Channels._ID,
                TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                TvContract.Channels.COLUMN_INPUT_ID
            )
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val nameCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                val numCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                val inputCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                while (cursor.moveToNext()) {
                    val rowId = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val name = if (nameCol >= 0) cursor.getString(nameCol) ?: "Channel" else "Channel"
                    val num = if (numCol >= 0) cursor.getString(numCol)?.toIntOrNull() ?: 0 else 0
                    val providerId = if (dataCol >= 0) {
                        try {
                            when (cursor.getType(dataCol)) {
                                android.database.Cursor.FIELD_TYPE_BLOB -> {
                                    val b = cursor.getBlob(dataCol)
                                    if (b != null) String(b, StandardCharsets.UTF_8).trim() else ""
                                }
                                else -> cursor.getString(dataCol)?.trim() ?: ""
                            }
                        } catch (_: Exception) {
                            ""
                        }
                    } else ""
                    val htspId = providerId.toLongOrNull() ?: rowId
                    val inputId = if (inputCol >= 0) cursor.getString(inputCol) ?: "" else ""
                    result.add(
                        TvhChannel(
                            id = htspId,
                            uuid = providerId.ifEmpty { htspId.toString() },
                            name = name,
                            number = num,
                            icon = "",
                            inputId = inputId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed querying TvContract.Channels: ${e.message}")
        }
        return result.sortedWith(compareBy({ it.number }, { it.name }))
    }

    suspend fun loadCurrentEpgMap(
        context: Context,
        channels: List<TvhChannel>
    ): Map<Long, EpgRepository.ChannelEpg> = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext emptyMap()
        val pairs = channels.map { ch ->
            async {
                ch.id to EpgRepository.getCurrentAndNextProgram(context, ch.id)
            }
        }.awaitAll()
        pairs.toMap()
    }

    suspend fun loadGuideWindow(
        context: Context,
        windowStartMs: Long,
        windowEndMs: Long
    ): Map<Long, List<EpgRepository.ProgramInfo>> = withContext(Dispatchers.IO) {
        EpgRepository.getAllProgramsInWindow(context, windowStartMs, windowEndMs)
    }

    suspend fun loadDvrState(
        context: Context
    ): Pair<List<TvhRecording>, List<TvhSeriesRule>> = withContext(Dispatchers.IO) {
        val settings = TvhSettings(context)
        if (settings.host.isBlank()) return@withContext emptyList<TvhRecording>() to emptyList()
        try {
            val client = HtspClient(settings.host, settings.htspPort)
            if (client.connect(settings.username, settings.password)) {
                val state = client.fetchDvrState()
                client.disconnect()
                return@withContext state
            }
            client.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load DVR state: ${e.message}")
        }
        emptyList<TvhRecording>() to emptyList()
    }

    suspend fun scheduleRecording(context: Context, eventId: Long): Boolean = withContext(Dispatchers.IO) {
        withHtspClient(context) { it.scheduleDvrEvent(eventId) } ?: false
    }

    suspend fun deleteRecording(context: Context, dvrId: Long): Boolean = withContext(Dispatchers.IO) {
        withHtspClient(context) { it.deleteDvrEntry(dvrId) } ?: false
    }

    suspend fun addSeriesRule(context: Context, title: String, channelId: Long?): Boolean = withContext(Dispatchers.IO) {
        withHtspClient(context) { it.addSeriesRule(title, channelId) } ?: false
    }

    suspend fun deleteSeriesRule(context: Context, ruleId: String): Boolean = withContext(Dispatchers.IO) {
        withHtspClient(context) { it.deleteSeriesRule(ruleId) } ?: false
    }

    fun buildRecordingStreamUrl(context: Context, dvrId: Long): String {
        val settings = TvhSettings(context)
        val user = settings.username
        val pass = settings.password
        val auth = if (user.isNotBlank()) "$user:$pass@" else ""
        return "http://$auth${settings.host}:${settings.httpPort}/dvrfile/$dvrId"
    }

    private inline fun <T> withHtspClient(context: Context, block: (HtspClient) -> T): T? {
        val settings = TvhSettings(context)
        if (settings.host.isBlank()) return null
        val client = HtspClient(settings.host, settings.htspPort)
        return try {
            if (client.connect(settings.username, settings.password)) {
                block(client)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTSP action failed: ${e.message}")
            null
        } finally {
            client.disconnect()
        }
    }
}

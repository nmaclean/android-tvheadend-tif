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
    private const val EPG_CACHE_TTL_MS = 120_000L

    @Volatile
    private var cachedCurrentEpg: Map<Long, EpgRepository.ChannelEpg> = emptyMap()
    @Volatile
    private var cachedCurrentEpgAtMs: Long = 0L

    @Volatile
    private var cachedGuidePrograms: Map<Long, List<EpgRepository.ProgramInfo>> = emptyMap()
    @Volatile
    private var cachedGuideAtMs: Long = 0L

    fun invalidateCaches() {
        cachedCurrentEpg = emptyMap()
        cachedCurrentEpgAtMs = 0L
        cachedGuidePrograms = emptyMap()
        cachedGuideAtMs = 0L
    }

    private fun isTvheadendTifEnabled(context: Context): Boolean {
        val enabledPref = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean("tvheadend_tif_enabled", true)
        return enabledPref && com.nmaclean.tvheadend.model.TvhTifSettings(context).host.isNotBlank()
    }

    val CHANNEL_CATEGORIES = listOf(
        "All Channels",
        "Favourites",
        "Entertainment",
        "News",
        "Sport",
        "Movies",
        "Kids"
    )

    enum class EpgGenre(
        val label: String,
        val cardBgHex: Long,
        val cardFocusedHex: Long,
        val accentHex: Long
    ) {
        SPORT("SPORT", 0xFF102E2A, 0xFF16403A, 0xFF22C55E),
        NEWS("NEWS", 0xFF2E1722, 0xFF40202F, 0xFFEF4444),
        MOVIES("MOVIES", 0xFF231938, 0xFF322450, 0xFFA855F7),
        KIDS("KIDS", 0xFF2D2414, 0xFF40321B, 0xFFF59E0B),
        DRAMA("ENTERTAINMENT", 0xFF182442, 0xFF22335C, 0xFF60A5FA),
        DOCUMENTARY("FACTUAL", 0xFF12283A, 0xFF1A3852, 0xFF38BDF8),
        GENERAL("", 0xFF162238, 0xFF1D2D49, 0x00000000)
    }

    fun classifyEpgGenre(
        title: String,
        summary: String? = null,
        channelName: String = "",
        category: String? = null
    ): EpgGenre {
        val text = "${title.lowercase(Locale.getDefault())} ${summary?.lowercase(Locale.getDefault()) ?: ""} ${channelName.lowercase(Locale.getDefault())} ${category?.lowercase(Locale.getDefault()) ?: ""}"
        return when {
            listOf("sport", "football", "match of the day", "premier league", "f1", "formula 1", "racing", "rugby", "cricket", "tennis", "golf", "snooker", "athletics", "boxing", "wimbledon").any { text.contains(it) } -> EpgGenre.SPORT
            listOf("news", "weather", "parliament", "question time", "newsnight", "politics", "breakfast", "pmqs", "world at one").any { text.contains(it) } -> EpgGenre.NEWS
            listOf("film", "movie", "cinema", "premiere", "james bond", "thriller").any { text.contains(it) } -> EpgGenre.MOVIES
            listOf("cbeebies", "cbbc", "kids", "cartoon", "bluey", "peppa", "bing", "hey duggee", "octonauts", "disney", "citv").any { text.contains(it) } -> EpgGenre.KIDS
            listOf("planet earth", "horizon", "grand designs", "countryfile", "antiques", "history", "nature", "wildlife", "documentary", "24 hours in", "panorama", "dispatches").any { text.contains(it) } -> EpgGenre.DOCUMENTARY
            listOf("eastenders", "coronation street", "emmerdale", "hollyoaks", "the chase", "pointless", "university challenge", "only connect", "strictly", "the one show", "drama", "comedy", "quiz", "eggheads").any { text.contains(it) } -> EpgGenre.DRAMA
            else -> EpgGenre.GENERAL
        }
    }

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

    fun loadChannelsSync(context: Context): List<TvhChannel> {
        if (!isTvheadendTifEnabled(context)) {
            invalidateCaches()
            return emptyList()
        }
        val fromProvider = queryChannelsFromTvProvider(context)
        if (fromProvider.isNotEmpty()) return fromProvider
        val registryChannels = ChannelRegistry.getAllChannels()
        if (registryChannels.isNotEmpty()) return registryChannels
        return emptyList()
    }

    suspend fun loadChannels(context: Context): List<TvhChannel> = withContext(Dispatchers.IO) {
        val fromTvProvider = queryChannelsFromTvProvider(context)
        if (fromTvProvider.isNotEmpty()) {
            ChannelRegistry.updateChannels(fromTvProvider)
            return@withContext fromTvProvider
        }

        val cached = ChannelRegistry.getAllChannels()
        if (cached.isNotEmpty()) return@withContext cached

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
                    val streamKey = providerId.ifEmpty { rowId.toString() }
                    val inputId = if (inputCol >= 0) cursor.getString(inputCol) ?: "" else ""
                    val logoUri = TvContract.buildChannelLogoUri(rowId).toString()
                    result.add(
                        TvhChannel(
                            id = rowId,
                            uuid = streamKey,
                            name = name,
                            number = num,
                            icon = logoUri,
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

    /**
     * Derives current and next program info per channel from an already-loaded guide window map
     * in memory, avoiding N separate ContentResolver queries.
     */
    fun deriveCurrentEpgFromWindow(
        channels: List<TvhChannel>,
        guideMap: Map<Long, List<EpgRepository.ProgramInfo>>,
        nowMs: Long = System.currentTimeMillis()
    ): Map<Long, EpgRepository.ChannelEpg> {
        if (channels.isEmpty() || guideMap.isEmpty()) return emptyMap()
        val result = HashMap<Long, EpgRepository.ChannelEpg>(channels.size)
        for (ch in channels) {
            val progs = guideMap[ch.id] ?: ch.uuid.toLongOrNull()?.let { guideMap[it] } ?: continue
            var current: EpgRepository.ProgramInfo? = null
            var next: EpgRepository.ProgramInfo? = null
            for (p in progs) {
                if (nowMs >= p.startTimeMs && nowMs < p.endTimeMs) {
                    if (current == null) current = p
                } else if (p.startTimeMs > nowMs) {
                    if (next == null || p.startTimeMs < next.startTimeMs) {
                        next = p
                    }
                }
            }
            if (current != null || next != null) {
                result[ch.id] = EpgRepository.ChannelEpg(currentProgram = current, nextProgram = next)
            }
        }
        return result
    }

    suspend fun loadCurrentEpgMap(
        context: Context,
        channels: List<TvhChannel>,
        forceRefresh: Boolean = false
    ): Map<Long, EpgRepository.ChannelEpg> = withContext(Dispatchers.IO) {
        if (!isTvheadendTifEnabled(context)) {
            cachedCurrentEpg = emptyMap()
            cachedCurrentEpgAtMs = 0L
            return@withContext emptyMap()
        }
        if (channels.isEmpty()) return@withContext emptyMap()
        val now = System.currentTimeMillis()
        if (!forceRefresh &&
            cachedCurrentEpg.isNotEmpty() &&
            (now - cachedCurrentEpgAtMs) < EPG_CACHE_TTL_MS &&
            channels.all { cachedCurrentEpg.containsKey(it.id) || it.uuid.toLongOrNull()?.let(cachedCurrentEpg::containsKey) == true }
        ) {
            return@withContext cachedCurrentEpg
        }
        val windowMap = loadGuideWindow(
            context = context,
            channels = channels,
            windowStartMs = now - 30 * 60_000L,
            windowEndMs = now + 12 * 60 * 60_000L,
            forceRefresh = forceRefresh
        )
        val result = deriveCurrentEpgFromWindow(channels, windowMap, now)
        if (result.isNotEmpty()) {
            cachedCurrentEpg = result
            cachedCurrentEpgAtMs = System.currentTimeMillis()
        }
        result
    }

    suspend fun loadGuideWindow(
        context: Context,
        channels: List<TvhChannel>,
        windowStartMs: Long,
        windowEndMs: Long,
        forceRefresh: Boolean = false
    ): Map<Long, List<EpgRepository.ProgramInfo>> = withContext(Dispatchers.IO) {
        if (!isTvheadendTifEnabled(context)) {
            cachedGuidePrograms = emptyMap()
            cachedGuideAtMs = 0L
            return@withContext emptyMap()
        }
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedGuidePrograms.isNotEmpty() && (now - cachedGuideAtMs) < EPG_CACHE_TTL_MS) {
            return@withContext cachedGuidePrograms
        }
        val rawByDbId = EpgRepository.getAllProgramsInWindow(context, windowStartMs, windowEndMs)
        val htspToDb = buildHtspToDbIdMap(context, channels)
        val dbToHtsp = htspToDb.entries.associate { (htspId, dbId) -> dbId to htspId }

        val mapped = mutableMapOf<Long, List<EpgRepository.ProgramInfo>>()
        for ((dbId, list) in rawByDbId) {
            val htspId = dbToHtsp[dbId] ?: dbId
            mapped[htspId] = list
        }

        if (mapped.isEmpty()) {
            // Fallback to direct HTSP EPG fetch when TvContract.Programs is not yet populated
            com.nmaclean.tvheadend.htsp.HtspConnectionPool.withClient(context, TAG) { client ->
                val events = client.fetchEvents(filterStartMs = windowStartMs, filterEndMs = windowEndMs)
                val grouped = events.groupBy { it.channelId }
                for ((chId, progs) in grouped) {
                    mapped[chId] = progs.sortedBy { it.startTime }.map { p ->
                        EpgRepository.ProgramInfo(
                            eventId = p.eventId,
                            channelId = p.channelId,
                            title = p.title,
                            summary = p.summary,
                            startTimeMs = p.startTime,
                            endTimeMs = p.stopTime
                        )
                    }
                }
            }
        }
        if (mapped.isNotEmpty()) {
            cachedGuidePrograms = mapped
            cachedGuideAtMs = System.currentTimeMillis()
        }
        mapped
    }

    private fun buildHtspToDbIdMap(context: Context, channels: List<TvhChannel>): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        val byUuid = channels.associateBy { it.uuid }
        val byId = channels.associateBy { it.id }
        val byServiceId = channels.associateBy { (it.id and 0x7FFFFFFFL).toInt() }
        val byNumber = channels.associateBy { it.number }

        try {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_SERVICE_ID,
                    TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                val numCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                val svcCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)
                val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                while (cursor.moveToNext()) {
                    val dbId = if (idCol >= 0) cursor.getLong(idCol) else continue
                    val providerData = if (dataCol >= 0) {
                        try {
                            when (cursor.getType(dataCol)) {
                                android.database.Cursor.FIELD_TYPE_BLOB -> {
                                    cursor.getBlob(dataCol)?.let { String(it, StandardCharsets.UTF_8).trim() } ?: ""
                                }
                                else -> cursor.getString(dataCol)?.trim() ?: ""
                            }
                        } catch (_: Exception) {
                            ""
                        }
                    } else ""
                    val svcId = if (svcCol >= 0) cursor.getInt(svcCol) else 0
                    val num = if (numCol >= 0) cursor.getString(numCol)?.toIntOrNull() ?: 0 else 0

                    val matched = providerData.toLongOrNull()?.let { byId[it] }
                        ?: byUuid[providerData]
                        ?: byServiceId[svcId]
                        ?: byNumber[num]
                    if (matched != null) {
                        result[matched.id] = dbId
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed building HTSP-to-DbId channel map: ${e.message}")
        }
        return result
    }

    data class TvInputEntry(
        val inputInfo: android.media.tv.TvInputInfo,
        val label: String,
        val channelCount: Int
    )

    fun loadTvInputs(context: Context): List<TvInputEntry> {
        val tvInputManager = context.getSystemService(Context.TV_INPUT_SERVICE) as android.media.tv.TvInputManager
        val resolver = context.contentResolver
        val rawItems = mutableListOf<TvInputEntry>()
        for (input in tvInputManager.tvInputList) {
            if (input.isPassthroughInput) continue
            var count = 0
            try {
                val uri = TvContract.buildChannelsUriForInput(input.id)
                resolver.query(uri, arrayOf(TvContract.Channels._ID), null, null, null)?.use { cursor ->
                    count = cursor.count
                }
            } catch (_: Exception) {}
            val label = input.loadLabel(context)?.toString()?.trim()?.ifEmpty { input.id } ?: input.id
            rawItems.add(TvInputEntry(input, label, count))
        }
        return rawItems
            .groupBy { it.label }
            .values
            .map { group -> group.maxByOrNull { it.channelCount } ?: group.first() }
    }
}

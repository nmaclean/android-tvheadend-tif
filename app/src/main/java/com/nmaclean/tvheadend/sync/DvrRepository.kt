package com.nmaclean.tvheadend.sync

import android.content.Context
import com.nmaclean.tvheadend.htsp.HtspConnectionPool
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhRecording
import com.nmaclean.tvheadend.model.TvhRecordingStatus
import com.nmaclean.tvheadend.model.TvhSeriesRule
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.model.computeWatchedPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Pure reducer for HTSP DVR & AutoRec messages (`dvrEntryAdd/Update/Delete`, `autorecEntryAdd/Update/Delete`).
 */
class DvrStateReducer {
    private val recordingsById = linkedMapOf<Long, TvhRecording>()
    private val rulesById = linkedMapOf<String, TvhSeriesRule>()

    fun applyMessage(msg: Map<*, *>, channelMap: Map<Long, TvhChannel> = emptyMap()): Boolean {
        val method = msg["method"] as? String ?: return false
        return when (method) {
            "dvrEntryAdd", "dvrEntryUpdate" -> {
                val id = (msg["id"] as? Number)?.toLong() ?: return false
                val existing = recordingsById[id]
                val uuid = msg["idStr"] as? String ?: existing?.uuid ?: ""
                val channelId = (msg["channel"] as? Number)?.toLong() ?: existing?.channelId ?: 0L
                val ch = channelMap[channelId]
                val channelName = msg["channelName"] as? String ?: ch?.name ?: existing?.channelName ?: "Channel $channelId"
                val channelIcon = ch?.icon ?: existing?.channelIcon ?: ""
                val title = msg["title"] as? String ?: existing?.title ?: "Recording"
                val subtitle = msg["subtitle"] as? String ?: existing?.subtitle ?: ""
                val description = msg["description"] as? String ?: msg["summary"] as? String ?: existing?.description ?: ""
                val startMs = (msg["start"] as? Number)?.toLong()?.times(1000L) ?: existing?.startTimeMs ?: 0L
                val stopMs = (msg["stop"] as? Number)?.toLong()?.times(1000L) ?: existing?.stopTimeMs ?: 0L
                val eventId = (msg["eventId"] as? Number)?.toLong() ?: existing?.eventId ?: 0L
                val playCount = (msg["playcount"] as? Number)?.toInt() ?: existing?.playCount ?: 0
                val playPosMs = (msg["playposition"] as? Number)?.toLong()?.times(1000L) ?: existing?.resumePositionMs ?: 0L
                val errorStr = (msg["error"] as? String)?.trim() ?: existing?.error ?: ""
                val hasError = errorStr.isNotBlank() && !errorStr.equals("OK", ignoreCase = true)
                val status = (msg["state"] as? String)?.lowercase()?.let { stateStr ->
                    when (stateStr) {
                        "scheduled" -> TvhRecordingStatus.SCHEDULED
                        "recording" -> TvhRecordingStatus.RECORDING
                        "missed", "invalid", "completederror", "empty" -> TvhRecordingStatus.MISSED
                        else -> if (hasError) TvhRecordingStatus.MISSED else TvhRecordingStatus.RECORDED
                    }
                } ?: existing?.status ?: TvhRecordingStatus.RECORDED
                val durationSec = ((stopMs - startMs).coerceAtLeast(0L)) / 1000L
                val watched = computeWatchedPercent(playCount, playPosMs / 1000L, durationSec)
                val imageUrl = msg["image"] as? String ?: existing?.imageUrl ?: channelIcon

                recordingsById[id] = TvhRecording(
                    id = id,
                    uuid = uuid,
                    eventId = eventId,
                    channelId = channelId,
                    channelName = channelName,
                    channelIcon = channelIcon,
                    title = title,
                    subtitle = subtitle,
                    description = description,
                    startTimeMs = startMs,
                    stopTimeMs = stopMs,
                    status = status,
                    watchedPercent = watched,
                    resumePositionMs = playPosMs,
                    playCount = playCount,
                    imageUrl = imageUrl,
                    error = errorStr
                )
                true
            }
            "dvrEntryDelete" -> {
                val id = (msg["id"] as? Number)?.toLong() ?: return false
                recordingsById.remove(id) != null
            }
            "autorecEntryAdd", "autorecEntryUpdate" -> {
                val id = msg["id"]?.toString() ?: return false
                val existing = rulesById[id]
                val title = msg["title"] as? String ?: msg["name"] as? String ?: existing?.title ?: "Series Rule"
                val name = msg["name"] as? String ?: existing?.name ?: title
                val channelId = (msg["channel"] as? Number)?.toLong() ?: existing?.channelId ?: 0L
                val channelName = channelMap[channelId]?.name ?: existing?.channelName ?: if (channelId > 0L) "Channel $channelId" else "Any Channel"
                val enabled = (msg["enabled"] as? Number)?.let { it.toInt() != 0 } ?: existing?.enabled ?: true
                val fulltext = (msg["fulltext"] as? Number)?.let { it.toInt() != 0 } ?: existing?.fulltext ?: false
                val comment = msg["comment"] as? String ?: existing?.comment ?: ""

                rulesById[id] = TvhSeriesRule(
                    id = id,
                    title = title,
                    name = name,
                    channelId = channelId,
                    channelName = channelName,
                    enabled = enabled,
                    fulltext = fulltext,
                    comment = comment
                )
                true
            }
            "autorecEntryDelete" -> {
                val id = msg["id"]?.toString() ?: return false
                rulesById.remove(id) != null
            }
            else -> false
        }
    }

    fun snapshotRecordings(): List<TvhRecording> = recordingsById.values.sortedByDescending { it.startTimeMs }

    fun snapshotRules(): List<TvhSeriesRule> = rulesById.values.sortedBy { it.title }
}

/**
 * Reactive HTSP DVR repository managing recordings, active/scheduled timers, AutoRec series rules,
 * and local resume offsets.
 */
object DvrRepository {
    private const val TAG = "DvrRepository"
    private const val RESUME_PREFS = "tvh_dvr_resume"
    private const val CACHE_TTL_MS = 45_000L

    @Volatile
    private var lastRefreshMs = 0L

    private val _recordings = MutableStateFlow<List<TvhRecording>>(emptyList())
    val recordings: StateFlow<List<TvhRecording>> = _recordings.asStateFlow()

    private val _seriesRules = MutableStateFlow<List<TvhSeriesRule>>(emptyList())
    val seriesRules: StateFlow<List<TvhSeriesRule>> = _seriesRules.asStateFlow()

    private val _scheduledEventIds = MutableStateFlow<Set<Long>>(emptySet())
    val scheduledEventIds: StateFlow<Set<Long>> = _scheduledEventIds.asStateFlow()

    private val _seriesTitles = MutableStateFlow<Set<String>>(emptySet())
    val seriesTitles: StateFlow<Set<String>> = _seriesTitles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun updateDerivedFlows(context: Context?, recs: List<TvhRecording>, rules: List<TvhSeriesRule>) {
        val mergedRecs = if (context != null) {
            recs.map { rec ->
                val localPosMs = getResumePosition(context, rec.id)
                if (localPosMs > rec.resumePositionMs && rec.stopTimeMs > rec.startTimeMs) {
                    val durationSec = (rec.stopTimeMs - rec.startTimeMs) / 1000L
                    val pct = computeWatchedPercent(rec.playCount, localPosMs / 1000L, durationSec)
                    rec.copy(resumePositionMs = localPosMs, watchedPercent = pct)
                } else {
                    rec
                }
            }
        } else recs

        _recordings.value = mergedRecs
        _seriesRules.value = rules
        _scheduledEventIds.value = mergedRecs
            .filter {
                (it.status == TvhRecordingStatus.SCHEDULED || it.status == TvhRecordingStatus.RECORDING) &&
                    it.eventId > 0L
            }
            .map { it.eventId }
            .toSet()
        _seriesTitles.value = rules
            .filter { it.enabled }
            .map { it.title.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    suspend fun refresh(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force &&
            now - lastRefreshMs < CACHE_TTL_MS &&
            (_recordings.value.isNotEmpty() || _seriesRules.value.isNotEmpty())
        ) {
            return@withContext
        }
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val settings = TvhSettings(context)
            if (settings.host.isBlank()) {
                _errorMessage.value = "Tvheadend server host is not configured in Settings."
                return@withContext
            }
            val result = HtspConnectionPool.withClient(context, TAG) { it.fetchDvrState() }
            if (result != null) {
                lastRefreshMs = System.currentTimeMillis()
                updateDerivedFlows(context, result.first, result.second)
            } else {
                _errorMessage.value = "Unable to connect to Tvheadend HTSP (${settings.host}:${settings.htspPort})."
            }
        } finally {
            _isLoading.value = false
        }
    }

    fun resolveHtspChannelId(context: Context, channel: TvhChannel): Long {
        channel.uuid.toLongOrNull()?.let { if (it > 0L) return it }
        com.nmaclean.tvheadend.htsp.ChannelRegistry.getChannelByNumber(channel.number)?.let { if (it.id > 0L) return it.id }
        com.nmaclean.tvheadend.htsp.ChannelRegistry.getChannelByName(channel.name)?.let { if (it.id > 0L) return it.id }
        try {
            val uri = android.content.ContentUris.withAppendedId(android.media.tv.TvContract.Channels.CONTENT_URI, channel.id)
            context.contentResolver.query(
                uri,
                arrayOf(
                    android.media.tv.TvContract.Channels.COLUMN_SERVICE_ID,
                    android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dataCol = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                    if (dataCol >= 0 && !cursor.isNull(dataCol)) {
                        val bytes = cursor.getBlob(dataCol)
                        if (bytes != null && bytes.isNotEmpty()) {
                            val key = String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim()
                            key.toLongOrNull()?.let { if (it > 0L) return it }
                        }
                    }
                    val svcCol = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_SERVICE_ID)
                    if (svcCol >= 0 && !cursor.isNull(svcCol)) {
                        val svcId = cursor.getLong(svcCol)
                        if (svcId > 0L) return svcId
                    }
                }
            }
        } catch (_: Exception) {}
        return if (channel.id > 0L) channel.id else 0L
    }

    /**
     * Schedules a recording for [program] on [channel].
     * Attempts event-based scheduling first if [program.eventId] is known,
     * then seamlessly falls back to channel + start/end time scheduling.
     */
    suspend fun scheduleProgram(
        context: Context,
        channel: TvhChannel,
        program: EpgRepository.ProgramInfo,
        startExtraMin: Long = 0L,
        stopExtraMin: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        val htspChannelId = resolveHtspChannelId(context, channel)
        val ok = HtspConnectionPool.withClient(context, TAG) { client ->
            var success = false
            if (program.eventId > 0L) {
                success = client.scheduleDvrEvent(program.eventId, startExtraMin, stopExtraMin)
                if (success) {
                    android.util.Log.i(TAG, "Scheduled DVR recording by eventId ${program.eventId} for '${program.title}'")
                } else {
                    android.util.Log.w(TAG, "Failed scheduling by eventId ${program.eventId}, falling back to manual channel/time scheduling...")
                }
            }
            if (!success && htspChannelId > 0L) {
                val nowSec = System.currentTimeMillis() / 1000L
                val startSec = if (program.startTimeMs > 0L) program.startTimeMs / 1000L else nowSec
                val stopSec = if (program.endTimeMs > program.startTimeMs) program.endTimeMs / 1000L else (startSec + 2 * 3600L)
                val title = program.title.ifBlank { "${channel.name} Recording" }
                success = client.scheduleManualDvr(
                    channelId = htspChannelId,
                    startSec = startSec,
                    stopSec = stopSec,
                    title = title,
                    startExtraMin = startExtraMin,
                    stopExtraMin = stopExtraMin
                )
                if (success) {
                    android.util.Log.i(TAG, "Scheduled manual DVR recording for '$title' on channel $htspChannelId ($startSec..$stopSec)")
                } else {
                    android.util.Log.w(TAG, "Failed manual DVR scheduling for '$title' on channel $htspChannelId")
                }
            }
            success
        } ?: false
        if (ok) refresh(context, force = true)
        ok
    }

    suspend fun scheduleEvent(
        context: Context,
        eventId: Long,
        startExtraMin: Long = 0L,
        stopExtraMin: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        if (eventId <= 0L) return@withContext false
        val ok = HtspConnectionPool.withClient(context, TAG) { it.scheduleDvrEvent(eventId, startExtraMin, stopExtraMin) } ?: false
        if (ok) refresh(context, force = true)
        ok
    }

    suspend fun cancelTimer(context: Context, dvrId: Long): Boolean = withContext(Dispatchers.IO) {
        val ok = HtspConnectionPool.withClient(context, TAG) { client ->
            client.cancelDvrEntry(dvrId) || client.deleteDvrEntry(dvrId)
        } ?: false
        if (ok) refresh(context, force = true)
        ok
    }

    suspend fun deleteRecording(context: Context, dvrId: Long): Boolean = withContext(Dispatchers.IO) {
        val ok = HtspConnectionPool.withClient(context, TAG) { it.deleteDvrEntry(dvrId) } ?: false
        if (ok) {
            clearResumePosition(context, dvrId)
            refresh(context, force = true)
        }
        ok
    }

    suspend fun scheduleSeries(
        context: Context,
        title: String,
        channelId: Long? = null,
        fulltext: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val ok = HtspConnectionPool.withClient(context, TAG) { it.addSeriesRule(title, channelId, fulltext) } ?: false
        if (ok) refresh(context, force = true)
        ok
    }

    suspend fun toggleSeriesRule(context: Context, rule: TvhSeriesRule): Boolean = withContext(Dispatchers.IO) {
        val ok = HtspConnectionPool.withClient(context, TAG) { it.updateSeriesRuleEnabled(rule.id, !rule.enabled) } ?: false
        if (ok) refresh(context, force = true)
        ok
    }

    suspend fun deleteSeriesRule(context: Context, ruleId: String): Boolean = withContext(Dispatchers.IO) {
        val ok = HtspConnectionPool.withClient(context, TAG) { it.deleteSeriesRule(ruleId) } ?: false
        if (ok) refresh(context, force = true)
        ok
    }

    fun findActiveRecording(
        eventId: Long = 0L,
        channelId: Long = 0L,
        title: String? = null,
        startTimeMs: Long = 0L
    ): TvhRecording? {
        return _recordings.value.firstOrNull { rec ->
            val isActive = rec.status == TvhRecordingStatus.SCHEDULED || rec.status == TvhRecordingStatus.RECORDING
            if (!isActive) return@firstOrNull false

            if (eventId > 0L && rec.eventId == eventId) return@firstOrNull true

            if (channelId > 0L && rec.channelId == channelId && !title.isNullOrBlank()) {
                val titleMatches = rec.title.trim().equals(title.trim(), ignoreCase = true)
                val timeMatches = startTimeMs <= 0L || Math.abs(rec.startTimeMs - startTimeMs) < 10 * 60_000L
                if (titleMatches && timeMatches) return@firstOrNull true
            }

            false
        }
    }

    fun findActiveRecordingForEvent(eventId: Long): TvhRecording? {
        return findActiveRecording(eventId = eventId)
    }

    fun saveResumePosition(context: Context, dvrId: Long, positionMs: Long, durationMs: Long) {
        if (dvrId <= 0L) return
        val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        if (durationMs > 0L && positionMs >= (durationMs * 0.95).toLong()) {
            // Finished watching — mark 100% / clear resume offset
            prefs.edit().putLong("pos_$dvrId", durationMs).apply()
        } else {
            prefs.edit().putLong("pos_$dvrId", positionMs.coerceAtLeast(0L)).apply()
        }
        updateDerivedFlows(context, _recordings.value, _seriesRules.value)
    }

    fun getResumePosition(context: Context, dvrId: Long): Long {
        if (dvrId <= 0L) return 0L
        return context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            .getLong("pos_$dvrId", 0L)
    }

    private fun clearResumePosition(context: Context, dvrId: Long) {
        context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            .edit().remove("pos_$dvrId").apply()
    }
}

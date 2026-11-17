package com.nmaclean.tvheadend.telemetry

import android.os.SystemClock
import android.util.Log
import com.nmaclean.tvheadend.model.TvhSettings
import java.util.EnumMap

/**
 * Monotonic time source abstraction for deterministic unit testing.
 */
fun interface MonotonicClock {
    fun elapsedRealtimeMs(): Long
}

/**
 * Default production [MonotonicClock] backed by [SystemClock.elapsedRealtime].
 */
object SystemMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Ordered milestones in the Android TIF channel tune-to-first-frame pipeline.
 */
enum class TuneStage {
    TUNE_REQUESTED,
    HTSP_SUBSCRIBED,
    FIRST_PACKET,
    VIDEO_AVAILABLE
}

/**
 * Snapshot of recorded tune milestones and computed per-stage latency deltas in milliseconds.
 */
data class TuneSummary(
    val channelKey: String?,
    val tuneToSubscribedMs: Long?,
    val subscribedToFirstPacketMs: Long?,
    val firstPacketToVideoAvailableMs: Long?,
    val totalTuneDurationMs: Long?,
    val timestampsMs: Map<TuneStage, Long>,
    val missingStages: List<TuneStage>
)

/**
 * Thread-safe tracker for measuring per-stage and end-to-end channel tune latency
 * using monotonic [SystemClock.elapsedRealtime] timestamps.
 */
class TuneTelemetryTracker(
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val isDebugEnabled: () -> Boolean = { TvhSettings.isStreamingDebugCached },
    private val logger: (tag: String, message: String) -> Unit = { tag, msg -> Log.i(tag, msg) }
) {
    companion object {
        const val TAG = "TuneTelemetry"
        val shared: TuneTelemetryTracker = TuneTelemetryTracker()
    }

    private val lock = Any()
    private val timestamps = EnumMap<TuneStage, Long>(TuneStage::class.java)
    private var activeChannelKey: String? = null

    /**
     * Resets any in-progress tune measurements and records [TuneStage.TUNE_REQUESTED]
     * for [channelKey] (safe for rapid channel-zap resets).
     */
    fun onTuneRequested(channelKey: String): TuneSummary {
        synchronized(lock) {
            timestamps.clear()
            activeChannelKey = channelKey
            val now = clock.elapsedRealtimeMs()
            timestamps[TuneStage.TUNE_REQUESTED] = now
            logDebug("TVH_STREAM_DEBUG: [TUNE_TELEMETRY] stage=TUNE_REQUESTED channel=$channelKey tsMs=$now")
            return buildSummaryLocked()
        }
    }

    /**
     * Records [stage] if it has not already been recorded in the current tune cycle.
     * Returns the updated [TuneSummary], or `null` if the stage was ignored as a duplicate.
     */
    fun recordStage(stage: TuneStage, channelKey: String? = null): TuneSummary? {
        if (stage == TuneStage.TUNE_REQUESTED) {
            return onTuneRequested(channelKey ?: "unknown")
        }

        synchronized(lock) {
            if (channelKey != null && activeChannelKey != null && channelKey != activeChannelKey) {
                return null
            }
            if (timestamps.containsKey(stage)) {
                return null
            }

            val now = clock.elapsedRealtimeMs()
            timestamps[stage] = now

            val prevDelta = deltaFromNearestPrecedingStageLocked(stage, now)
            logDebug(
                "TVH_STREAM_DEBUG: [TUNE_TELEMETRY] stage=$stage channel=${activeChannelKey ?: "unknown"} " +
                    "deltaMs=${prevDelta ?: "N/A"} tsMs=$now"
            )

            val summary = buildSummaryLocked()
            if (stage == TuneStage.VIDEO_AVAILABLE) {
                logFinalSummaryLocked(summary)
            }
            return summary
        }
    }

    /**
     * Returns a thread-safe snapshot of the current tune telemetry state.
     */
    fun getSnapshot(): TuneSummary {
        synchronized(lock) {
            return buildSummaryLocked()
        }
    }

    /**
     * Clears all recorded milestones and the active channel key.
     */
    fun reset() {
        synchronized(lock) {
            timestamps.clear()
            activeChannelKey = null
        }
    }

    private fun deltaFromNearestPrecedingStageLocked(stage: TuneStage, nowMs: Long): Long? {
        val stages = TuneStage.entries
        for (i in (stage.ordinal - 1) downTo 0) {
            val prevTs = timestamps[stages[i]]
            if (prevTs != null) {
                return (nowMs - prevTs).coerceAtLeast(0L)
            }
        }
        return null
    }

    private fun buildSummaryLocked(): TuneSummary {
        val tReq = timestamps[TuneStage.TUNE_REQUESTED]
        val tSub = timestamps[TuneStage.HTSP_SUBSCRIBED]
        val tPkt = timestamps[TuneStage.FIRST_PACKET]
        val tVid = timestamps[TuneStage.VIDEO_AVAILABLE]

        val tuneToSubscribed = delta(tReq, tSub)
        val subscribedToFirstPacket = delta(tSub, tPkt)
        val firstPacketToVideo = delta(tPkt, tVid)
        val total = delta(tReq, tVid)
        val missing = TuneStage.entries.filter { !timestamps.containsKey(it) }

        return TuneSummary(
            channelKey = activeChannelKey,
            tuneToSubscribedMs = tuneToSubscribed,
            subscribedToFirstPacketMs = subscribedToFirstPacket,
            firstPacketToVideoAvailableMs = firstPacketToVideo,
            totalTuneDurationMs = total,
            timestampsMs = timestamps.toMap(),
            missingStages = missing
        )
    }

    private fun delta(startMs: Long?, endMs: Long?): Long? {
        if (startMs == null || endMs == null) return null
        return (endMs - startMs).coerceAtLeast(0L)
    }

    private fun logFinalSummaryLocked(summary: TuneSummary) {
        fun fmt(v: Long?): String = if (v != null) "${v}ms" else "N/A"
        logDebug(
            "TVH_STREAM_DEBUG: [TUNE_TELEMETRY] summary channel=${summary.channelKey ?: "unknown"} " +
                "TUNE_REQUESTED->HTSP_SUBSCRIBED=${fmt(summary.tuneToSubscribedMs)} " +
                "HTSP_SUBSCRIBED->FIRST_PACKET=${fmt(summary.subscribedToFirstPacketMs)} " +
                "FIRST_PACKET->VIDEO_AVAILABLE=${fmt(summary.firstPacketToVideoAvailableMs)} " +
                "TOTAL=${fmt(summary.totalTuneDurationMs)} " +
                "missing=${summary.missingStages}"
        )
    }

    private fun logDebug(message: String) {
        if (isDebugEnabled()) {
            logger(TAG, message)
        }
    }
}

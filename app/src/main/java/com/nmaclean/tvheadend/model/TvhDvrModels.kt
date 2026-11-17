package com.nmaclean.tvheadend.model

/**
 * Status of a Tvheadend DVR entry returned over HTSP.
 */
enum class TvhRecordingStatus {
    RECORDED,
    SCHEDULED,
    RECORDING,
    MISSED
}

/**
 * Represents a Tvheadend DVR recording or scheduled timer entry.
 */
data class TvhRecording(
    val id: Long,
    val eventId: Long = 0L,
    val channelId: Long,
    val channelName: String,
    val channelIcon: String = "",
    val title: String,
    val subtitle: String = "",
    val description: String = "",
    val startTimeMs: Long,
    val stopTimeMs: Long,
    val status: TvhRecordingStatus,
    val watchedPercent: Int = 0,
    val resumePositionMs: Long = 0L,
    val playCount: Int = 0,
    val imageUrl: String = ""
) {
    val durationMinutes: Int
        get() = (((stopTimeMs - startTimeMs).coerceAtLeast(0L)) / 60_000L).toInt()
}

/**
 * Represents a Tvheadend AutoRec / Series recording rule.
 */
data class TvhSeriesRule(
    val id: String,
    val title: String,
    val name: String,
    val channelId: Long = 0L,
    val channelName: String = "",
    val enabled: Boolean = true,
    val fulltext: Boolean = false,
    val comment: String = ""
)

/**
 * Computes watched percentage (0..100) from HTSP `playcount`, `playposition`, and recording duration.
 */
fun computeWatchedPercent(playCount: Int, playPositionSec: Long, durationSec: Long): Int {
    if (durationSec > 0L && playPositionSec > 0L) {
        return ((playPositionSec.toDouble() / durationSec.toDouble()) * 100.0).toInt().coerceIn(1, 100)
    }
    return if (playCount > 0) 100 else 0
}

package com.nmaclean.tvheadend.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuneTelemetryTrackerTest {

    private class FakeClock(var nowMs: Long = 1_000L) : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = nowMs
    }

    @Test
    fun testFullTuneStageDeltasAndTotalDuration() {
        val clock = FakeClock(10_000L)
        val logs = mutableListOf<String>()
        val tracker = TuneTelemetryTracker(clock, isDebugEnabled = { true }) { tag, msg -> logs.add("$tag: $msg") }

        tracker.onTuneRequested("ch-101")

        clock.nowMs = 10_045L
        val subSummary = tracker.recordStage(TuneStage.HTSP_SUBSCRIBED)
        assertNotNull(subSummary)
        assertEquals(45L, subSummary?.tuneToSubscribedMs)

        clock.nowMs = 10_180L
        val pktSummary = tracker.recordStage(TuneStage.FIRST_PACKET)
        assertNotNull(pktSummary)
        assertEquals(135L, pktSummary?.subscribedToFirstPacketMs)

        clock.nowMs = 10_420L
        val videoSummary = tracker.recordStage(TuneStage.VIDEO_AVAILABLE)
        assertNotNull(videoSummary)
        assertEquals(45L, videoSummary?.tuneToSubscribedMs)
        assertEquals(135L, videoSummary?.subscribedToFirstPacketMs)
        assertEquals(240L, videoSummary?.firstPacketToVideoAvailableMs)
        assertEquals(420L, videoSummary?.totalTuneDurationMs)
        assertTrue(videoSummary?.missingStages?.isEmpty() == true)

        assertTrue(logs.any {
            it.contains("TVH_STREAM_DEBUG: [TUNE_TELEMETRY] summary channel=ch-101") &&
                it.contains("TOTAL=420ms")
        })
    }

    @Test
    fun testLoggingSuppressedWhenStreamingDebugDisabled() {
        val clock = FakeClock(10_000L)
        val logs = mutableListOf<String>()
        val tracker = TuneTelemetryTracker(clock, isDebugEnabled = { false }) { tag, msg -> logs.add("$tag: $msg") }

        tracker.onTuneRequested("ch-muted")
        clock.nowMs = 10_200L
        val summary = tracker.recordStage(TuneStage.VIDEO_AVAILABLE)

        assertEquals(200L, summary?.totalTuneDurationMs)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun testRapidChannelZapResetsPreviousMilestones() {
        val clock = FakeClock(5_000L)
        val logs = mutableListOf<String>()
        val tracker = TuneTelemetryTracker(clock) { _, msg -> logs.add(msg) }

        tracker.onTuneRequested("ch-1")
        clock.nowMs = 5_050L
        tracker.recordStage(TuneStage.HTSP_SUBSCRIBED, "ch-1")

        // Rapid zap to ch-2 before FIRST_PACKET or VIDEO_AVAILABLE for ch-1
        clock.nowMs = 5_100L
        val zapSnapshot = tracker.onTuneRequested("ch-2")
        assertEquals("ch-2", zapSnapshot.channelKey)
        assertNull(zapSnapshot.tuneToSubscribedMs)
        assertEquals(
            listOf(TuneStage.HTSP_SUBSCRIBED, TuneStage.FIRST_PACKET, TuneStage.VIDEO_AVAILABLE),
            zapSnapshot.missingStages
        )

        // Late callback tagged for ch-1 must be ignored
        clock.nowMs = 5_120L
        assertNull(tracker.recordStage(TuneStage.FIRST_PACKET, "ch-1"))

        clock.nowMs = 5_140L
        tracker.recordStage(TuneStage.HTSP_SUBSCRIBED, "ch-2")
        clock.nowMs = 5_210L
        tracker.recordStage(TuneStage.FIRST_PACKET, "ch-2")
        clock.nowMs = 5_350L
        val finalSummary = tracker.recordStage(TuneStage.VIDEO_AVAILABLE, "ch-2")

        assertNotNull(finalSummary)
        assertEquals("ch-2", finalSummary?.channelKey)
        assertEquals(40L, finalSummary?.tuneToSubscribedMs)
        assertEquals(70L, finalSummary?.subscribedToFirstPacketMs)
        assertEquals(140L, finalSummary?.firstPacketToVideoAvailableMs)
        assertEquals(250L, finalSummary?.totalTuneDurationMs)
    }

    @Test
    fun testMissingMilestonesEdgeCases() {
        val clock = FakeClock(1_000L)
        val tracker = TuneTelemetryTracker(clock) { _, _ -> }

        // Case A: HTSP_SUBSCRIBED and FIRST_PACKET skipped between TUNE_REQUESTED and VIDEO_AVAILABLE
        tracker.onTuneRequested("ch-skip")
        clock.nowMs = 1_300L
        val skippedSummary = tracker.recordStage(TuneStage.VIDEO_AVAILABLE)
        assertNotNull(skippedSummary)
        assertNull(skippedSummary?.tuneToSubscribedMs)
        assertNull(skippedSummary?.subscribedToFirstPacketMs)
        assertNull(skippedSummary?.firstPacketToVideoAvailableMs)
        assertEquals(300L, skippedSummary?.totalTuneDurationMs)
        assertEquals(
            listOf(TuneStage.HTSP_SUBSCRIBED, TuneStage.FIRST_PACKET),
            skippedSummary?.missingStages
        )

        // Case B: TUNE_REQUESTED never called
        tracker.reset()
        clock.nowMs = 2_000L
        tracker.recordStage(TuneStage.FIRST_PACKET)
        clock.nowMs = 2_125L
        val orphanSummary = tracker.recordStage(TuneStage.VIDEO_AVAILABLE)
        assertNotNull(orphanSummary)
        assertNull(orphanSummary?.totalTuneDurationMs)
        assertEquals(125L, orphanSummary?.firstPacketToVideoAvailableMs)
        assertEquals(
            listOf(TuneStage.TUNE_REQUESTED, TuneStage.HTSP_SUBSCRIBED),
            orphanSummary?.missingStages
        )
    }

    @Test
    fun testDuplicateStageIsIdempotent() {
        val clock = FakeClock(100L)
        val tracker = TuneTelemetryTracker(clock) { _, _ -> }

        tracker.onTuneRequested("ch-dup")
        clock.nowMs = 150L
        assertNotNull(tracker.recordStage(TuneStage.HTSP_SUBSCRIBED))

        clock.nowMs = 250L
        assertNull(tracker.recordStage(TuneStage.HTSP_SUBSCRIBED))
        assertEquals(50L, tracker.getSnapshot().tuneToSubscribedMs)
    }
}

package com.nmaclean.tvheadend.player.afr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FrameRateMatcherTest {

    @Test
    fun testNormalizeBroadcastFrameRates() {
        assertEquals(25.0f, FrameRateMatcher.normalizeFrameRate(25.03f), 0.01f)
        assertEquals(50.0f, FrameRateMatcher.normalizeFrameRate(49.97f), 0.01f)
        assertEquals(24.0f, FrameRateMatcher.normalizeFrameRate(23.976f), 0.01f)
        assertEquals(30.0f, FrameRateMatcher.normalizeFrameRate(29.97f), 0.01f)
        assertEquals(60.0f, FrameRateMatcher.normalizeFrameRate(59.94f), 0.01f)
    }

    @Test
    fun testUkBroadcastTargetRefreshRateIs50Hz() {
        // 25fps UK broadcast -> 50.0Hz (smooth 2:1 cadence and 50Hz OSD)
        assertEquals(50.0f, FrameRateMatcher.resolveTargetRefreshRate(25.0f), 0.01f)
        assertEquals(50.0f, FrameRateMatcher.resolveTargetRefreshRate(50.0f), 0.01f)
        assertEquals(24.0f, FrameRateMatcher.resolveTargetRefreshRate(24.0f), 0.01f)
        assertEquals(60.0f, FrameRateMatcher.resolveTargetRefreshRate(60.0f), 0.01f)
    }

    @Test
    fun testFindBestSameResolutionDisplayMode() {
        val currentMode = DisplayModeInfo(1, 1920, 1080, 60.0f)
        val availableModes = listOf(
            DisplayModeInfo(1, 1920, 1080, 60.0f),
            DisplayModeInfo(2, 1920, 1080, 50.0f),
            DisplayModeInfo(3, 1920, 1080, 24.0f),
            DisplayModeInfo(4, 3840, 2160, 50.0f)
        )

        val bestMode = FrameRateMatcher.findBestDisplayMode(currentMode, availableModes, 50.0f)
        assertNotNull(bestMode)
        assertEquals(2, bestMode?.modeId)
        assertEquals(50.0f, bestMode?.refreshRate ?: 0f, 0.01f)
        assertEquals(1920, bestMode?.width)
    }
}

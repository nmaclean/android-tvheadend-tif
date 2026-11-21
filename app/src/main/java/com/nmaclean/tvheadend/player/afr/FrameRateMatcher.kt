package com.nmaclean.tvheadend.player.afr

import kotlin.math.abs

data class DisplayModeInfo(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

/**
 * Pure Kotlin decision engine for Auto Frame Rate (AFR) matching.
 * Normalizes broadcast frame rates (e.g. 25fps / 50fps UK streams -> 50.0Hz)
 * and resolves identical-resolution Display.Mode targets for legacy TV boxes.
 */
object FrameRateMatcher {

    fun normalizeFrameRate(fps: Float): Float {
        if (fps <= 0f || fps.isNaN()) return 0f
        return when {
            fps in 23.8f..24.1f -> 24.0f
            fps in 24.9f..25.1f -> 25.0f
            fps in 29.8f..30.1f -> 30.0f
            fps in 49.8f..50.2f -> 50.0f
            fps in 59.8f..60.2f -> 60.0f
            else -> fps
        }
    }

    fun resolveTargetRefreshRate(normalizedFps: Float): Float {
        return when (normalizedFps) {
            24.0f -> 24.0f
            25.0f -> 50.0f // Prefer 50Hz for 25fps UK streams for 2:1 smooth cadence & 50Hz UI
            30.0f -> 60.0f // Prefer 60Hz for 30fps streams for 2:1 smooth cadence
            50.0f -> 50.0f
            60.0f -> 60.0f
            else -> normalizedFps
        }
    }

    fun findBestDisplayMode(
        currentMode: DisplayModeInfo?,
        availableModes: List<DisplayModeInfo>,
        targetRefreshRate: Float
    ): DisplayModeInfo? {
        if (currentMode == null || availableModes.isEmpty() || targetRefreshRate <= 0f) return null

        val currentW = currentMode.width
        val currentH = currentMode.height

        val sameResModes = availableModes.filter { it.width == currentW && it.height == currentH }
        if (sameResModes.isEmpty()) return null

        return sameResModes.minByOrNull { mode ->
            val diff = abs(mode.refreshRate - targetRefreshRate)
            val isMultiple = abs(mode.refreshRate - (targetRefreshRate * 2)) < 0.2f
            if (diff < 0.2f) 0.0f else if (isMultiple) 0.1f else diff + 10f
        }
    }
}

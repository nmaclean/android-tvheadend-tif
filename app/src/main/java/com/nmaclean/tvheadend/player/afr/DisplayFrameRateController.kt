package com.nmaclean.tvheadend.player.afr

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.Window
import android.view.WindowManager

interface DisplayFrameRateController {
    fun applyFrameRate(surface: Surface?, frameRate: Float, window: Window? = null)
    fun resetFrameRate(surface: Surface?, window: Window? = null)
}

class AndroidDisplayFrameRateController(
    private val context: Context,
    private val settingsStore: AfrSettingsStore = AfrSettingsStore(context)
) : DisplayFrameRateController {

    companion object {
        const val TAG = "DisplayFrameRateCtrl"
        const val ZAP_DEBOUNCE_DELAY_MS = 750L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingDebounceRunnable: Runnable? = null
    private var originalDisplayModeId: Int = -1

    override fun applyFrameRate(surface: Surface?, frameRate: Float, window: Window?) {
        val mode = settingsStore.afrMode
        if (mode == AfrMode.OFF) return

        val normalized = FrameRateMatcher.normalizeFrameRate(frameRate)
        val targetRate = FrameRateMatcher.resolveTargetRefreshRate(normalized)
        if (targetRate <= 0f) return

        pendingDebounceRunnable?.let { handler.removeCallbacks(it) }

        val runnable = Runnable {
            try {
                // 1. API 30+ (Android 11+) Surface.setFrameRate
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface != null && surface.isValid) {
                    val changeStrategy = if (mode == AfrMode.ALWAYS) {
                        Surface.CHANGE_FRAME_RATE_ALWAYS
                    } else {
                        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                    }
                    surface.setFrameRate(
                        targetRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        changeStrategy
                    )
                    Log.i(TAG, "Applied Surface.setFrameRate($targetRate Hz, strategy=$changeStrategy)")
                }

                // 2. Legacy API 23-29 Display.Mode matching via Window
                if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                    val defaultDisplay = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                    if (defaultDisplay != null) {
                        val currentMode = defaultDisplay.mode
                        if (originalDisplayModeId == -1) {
                            originalDisplayModeId = currentMode.modeId
                        }
                        val availableModes = defaultDisplay.supportedModes.map {
                            DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
                        }
                        val currentModeInfo = DisplayModeInfo(
                            currentMode.modeId,
                            currentMode.physicalWidth,
                            currentMode.physicalHeight,
                            currentMode.refreshRate
                        )
                        val bestMode = FrameRateMatcher.findBestDisplayMode(currentModeInfo, availableModes, targetRate)
                        if (bestMode != null && bestMode.modeId != currentMode.modeId) {
                            val params = window.attributes
                            params.preferredDisplayModeId = bestMode.modeId
                            window.attributes = params
                            Log.i(TAG, "Applied Legacy preferredDisplayModeId=${bestMode.modeId} (${bestMode.refreshRate} Hz)")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying frame rate match: ${e.message}")
            }
        }
        pendingDebounceRunnable = runnable
        handler.postDelayed(runnable, ZAP_DEBOUNCE_DELAY_MS)
    }

    override fun resetFrameRate(surface: Surface?, window: Window?) {
        pendingDebounceRunnable?.let { handler.removeCallbacks(it) }
        pendingDebounceRunnable = null

        if (settingsStore.afrMode == AfrMode.OFF || !settingsStore.restoreOnExit) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && surface != null && surface.isValid) {
                surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                Log.i(TAG, "Reset Surface.setFrameRate(0f)")
            }
            if (window != null && originalDisplayModeId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val params = window.attributes
                params.preferredDisplayModeId = originalDisplayModeId
                window.attributes = params
                originalDisplayModeId = -1
                Log.i(TAG, "Reset preferredDisplayModeId to original")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting frame rate: ${e.message}")
        }
    }
}

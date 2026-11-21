package com.nmaclean.tvheadend.player.afr

import android.content.Context
import android.view.Surface
import android.view.Window
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Shared generic helper encapsulating Auto Frame Rate (AFR) matching lifecycle
 * for ExoPlayer TIF sessions across Tvheadend and IPTV inputs.
 */
@OptIn(UnstableApi::class)
class TifAfrSessionHelper(context: Context) {
    val controller: DisplayFrameRateController = AndroidDisplayFrameRateController(context)
    val observer: AfrPlayerObserver = AfrPlayerObserver(controller)

    fun attachToPlayer(player: ExoPlayer) {
        player.addListener(observer)
    }

    fun onSetSurface(surface: Surface?, window: Window? = null) {
        observer.updateSurfaceAndWindow(surface, window)
    }

    fun release(surface: Surface? = null, window: Window? = null) {
        observer.release()
    }
}

package com.nmaclean.tvheadend.player.afr

import android.view.Surface
import android.view.Window
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class AfrPlayerObserver(
    private val controller: DisplayFrameRateController,
    private var surface: Surface? = null,
    private var window: Window? = null
) : Player.Listener {

    private var currentFrameRate: Float = 0f

    fun updateSurfaceAndWindow(surface: Surface?, window: Window? = null) {
        this.surface = surface
        this.window = window
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (currentFrameRate > 0f) {
            controller.applyFrameRate(surface, currentFrameRate, window)
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        for (group in tracks.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        val format: Format = group.getTrackFormat(i)
                        if (format.frameRate > 0f) {
                            currentFrameRate = format.frameRate
                            controller.applyFrameRate(surface, currentFrameRate, window)
                        }
                    }
                }
            }
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY && currentFrameRate > 0f) {
            controller.applyFrameRate(surface, currentFrameRate, window)
        }
    }

    fun release() {
        controller.resetFrameRate(surface, window)
        surface = null
        window = null
    }
}

package com.nmaclean.tvheadend.iptv.tif

import android.content.Context
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.content.ComponentName
import android.media.tv.TvContract
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.preference.PreferenceManager

@UnstableApi
class IptvTvInputService : TvInputService() {
    companion object {
        const val TAG = "IptvTvInputService"
    }

    private fun isIptvTifEnabled(): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("iptv_tif_enabled", true)
    }

    override fun onCreateSession(inputId: String): Session {
        return IptvSession(this)
    }

    inner class IptvSession(context: Context) : Session(context), Player.Listener {
        private var player: ExoPlayer? = null
        private var surface: Surface? = null
        private var currentVolume = 1.0f

        init {
            setOverlayViewEnabled(false)
        }

        private fun getOrCreatePlayer(): ExoPlayer {
            val existing = player
            if (existing != null) return existing
            val extractorsFactory = DefaultExtractorsFactory().setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
            )
            val mediaSourceFactory = DefaultMediaSourceFactory(baseContext, extractorsFactory)
            val newPlayer = ExoPlayer.Builder(baseContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                addListener(this@IptvSession)
            }
            player = newPlayer
            return newPlayer
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            this.surface = surface
            player?.setVideoSurface(surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            currentVolume = volume
            player?.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {
            // Optional caption handling
        }

        override fun onTune(uri: Uri): Boolean {
            Log.i(TAG, "IptvSession onTune: uri=$uri")
            if (!isIptvTifEnabled()) {
                Log.w(TAG, "IPTV TIF is disabled; blocking tune for $uri")
                player?.stop()
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return false
            }
            notifyContentAllowed()

            val resolved = IptvStreamResolver.resolve(contentResolver, uri, baseContext)
            if (resolved == null) {
                Log.e(TAG, "Failed to resolve IPTV stream for URI: $uri")
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return false
            }

            val p = getOrCreatePlayer()
            p.stop()
            if (surface != null && surface?.isValid == true) {
                p.setVideoSurface(surface)
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
                if (resolved.headers.isNotEmpty()) {
                    setDefaultRequestProperties(resolved.headers)
                }
                setUserAgent("Mozilla/5.0 (Linux; Android 14; ATV) AppleWebKit/537.36")
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(Uri.parse(resolved.streamUrl)))

            p.setMediaSource(mediaSource, true)
            p.volume = if (currentVolume > 0.0f) currentVolume else 1.0f
            p.prepare()
            p.playWhenReady = true

            return true
        }

        override fun onRelease() {
            player?.removeListener(this)
            player?.clearVideoSurface()
            player?.stop()
            player?.release()
            player = null
            surface = null
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                notifyVideoAvailable()
            }
        }

        override fun onTimeShiftPause() {
            val p = player ?: return
            if (p.isCurrentMediaItemSeekable) {
                p.pause()
            }
        }

        override fun onTimeShiftResume() {
            val p = player ?: return
            p.play()
        }

        override fun onTimeShiftSeekTo(timeMs: Long) {
            val p = player ?: return
            if (p.isCurrentMediaItemSeekable) {
                p.seekTo(timeMs)
            }
        }

        override fun onTimeShiftGetCurrentPosition(): Long {
            val p = player ?: return TvInputManager.TIME_SHIFT_INVALID_TIME
            return if (p.isCurrentMediaItemSeekable) p.currentPosition else TvInputManager.TIME_SHIFT_INVALID_TIME
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "IPTV ExoPlayer error: ${error.message}", error)
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
        }
    }
}

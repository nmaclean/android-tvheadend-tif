package com.nmaclean.tvheadend

import android.database.Cursor
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.ExtractorsFactory
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android TV Input Service (TIF) that binds Tvheadend live streams to the Android TV Live TV application.
 *
 * Implements [TvInputService] and manages playback sessions via [ExoPlayer], [HtspDataSource], and [HtspExtractor].
 */
@UnstableApi
class TvheadendInputService : TvInputService() {
    companion object {
        const val TAG = "TvheadendInputService"
    }

    /**
     * Creates a new [TvheadendSession] instance for the given TV input session ID.
     *
     * @param inputId Unique string identifier for the TV input.
     * @return Newly initialized [TvheadendSession].
     */
    override fun onCreateSession(inputId: String): Session {
        Log.i(TAG, "=== onCreateSession called with inputId: $inputId ===")
        return TvheadendSession()
    }

    /**
     * Session subclass managing the lifecycle of an individual TV channel playback instance.
     *
     * Binds the video rendering surface, executes channel queries, configures ExoPlayer playback,
     * and reports track/video availability state changes to the Android TV framework.
     */
    inner class TvheadendSession : Session(this@TvheadendInputService), Player.Listener {
        private var player: ExoPlayer? = null
        private var currentChannelUuid: String? = null
        private val settings = TvhSettings(baseContext)
        private var surface: Surface? = null
        private val sessionJob = SupervisorJob()
        private val sessionScope = CoroutineScope(Dispatchers.Main + sessionJob)
        private var tuneJob: Job? = null

        /**
         * Binds or unbinds the video rendering surface provided by the Android TV framework.
         *
         * @param surface Target [Surface] on which video frames should be drawn, or `null` to detach.
         * @return `true` if the surface was handled successfully.
         */
        override fun onSetSurface(surface: Surface?): Boolean {
            val isValid = surface?.isValid == true
            Log.i(TAG, "=== onSetSurface called with surface: $surface, isValid=$isValid ===")
            this.surface = if (isValid) surface else null

            player?.let { p ->
                p.setVideoSurface(this.surface)
                if (this.surface != null) {
                    if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                        p.play()
                    }
                }
            }
            return true
        }

        private var currentVolume = 1.0f

        /**
         * Sets the audio volume for this TV input session.
         *
         * @param volume Volume level between `0.0` (mute) and `1.0` (full volume).
         */
        override fun onSetStreamVolume(volume: Float) {
            Log.i(TAG, "onSetStreamVolume called with volume: $volume")
            if (volume > 0.0f) {
                currentVolume = volume
                player?.volume = volume
            } else {
                val p = player
                if (p != null && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) && currentVolume > 0.0f) {
                    Log.w(TAG, "System sent volume=0.0 during active playback; ignoring system mute and keeping volume at $currentVolume")
                    p.volume = currentVolume
                } else {
                    player?.volume = 0.0f
                }
            }
        }

        private fun restoreVolumeIfNeeded() {
            val p = player ?: return
            val targetVol = if (currentVolume > 0.0f) currentVolume else 1.0f
            if (p.volume < targetVol) {
                Log.i(TAG, "Restoring ExoPlayer volume to $targetVol (was ${p.volume})")
                p.volume = targetVol
            }
        }

        /**
         * Called when closed caption state is toggled by the system.
         *
         * @param enabled `true` if captions should be displayed.
         */
        override fun onSetCaptionEnabled(enabled: Boolean) {
            // Optional: Closed captions handling
        }

        /**
         * Overloaded tune callback containing additional vendor parameters.
         *
         * @param unhandledUri Target channel content URI.
         * @param params Optional vendor parameters bundle.
         * @return Result of delegated [onTune] call.
         */
        override fun onTune(unhandledUri: Uri, params: Bundle?): Boolean {
            Log.i(TAG, "=== onTune(uri, params) called: uri=$unhandledUri, params=$params ===")
            return onTune(unhandledUri)
        }

        /**
         * Tunes to a specific channel identified by the provided content URI.
         *
         * Queries channel metadata from [TvContract.Channels], resolves the channel UUID or service ID,
         * and initiates HTSP streaming playback.
         *
         * @param uri Channel content URI (e.g. `content://android.media.tv/channel/123`).
         * @return `true` if tuning was initiated successfully.
         */
        override fun onTune(uri: Uri): Boolean {
            Log.i(TAG, "=== onTune(uri) called: uri=$uri ===")
            notifyContentAllowed()
            // Immediately inform the TV shell that tuning has begun to satisfy timeout contracts
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)

            tuneJob?.cancel()
            tuneJob = sessionScope.launch {
                val streamKey = withContext(Dispatchers.IO) {
                    val cursor = contentResolver.query(
                        uri,
                        arrayOf(
                            TvContract.Channels._ID,
                            TvContract.Channels.COLUMN_DISPLAY_NAME,
                            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                            TvContract.Channels.COLUMN_SERVICE_ID,
                            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                        ),
                        null, null, null
                    )

                    if (cursor == null || !cursor.moveToFirst()) {
                        Log.e(TAG, "Cursor query failed or empty for URI: $uri")
                        cursor?.close()
                        return@withContext ""
                    }

                    val idCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val nameCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                    val numCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                    val serviceCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)
                    val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)

                    val channelId = if (idCol >= 0) cursor.getLong(idCol) else 0L
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else ""
                    val number = if (numCol >= 0) cursor.getString(numCol) else ""
                    val serviceId = if (serviceCol >= 0) cursor.getInt(serviceCol) else 0

                    val keyFromData = if (dataCol >= 0) {
                        try {
                            when (cursor.getType(dataCol)) {
                                Cursor.FIELD_TYPE_BLOB -> {
                                    val bytes = cursor.getBlob(dataCol)
                                    if (bytes != null && bytes.isNotEmpty()) String(bytes, StandardCharsets.UTF_8).trim() else ""
                                }
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(dataCol)?.trim() ?: ""
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(dataCol).toString()
                                else -> cursor.getString(dataCol)?.trim() ?: ""
                            }
                        } catch (e: Exception) {
                            cursor.getString(dataCol)?.trim() ?: ""
                        }
                    } else ""

                    val key = when {
                        keyFromData.isNotEmpty() -> keyFromData
                        serviceId > 0 -> serviceId.toString()
                        else -> ""
                    }

                    cursor.close()

                    Log.d(TAG, "Column _id = $channelId")
                    Log.d(TAG, "Column display_name = $name")
                    Log.d(TAG, "Column display_number = $number")
                    Log.i(TAG, "Column service_id = $serviceId")
                    Log.i(TAG, "Resolved StreamKey (UUID/ID) = $key")
                    key
                }

                if (streamKey.isEmpty()) {
                    Log.e(TAG, "ERROR: Channel UUID / StreamKey is empty! Cannot tune.")
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                } else {
                    currentChannelUuid = streamKey
                    startPlayback(streamKey)
                }
            }
            return true
        }

        private fun startPlayback(uuid: String) {
            val host = settings.host
            val port = settings.htspPort

            Log.i(TAG, "=== Starting NATIVE HTSP playback for channel: $uuid on $host:$port ===")

            val streamUri = Uri.parse("htsp://$host:$port/$uuid")
            releasePlayer()

            // Enable decoder fallback, extension renderers, & disable async queueing to prevent MediaTek/Amlogic hardware decoders on Android TV from failing MediaCodec initialization
            val renderersFactory = DefaultRenderersFactory(baseContext).apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                forceDisableMediaCodecAsynchronousQueueing()
            }

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ TvhConstants.EXOPLAYER_MIN_BUFFER_MS,
                    /* maxBufferMs = */ TvhConstants.EXOPLAYER_MAX_BUFFER_MS,
                    /* bufferForPlaybackMs = */ TvhConstants.EXOPLAYER_BUFFER_FOR_PLAYBACK_MS,
                    /* bufferForPlaybackAfterRebufferMs = */ TvhConstants.EXOPLAYER_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val trackSelectionParameters = TrackSelectionParameters.Builder(baseContext)
                .setPreferredAudioMimeTypes(
                    MimeTypes.AUDIO_AAC,
                    MimeTypes.AUDIO_AC3,
                    MimeTypes.AUDIO_E_AC3
                )
                .build()

            val exoPlayer = ExoPlayer.Builder(baseContext, renderersFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    setTrackSelectionParameters(trackSelectionParameters)
                    setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)

                    // Bind surface if already delivered by the system before or during tuning
                    if (this@TvheadendSession.surface?.isValid == true) {
                        Log.i(TAG, "Binding existing valid surface to ExoPlayer.")
                        setVideoSurface(this@TvheadendSession.surface)
                    } else {
                        Log.w(TAG, "Surface is null or invalid when starting playback; waiting for onSetSurface.")
                    }

                    val dataSourceFactory = HtspDataSource.Factory(baseContext)
                    val extractorsFactory = ExtractorsFactory { arrayOf(HtspExtractor()) }

                    val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(MediaItem.fromUri(streamUri))

                    setMediaSource(mediaSource)
                    volume = 1.0f
                    prepare()
                    playWhenReady = true
                    addListener(this@TvheadendSession)
                    addAnalyticsListener(EventLogger("ExoPlayerEventLogger"))
                }
            player = exoPlayer
        }

        private fun releasePlayer() {
            Log.d(TAG, "Releasing player...")
            try {
                player?.stop()
                player?.clearMediaItems()
                player?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player", e)
            }
            player = null
        }

        /**
         * Called when the session is released by the Android TV system.
         */
        override fun onRelease() {
            Log.d(TAG, "onRelease called on session.")
            sessionJob.cancel()
            releasePlayer()
        }

        /**
         * Called when parental controls content is unblocked.
         *
         * @param unblockedRating Unblocked content rating object.
         */
        override fun onUnblockContent(unblockedRating: TvContentRating?) {
            Log.d(TAG, "onUnblockContent called for rating: $unblockedRating")
            notifyContentAllowed()
        }

        /**
         * ExoPlayer listener callback triggered on playback state transitions.
         *
         * @param playbackState One of [Player.STATE_IDLE], [Player.STATE_BUFFERING], [Player.STATE_READY], or [Player.STATE_ENDED].
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "STATE_IDLE"
                Player.STATE_BUFFERING -> "STATE_BUFFERING"
                Player.STATE_READY -> "STATE_READY"
                Player.STATE_ENDED -> "STATE_ENDED"
                else -> "UNKNOWN ($playbackState)"
            }
            Log.i(TAG, "ExoPlayer playback state changed: $stateStr ($playbackState)")

            if (playbackState == Player.STATE_READY) {
                notifyContentAllowed()
                notifyVideoAvailable()
                restoreVolumeIfNeeded()
            }
        }

        /**
         * ExoPlayer listener callback triggered when video dimensions are determined or updated.
         *
         * @param videoSize Video width, height, and rotation properties.
         */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.i(TAG, "ExoPlayer video size changed: width = ${videoSize.width}, height = ${videoSize.height}, unappliedRotation = ${videoSize.unappliedRotationDegrees}")
        }

        /**
         * ExoPlayer listener callback triggered when buffering loading status changes.
         *
         * @param isLoading `true` if ExoPlayer is actively loading media data.
         */
        override fun onIsLoadingChanged(isLoading: Boolean) {
            Log.i(TAG, "ExoPlayer isLoading changed: $isLoading")
        }

        /**
         * ExoPlayer listener callback triggered when media tracks (audio/video) are loaded or modified.
         *
         * Builds [TvTrackInfo] structures and notifies the Android TV framework.
         *
         * @param tracks Track groups provided by ExoPlayer.
         */
        override fun onTracksChanged(tracks: Tracks) {
            Log.i(TAG, "ExoPlayer tracks changed: group count = ${tracks.groups.size}")
            val tvTracks = mutableListOf<TvTrackInfo>()
            var selectedVideoTrackId: String? = null
            var selectedAudioTrackId: String? = null

            for (group in tracks.groups) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val trackId = format.id ?: "$i"
                    val isSelected = group.isTrackSelected(i)

                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        val builder = TvTrackInfo.Builder(TvTrackInfo.TYPE_VIDEO, trackId)
                        if (format.width > 0 && format.height > 0) {
                            builder.setVideoWidth(format.width)
                            builder.setVideoHeight(format.height)
                        }
                        if (format.frameRate > 0) {
                            builder.setVideoFrameRate(format.frameRate)
                        }
                        tvTracks.add(builder.build())
                        if (isSelected) selectedVideoTrackId = trackId
                    } else if (group.type == C.TRACK_TYPE_AUDIO) {
                        val builder = TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, trackId)
                        if (format.channelCount > 0) {
                            builder.setAudioChannelCount(format.channelCount)
                        }
                        if (format.sampleRate > 0) {
                            builder.setAudioSampleRate(format.sampleRate)
                        }
                        val lang = format.language
                        if (!lang.isNullOrEmpty()) {
                            builder.setLanguage(lang)
                        }
                        tvTracks.add(builder.build())
                        if (isSelected) selectedAudioTrackId = trackId
                    }
                }
            }

            Log.i(TAG, "Notifying ${tvTracks.size} TV tracks to framework: selectedVideo=$selectedVideoTrackId, selectedAudio=$selectedAudioTrackId")
            notifyTracksChanged(tvTracks)

            if (selectedVideoTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, selectedVideoTrackId)
            }
            if (selectedAudioTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, selectedAudioTrackId)
            }
            restoreVolumeIfNeeded()
        }

        /**
         * ExoPlayer listener callback triggered when the very first video frame is rendered onto the surface.
         */
        override fun onRenderedFirstFrame() {
            Log.i(TAG, "=== ExoPlayer rendered first video frame successfully! ===")
            notifyContentAllowed()
            notifyVideoAvailable()
            restoreVolumeIfNeeded()
        }

        /**
         * ExoPlayer listener callback triggered on playback errors.
         *
         * @param error [PlaybackException] describing the error.
         */
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayback error encountered: errorCodeName = ${error.errorCodeName}, message = ${error.message}", error)

            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                Log.w(TAG, "Player fell behind live window. Re-initializing at default position.")
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }

            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        }
    }
}

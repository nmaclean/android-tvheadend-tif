package com.nmaclean.tvheadend

import android.content.Context
import android.database.Cursor
import android.media.MediaCodec
import android.media.PlaybackParams
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.ExtractorsFactory
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.htsp.HtspClient
import com.nmaclean.tvheadend.htsp.HtspConnectionPool
import com.nmaclean.tvheadend.model.TvhConstants
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.player.HtspDataSource
import com.nmaclean.tvheadend.player.HtspExtractor
import com.nmaclean.tvheadend.telemetry.TuneStage
import com.nmaclean.tvheadend.telemetry.TuneTelemetryTracker
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
        HtspConnectionPool.preconnectAsync(baseContext)
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
        private var subtitleView: SubtitleView? = null
        private var currentUri: Uri? = null
        private var currentChannelUuid: String? = null
        private val settings = TvhSettings(baseContext)
        private var surface: Surface? = null
        private val sessionJob = SupervisorJob()
        private val sessionScope = CoroutineScope(Dispatchers.Main + sessionJob)
        private var tuneJob: Job? = null
        private var tuneStartTimeMs = 0L
        private var hasReachedReadyState = false
        private var timeShiftStartMs = 0L
        private var isTimeShiftPaused = false
        private var pausedAtMs = 0L
        private var totalPausedMs = 0L

        init {
            setOverlayViewEnabled(true)
            sessionScope.launch {
                // Pre-warm ExoPlayer instance so it is ready before onTune fires
                getOrCreatePlayer()
            }
        }

        private fun getOrCreatePlayer(): ExoPlayer {
            val existing = player
            if (existing != null) return existing

            Log.i(TAG, "Pre-building ExoPlayer instance for TIF Session...")
            val renderersFactory = object : DefaultRenderersFactory(baseContext) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    val defaultSink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                    return object : ForwardingAudioSink(checkNotNull(defaultSink)) {
                        override fun setListener(listener: AudioSink.Listener) {
                            super.setListener(object : AudioSink.Listener {
                                override fun onPositionDiscontinuity() {
                                    listener.onPositionDiscontinuity()
                                }

                                override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                                    listener.onPositionAdvancing(playoutStartSystemTimeMs)
                                }

                                override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                                    if (settings.isStreamingDebugEnabled) {
                                        Log.w(TAG, "TVH_STREAM_DEBUG: [AUDIO UNDERRUN] bufferSize=$bufferSize, bufferSizeMs=$bufferSizeMs, elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs")
                                    }
                                    listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
                                }

                                override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {
                                    listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
                                }

                                override fun onOffloadBufferEmptying() {
                                    listener.onOffloadBufferEmptying()
                                }

                                override fun onOffloadBufferFull() {
                                    listener.onOffloadBufferFull()
                                }

                                override fun onAudioSinkError(audioSinkError: Exception) {
                                    if (settings.isStreamingDebugEnabled) {
                                        Log.w(TAG, "TVH_STREAM_DEBUG: [AUDIO DISCONTINUITY] Audio sink error: ${audioSinkError.message}", audioSinkError)
                                    }
                                    if (audioSinkError is AudioSink.UnexpectedDiscontinuityException) {
                                        Log.w(TAG, "Tolerated audio timestamp discontinuity (expected ${audioSinkError.expectedPresentationTimeUs}, got ${audioSinkError.actualPresentationTimeUs})")
                                    } else {
                                        listener.onAudioSinkError(audioSinkError)
                                    }
                                }

                                override fun onAudioCapabilitiesChanged() {
                                    listener.onAudioCapabilitiesChanged()
                                }

                                override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                                    listener.onAudioTrackInitialized(audioTrackConfig)
                                }

                                override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
                                    listener.onAudioTrackReleased(audioTrackConfig)
                                }

                                override fun onSilenceSkipped() {
                                    listener.onSilenceSkipped()
                                }
                            })
                        }
                    }
                }
            }.apply {
                setEnableDecoderFallback(true)
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                forceDisableMediaCodecAsynchronousQueueing()
            }

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    TvhConstants.EXOPLAYER_MIN_BUFFER_MS,
                    TvhConstants.EXOPLAYER_MAX_BUFFER_MS,
                    TvhConstants.EXOPLAYER_BUFFER_FOR_PLAYBACK_MS,
                    TvhConstants.EXOPLAYER_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val trackSelectionParameters = TrackSelectionParameters.Builder(baseContext)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()

            val exoPlayer = ExoPlayer.Builder(baseContext, renderersFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    setWakeMode(C.WAKE_MODE_LOCAL)
                    setTrackSelectionParameters(trackSelectionParameters)
                    setAudioAttributes(audioAttributes, true)
                    addListener(this@TvheadendSession)
                    addAnalyticsListener(EventLogger("ExoPlayerEventLogger"))
                }
            player = exoPlayer
            return exoPlayer
        }

        private fun populateChannelRegistryFromServer() {
            try {
                val settings = TvhSettings(baseContext)
                val client = HtspClient(settings.host, settings.htspPort)
                if (client.connect(settings.username, settings.password)) {
                    val channels = client.fetchChannels()
                    client.disconnect()
                    if (channels.isNotEmpty()) {
                        ChannelRegistry.updateChannels(channels)
                        Log.i(TAG, "Auto-populated ChannelRegistry with ${channels.size} channels during onTune fallback")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-populate ChannelRegistry in onTune fallback: ${e.message}")
            }
        }

        private val mediaSourceFactory: ProgressiveMediaSource.Factory by lazy {
            val dataSourceFactory = HtspDataSource.Factory(baseContext)
            val extractorsFactory = ExtractorsFactory { arrayOf(HtspExtractor(baseContext)) }
            ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
        }

        private fun startPlayback(uuid: String) {
            hasReachedReadyState = false
            val host = settings.host
            val port = settings.htspPort

            Log.i(TAG, "=== Starting NATIVE HTSP playback for channel: $uuid on $host:$port ===")

            val streamUri = Uri.parse("htsp://$host:$port/$uuid")
            val p = getOrCreatePlayer()

            // Stop prior playback and flush AudioSink & decoder pipeline before tuning new channel
            p.stop()
            p.clearMediaItems()

            if (this@TvheadendSession.surface?.isValid == true) {
                Log.i(TAG, "Binding existing valid surface to ExoPlayer.")
                p.setVideoSurface(this@TvheadendSession.surface)
            } else {
                Log.w(TAG, "Surface is null or invalid when starting playback; clearing video surface.")
                p.clearVideoSurface()
            }

            val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(streamUri))

            p.setMediaSource(mediaSource, /* resetPosition= */ true)
            val targetVol = if (currentVolume > 0.0f) currentVolume else 1.0f
            p.volume = targetVol
            p.prepare()
            p.playWhenReady = true
        }

        override fun onCreateOverlayView(): View {
            Log.i(TAG, "onCreateOverlayView called: creating SubtitleView overlay")
            val view = SubtitleView(baseContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setUserDefaultStyle()
                setUserDefaultTextSize()
                setApplyEmbeddedStyles(true)
                setApplyEmbeddedFontSizes(true)
                setBottomPaddingFraction(settings.subtitleBottomPaddingFraction)
            }
            subtitleView = view
            return view
        }

        /**
         * Binds or unbinds the video rendering surface provided by the Android TV framework.
         *
         * @param surface Target [Surface] on which video frames should be drawn, or `null` to detach.
         * @return `true` if the surface was handled successfully.
         */
        override fun onSetSurface(surface: Surface?): Boolean {
            val isValid = surface?.isValid == true
            if (settings.isStreamingDebugEnabled) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [SET SURFACE] onSetSurface called: surface=$surface, isValid=$isValid")
            }
            Log.i(TAG, "=== onSetSurface called with surface: $surface, isValid=$isValid ===")
            this.surface = if (isValid) surface else null

            if (this.surface != null) {
                player?.let { p ->
                    p.setVideoSurface(this.surface)
                    if (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) {
                        p.play()
                    }
                } ?: run {
                    if (!currentChannelUuid.isNullOrEmpty()) {
                        Log.i(TAG, "Surface re-attached and player is null. Restoring playback for channel $currentChannelUuid.")
                        startPlayback(currentChannelUuid!!)
                    } else if (currentUri != null) {
                        Log.i(TAG, "Surface re-attached but currentChannelUuid is null. Re-tuning URI: $currentUri")
                        onTune(currentUri!!)
                    }
                }
            } else {
                Log.i(TAG, "Surface detached/invalid (screensaver or background). Clearing video surface and releasing player.")
                player?.clearVideoSurface()
                releasePlayer(preserveChannel = true)
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
            currentVolume = volume
            player?.volume = volume
        }

        private fun restoreVolumeIfNeeded() {
            val p = player ?: return
            val targetVol = if (currentVolume > 0.0f) currentVolume else 1.0f
            if (p.volume != targetVol) {
                Log.i(TAG, "Syncing ExoPlayer volume to $targetVol (was ${p.volume})")
                p.volume = targetVol
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            Log.d(TAG, "onCues received: ${cueGroup.cues.size} cues")
            subtitleView?.setCues(cueGroup.cues)
        }

        /**
         * Called when closed caption state is toggled by the system.
         *
         * @param enabled `true` if captions should be displayed.
         */
        override fun onSetCaptionEnabled(enabled: Boolean) {
            Log.i(TAG, "onSetCaptionEnabled called: enabled=$enabled")
            subtitleView?.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) {
                subtitleView?.bringToFront()
            }
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
            tuneStartTimeMs = System.currentTimeMillis()
            if (settings.isStreamingDebugEnabled) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [TUNE REQUEST] Tune initiated for URI: $uri")
            }
            Log.i(TAG, "=== onTune(uri) called: uri=$uri ===")

            val lastSegment = uri.lastPathSegment?.toLongOrNull()
            val cachedChannel = if (lastSegment != null) ChannelRegistry.getChannelById(lastSegment) else null
            val resolvedStreamKey = if (cachedChannel != null) {
                if (cachedChannel.uuid.isNotEmpty()) cachedChannel.uuid else cachedChannel.id.toString()
            } else null

            if (player != null && (currentUri == uri || (resolvedStreamKey != null && currentChannelUuid == resolvedStreamKey))) {
                Log.i(TAG, "Channel URI $uri (StreamKey $resolvedStreamKey) is already currently playing; skipping playback reload.")
                notifyContentAllowed()
                notifyVideoAvailable()
                return true
            }

            notifyContentAllowed()
            notifyVideoAvailable()

            TuneTelemetryTracker.shared.onTuneRequested(resolvedStreamKey ?: uri.lastPathSegment ?: uri.toString())

            tuneJob?.cancel()
            currentUri = uri

            if (cachedChannel != null) {
                val streamKey = resolvedStreamKey ?: if (cachedChannel.uuid.isNotEmpty()) cachedChannel.uuid else cachedChannel.id.toString()
                Log.i(TAG, "Instant in-memory tune via ChannelRegistry for URI $uri -> StreamKey $streamKey")
                currentChannelUuid = streamKey
                startPlayback(streamKey)
                return true
            }

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
                        Log.w(TAG, "Cursor query failed or empty for URI: $uri. Attempting robust fallback via ChannelRegistry...")
                        cursor?.close()
                        if (ChannelRegistry.getAllChannels().isEmpty()) {
                            populateChannelRegistryFromServer()
                        }
                        val lastSegment = uri.lastPathSegment?.toLongOrNull()
                        if (lastSegment != null) {
                            val byId = ChannelRegistry.getChannelById(lastSegment)
                            if (byId != null) return@withContext byId.uuid.ifEmpty { byId.id.toString() }
                            val byNum = ChannelRegistry.getChannelByNumber(lastSegment.toInt())
                            if (byNum != null) return@withContext byNum.uuid.ifEmpty { byNum.id.toString() }
                        }
                        val allChannels = ChannelRegistry.getAllChannels()
                        if (allChannels.isNotEmpty()) {
                            val firstCh = allChannels.first()
                            Log.w(TAG, "Channel not found for URI $uri; falling back to first available channel: ${firstCh.name}")
                            return@withContext firstCh.uuid.ifEmpty { firstCh.id.toString() }
                        }
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

                    var key = keyFromData
                    val idLong = key.toLongOrNull()

                    val directMatch = if (idLong != null) ChannelRegistry.getChannelById(idLong) else null
                    if (directMatch != null) {
                        key = directMatch.uuid.ifEmpty { directMatch.id.toString() }
                        Log.i(TAG, "StreamKey $key directly matched in ChannelRegistry")
                    } else {
                        val numInt = number.toIntOrNull()
                        val matchedByNum = if (numInt != null) ChannelRegistry.getChannelByNumber(numInt) else null
                        val matchedByName = if (matchedByNum == null && name.isNotEmpty()) ChannelRegistry.getChannelByName(name) else null
                        val matchedChannel = matchedByNum ?: matchedByName

                        if (matchedChannel != null) {
                            key = matchedChannel.uuid.ifEmpty { matchedChannel.id.toString() }
                            Log.i(TAG, "Fallback match for channel '$name' (#$number) -> Resolved HTSP Channel ID: $key")
                        } else if (serviceId > 0) {
                            key = serviceId.toString()
                        } else {
                            val allChannels = ChannelRegistry.getAllChannels()
                            if (allChannels.isNotEmpty()) {
                                val firstCh = allChannels.first()
                                key = firstCh.uuid.ifEmpty { firstCh.id.toString() }
                                Log.w(TAG, "Could not resolve channel key for URI $uri; falling back to first channel: ${firstCh.name}")
                            }
                        }
                    }

                    if (key.isEmpty() && ChannelRegistry.getAllChannels().isEmpty()) {
                        populateChannelRegistryFromServer()
                        val numInt = number.toIntOrNull()
                        val matchedByNum = if (numInt != null) ChannelRegistry.getChannelByNumber(numInt) else null
                        val matchedByName = if (matchedByNum == null && name.isNotEmpty()) ChannelRegistry.getChannelByName(name) else null
                        val matchedChannel = matchedByNum ?: matchedByName
                        if (matchedChannel != null) {
                            key = matchedChannel.uuid.ifEmpty { matchedChannel.id.toString() }
                        } else {
                            val allChannels = ChannelRegistry.getAllChannels()
                            if (allChannels.isNotEmpty()) {
                                val firstCh = allChannels.first()
                                key = firstCh.uuid.ifEmpty { firstCh.id.toString() }
                            }
                        }
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
                } else if (player != null && currentChannelUuid == streamKey) {
                    Log.i(TAG, "Channel StreamKey $streamKey resolved and already playing; skipping reload.")
                    notifyContentAllowed()
                    notifyVideoAvailable()
                } else {
                    isTimeShiftPaused = false
                    pausedAtMs = 0L
                    totalPausedMs = 0L
                    timeShiftStartMs = System.currentTimeMillis()
                    currentChannelUuid = streamKey
                    startPlayback(streamKey)
                }
            }
            return true
        }

        private fun releasePlayer(preserveChannel: Boolean = false) {
            val caller = Throwable().stackTrace.getOrNull(1)?.let { "${it.fileName}:${it.lineNumber} in ${it.methodName}" } ?: "unknown"
            if (settings.isStreamingDebugEnabled) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [RELEASE PLAYER] releasePlayer called (preserveChannel=$preserveChannel, caller=$caller)")
            }
            Log.d(TAG, "Releasing player (preserveChannel=$preserveChannel, caller=$caller)...")
            
            tuneJob?.cancel()
            tuneJob = null
            
            val savedChannel = if (preserveChannel) currentChannelUuid else null
            val savedUri = if (preserveChannel) currentUri else null
            currentChannelUuid = null
            currentUri = null
            val p = player ?: run {
                if (preserveChannel) {
                    currentChannelUuid = savedChannel
                    currentUri = savedUri
                }
                return
            }
            player = null
            try {
                p.clearVideoSurface()
                p.removeListener(this)
                p.pause()
                p.stop()
                p.clearMediaItems()
                p.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing player", e)
            }
            if (preserveChannel) {
                currentChannelUuid = savedChannel
                currentUri = savedUri
            }
        }

        /**
         * Called when the session is released by the Android TV system.
         */
        override fun onRelease() {
            if (settings.isStreamingDebugEnabled) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [SESSION RELEASE] onRelease called on TV Input Session")
            }
            Log.d(TAG, "onRelease called on session.")
            sessionJob.cancel()
            releasePlayer()
            HtspConnectionPool.releaseStreamingClient()
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

            if (playbackState == Player.STATE_BUFFERING && settings.isStreamingDebugEnabled) {
                if (hasReachedReadyState) {
                    Log.w(TAG, "TVH_STREAM_DEBUG: [REBUFFERING] ExoPlayer entered STATE_BUFFERING during active playback")
                } else {
                    Log.i(TAG, "TVH_STREAM_DEBUG: [INITIAL BUFFERING] ExoPlayer buffering for playback start")
                }
            }

            if (playbackState == Player.STATE_READY) {
                hasReachedReadyState = true
                if (timeShiftStartMs == 0L) {
                    timeShiftStartMs = System.currentTimeMillis()
                }
                notifyTimeShiftStatusChanged(TvInputManager.TIME_SHIFT_STATUS_AVAILABLE)
                notifyContentAllowed()
                notifyVideoAvailable()
                restoreVolumeIfNeeded()
            }
        }

        override fun onTimeShiftPause() {
            Log.i(TAG, "onTimeShiftPause called")
            if (!isTimeShiftPaused) {
                isTimeShiftPaused = true
                pausedAtMs = System.currentTimeMillis()
                player?.pause()
                HtspConnectionPool.setStreamingSpeedAsync(0)
            }
        }

        override fun onTimeShiftResume() {
            Log.i(TAG, "onTimeShiftResume called")
            if (isTimeShiftPaused) {
                if (pausedAtMs > 0L) {
                    totalPausedMs += (System.currentTimeMillis() - pausedAtMs).coerceAtLeast(0L)
                }
                isTimeShiftPaused = false
                pausedAtMs = 0L
                HtspConnectionPool.setStreamingSpeedAsync(100)
                player?.play()
            }
        }

        override fun onTimeShiftSeekTo(timeMs: Long) {
            Log.i(TAG, "onTimeShiftSeekTo called: timeMs=$timeMs")
            val p = player ?: return
            if (p.isCurrentMediaItemSeekable) {
                val targetPos = (timeMs - timeShiftStartMs).coerceAtLeast(0L)
                p.seekTo(targetPos)
            }
        }

        override fun onTimeShiftSetPlaybackParams(params: PlaybackParams) {
            Log.i(TAG, "onTimeShiftSetPlaybackParams called: speed=${params.speed}")
            if (params.speed == 0f) {
                onTimeShiftPause()
            } else {
                onTimeShiftResume()
            }
        }

        override fun onTimeShiftGetStartPosition(): Long {
            return if (timeShiftStartMs > 0L) timeShiftStartMs else System.currentTimeMillis()
        }

        override fun onTimeShiftGetCurrentPosition(): Long {
            val now = System.currentTimeMillis()
            if (timeShiftStartMs <= 0L) return now
            val activePause = if (isTimeShiftPaused && pausedAtMs > 0L) (now - pausedAtMs).coerceAtLeast(0L) else 0L
            return (now - totalPausedMs - activePause).coerceAtLeast(timeShiftStartMs)
        }

        /**
         * ExoPlayer listener callback triggered when video dimensions are determined or updated.
         *
         * @param videoSize Video width, height, and rotation properties.
         */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (settings.isStreamingDebugEnabled) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [VIDEO RESOLUTION] Resolution: ${videoSize.width}x${videoSize.height}, unappliedRotation=${videoSize.unappliedRotationDegrees}")
            }
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
         * Called when a track selection request is issued by the framework or TvView.
         */
        override fun onSelectTrack(type: Int, trackId: String?): Boolean {
            Log.i(TAG, "onSelectTrack called: type=$type, trackId=$trackId")
            val p = player ?: return false

            val trackType = when (type) {
                TvTrackInfo.TYPE_AUDIO -> C.TRACK_TYPE_AUDIO
                TvTrackInfo.TYPE_VIDEO -> C.TRACK_TYPE_VIDEO
                TvTrackInfo.TYPE_SUBTITLE -> C.TRACK_TYPE_TEXT
                else -> return false
            }

            if (trackId == null) {
                val builder = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(trackType, true)
                p.trackSelectionParameters = builder.build()
                notifyTrackSelected(type, null)
                return true
            }

            var found = false
            for (group in p.currentTracks.groups) {
                if (group.type == trackType) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val id = format.id ?: "$i"
                        if (id == trackId) {
                            val override = TrackSelectionOverride(group.mediaTrackGroup, i)
                            val builder = p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(trackType, false)
                                .setOverrideForType(override)
                            p.trackSelectionParameters = builder.build()
                            notifyTrackSelected(type, trackId)
                            found = true
                            break
                        }
                    }
                }
                if (found) break
            }
            return found
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
            var selectedSubtitleTrackId: String? = null

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
                        Log.i(TAG, "TVH_STREAM_DEBUG: [TRACK CHANGED] Audio track $trackId: mime=${format.sampleMimeType}, rate=${format.sampleRate}, ch=${format.channelCount}, selected=$isSelected")
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
                    } else if (group.type == C.TRACK_TYPE_TEXT) {
                        val builder = TvTrackInfo.Builder(TvTrackInfo.TYPE_SUBTITLE, trackId)
                        val lang = format.language
                        if (!lang.isNullOrEmpty()) {
                            builder.setLanguage(lang)
                        }
                        tvTracks.add(builder.build())
                        if (isSelected) selectedSubtitleTrackId = trackId
                    }
                }
            }

            Log.i(TAG, "Notifying ${tvTracks.size} TV tracks to framework: selectedVideo=$selectedVideoTrackId, selectedAudio=$selectedAudioTrackId, selectedSubtitle=$selectedSubtitleTrackId")
            notifyTracksChanged(tvTracks)

            if (selectedVideoTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, selectedVideoTrackId)
            }
            if (selectedAudioTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, selectedAudioTrackId)
            }
            if (selectedSubtitleTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_SUBTITLE, selectedSubtitleTrackId)
            }
            restoreVolumeIfNeeded()
        }

        /**
         * ExoPlayer listener callback triggered when the very first video frame is rendered onto the surface.
         */
        override fun onRenderedFirstFrame() {
            TuneTelemetryTracker.shared.recordStage(TuneStage.VIDEO_AVAILABLE)
            val latency = if (tuneStartTimeMs > 0L) System.currentTimeMillis() - tuneStartTimeMs else -1L
            if (settings.isStreamingDebugEnabled) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [FIRST FRAME RENDERED] First video frame rendered on surface in ${latency}ms from tune request (channel $currentChannelUuid)")
            } else {
                Log.i(TAG, "=== ExoPlayer rendered first video frame successfully! ===")
            }
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
            if (player == null) return
            if (settings.isStreamingDebugEnabled) {
                Log.e(TAG, "TVH_STREAM_DEBUG: [PLAYER ERROR] errorCodeName=${error.errorCodeName}, message=${error.message}, cause=${error.cause?.message}", error.cause ?: error)
            }
            Log.e(TAG, "ExoPlayback error encountered: errorCodeName = ${error.errorCodeName}, message = ${error.message}", error)

            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                Log.w(TAG, "Player fell behind live window. Re-initializing at default position.")
                player?.seekToDefaultPosition()
                player?.prepare()
                return
            }

            val cause = error.cause
            val isSurfaceOrCodecError = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                cause is MediaCodec.CodecException ||
                (cause != null && cause.message?.contains("obsolete", ignoreCase = true) == true)

            val channelUuid = currentChannelUuid
            if (isSurfaceOrCodecError && !channelUuid.isNullOrEmpty() && player != null) {
                if (surface == null || surface?.isValid == false) {
                    Log.w(TAG, "Surface is invalid/detached during decoder error (${error.errorCodeName}); releasing player until surface re-attaches.")
                    releasePlayer(preserveChannel = true)
                } else {
                    Log.w(TAG, "Hardware decoder failure detected (${error.errorCodeName}); recreating player instance for channel $channelUuid.")
                    startPlayback(channelUuid)
                }
                return
            }

            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        }
    }
}

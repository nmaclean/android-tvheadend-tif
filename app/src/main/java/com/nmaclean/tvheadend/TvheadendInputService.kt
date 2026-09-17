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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

@UnstableApi
class TvheadendInputService : TvInputService() {
    companion object {
        const val TAG = "TvheadendInputService"
    }

    override fun onCreateSession(inputId: String): Session {
        return TvheadendSession()
    }

    inner class TvheadendSession : Session(this@TvheadendInputService), Player.Listener {
        private var player: ExoPlayer? = null
        private var currentChannelUuid: String? = null
        private val settings = TvhSettings(baseContext)
        private var surface: Surface? = null
        private val sessionJob = SupervisorJob()
        private val sessionScope = CoroutineScope(Dispatchers.Main + sessionJob)
        private var tuneJob: Job? = null

        override fun onSetSurface(surface: Surface?): Boolean {
            val isValid = surface?.isValid == true
            Log.d(TAG, "onSetSurface called with surface: $surface, isValid=$isValid")
            this.surface = if (isValid) surface else null

            // Attach or clear the surface immediately if the player instance already exists
            player?.setVideoSurface(this.surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            Log.d(TAG, "onSetStreamVolume called with volume: $volume")
            player?.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {
            // Optional: Closed captions handling
        }

        override fun onTune(unhandledUri: Uri, params: Bundle?): Boolean {
            Log.d(TAG, "onTune with params called for URI: $unhandledUri, params: $params")
            return onTune(unhandledUri)
        }

        override fun onTune(uri: Uri): Boolean {
            Log.d(TAG, "Tune URI received: $uri")
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
                    Log.d(TAG, "Column service_id = $serviceId")
                    Log.d(TAG, "Resolved StreamKey (UUID/ID) = $key")
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

            Log.d(TAG, "Starting NATIVE HTSP playback for channel: $uuid on $host:$port")

            val streamUri = Uri.parse("htsp://$host:$port/$uuid")
            releasePlayer()

            // Enable decoder fallback to prevent MediaTek hardware decoders on Sony TVs from rejecting interlaced/custom profiles
            val renderersFactory = DefaultRenderersFactory(baseContext).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                setEnableDecoderFallback(true)
            }

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 2000,
                    /* maxBufferMs = */ 30000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val exoPlayer = ExoPlayer.Builder(baseContext, renderersFactory)
                .setLoadControl(loadControl)
                .build().apply {
                    setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)

                    // Bind surface if already delivered by the system before or during tuning
                    if (this@TvheadendSession.surface?.isValid == true) {
                        Log.d(TAG, "Binding existing valid surface to ExoPlayer.")
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
            player?.release()
            player = null
        }

        override fun onRelease() {
            Log.d(TAG, "onRelease called on session.")
            sessionJob.cancel()
            releasePlayer()
        }

        override fun onUnblockContent(unblockedRating: TvContentRating?) {
            Log.d(TAG, "onUnblockContent called for rating: $unblockedRating")
            notifyContentAllowed()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "STATE_IDLE"
                Player.STATE_BUFFERING -> "STATE_BUFFERING"
                Player.STATE_READY -> "STATE_READY"
                Player.STATE_ENDED -> "STATE_ENDED"
                else -> "UNKNOWN ($playbackState)"
            }
            Log.d(TAG, "ExoPlayer playback state changed: $stateStr ($playbackState)")

            if (playbackState == Player.STATE_READY) {
                notifyContentAllowed()
                notifyVideoAvailable()
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(TAG, "ExoPlayer video size changed: width = ${videoSize.width}, height = ${videoSize.height}, unappliedRotation = ${videoSize.unappliedRotationDegrees}")
        }

        override fun onIsLoadingChanged(isLoading: Boolean) {
            Log.d(TAG, "ExoPlayer isLoading changed: $isLoading")
        }

        override fun onTracksChanged(tracks: Tracks) {
            Log.d(TAG, "ExoPlayer tracks changed: group count = ${tracks.groups.size}")
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

            Log.d(TAG, "Notifying ${tvTracks.size} TV tracks to framework")
            notifyTracksChanged(tvTracks)

            if (selectedVideoTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_VIDEO, selectedVideoTrackId)
            }
            if (selectedAudioTrackId != null) {
                notifyTrackSelected(TvTrackInfo.TYPE_AUDIO, selectedAudioTrackId)
            }
        }

        override fun onRenderedFirstFrame() {
            Log.d(TAG, "ExoPlayer rendered first video frame successfully!")
            notifyContentAllowed()
            notifyVideoAvailable()
        }

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
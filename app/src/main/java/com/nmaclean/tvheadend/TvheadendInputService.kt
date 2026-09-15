package com.nmaclean.tvheadend

import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import okhttp3.Credentials
import java.nio.charset.StandardCharsets

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
        private var htspClient: HtspClient? = null
        private val settings = TvhSettings(baseContext)
        private var surface: Surface? = null

        override fun onSetSurface(surface: Surface?): Boolean {
            Log.d(TAG, "onSetSurface called with surface: $surface")
            if (surface != null) {
                this.surface = surface
            }
            player?.setVideoSurface(this.surface)
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            Log.d(TAG, "onSetStreamVolume called with volume: $volume")
            player?.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {
            // Optional: Handle closed captions
        }

        override fun onTune(uri: Uri): Boolean {
            Log.d(TAG, "Tune URI received: $uri")
            val cursor = contentResolver.query(
                uri,
                arrayOf(
                    android.media.tv.TvContract.Channels._ID,
                    android.media.tv.TvContract.Channels.COLUMN_DISPLAY_NAME,
                    android.media.tv.TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                ),
                null, null, null
            )

            if (cursor == null || !cursor.moveToFirst()) {
                Log.e(TAG, "Cursor query failed or empty for URI: $uri")
                cursor?.close()
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                return false
            }

            val idCol = cursor.getColumnIndex(android.media.tv.TvContract.Channels._ID)
            val nameCol = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_DISPLAY_NAME)
            val numCol = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_DISPLAY_NUMBER)
            val dataCol = cursor.getColumnIndex(android.media.tv.TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)

            val channelId = if (idCol >= 0) cursor.getLong(idCol) else 0L
            val name = if (nameCol >= 0) cursor.getString(nameCol) else ""
            val number = if (numCol >= 0) cursor.getString(numCol) else ""

            val providerDataBytes = if (dataCol >= 0) cursor.getBlob(dataCol) else null
            val streamKey = if (providerDataBytes != null) String(providerDataBytes, StandardCharsets.UTF_8).trim() else ""

            cursor.close()

            Log.d(TAG, "Column _id = $channelId")
            Log.d(TAG, "Column display_name = $name")
            Log.d(TAG, "Column display_number = $number")
            Log.d(TAG, "Column internal_provider_data (UUID/ID) = $streamKey")

            if (streamKey.isEmpty()) {
                Log.e(TAG, "ERROR: Channel UUID / StreamKey is empty! Cannot tune.")
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                return false
            }

            currentChannelUuid = streamKey
            startPlayback(streamKey)
            return true
        }

        private fun startPlayback(uuid: String) {
            val host = settings.host
            val port = settings.htspPort
            val httpPort = settings.httpPort
            val user = settings.username
            val pass = settings.password

            Log.d(TAG, "Starting playback for streamKey: $uuid on host $host (HTSP port: $port, HTTP port: $httpPort)")

            kotlin.concurrent.thread {
                try {
                    Log.d(TAG, "Disconnecting previous HTSP client if any...")
                    htspClient?.disconnect()
                    val client = HtspClient(host, port)
                    Log.d(TAG, "Connecting to HTSP server at $host:$port...")
                    if (client.connect(user, pass)) {
                        Log.d(TAG, "HTSP connected successfully. Subscribing to channel: $uuid")
                        val subResponse = client.subscribe(uuid)
                        Log.d(TAG, "HTSP subscription response: $subResponse")
                        htspClient = client
                    } else {
                        Log.e(TAG, "HTSP connection failed during tune for $uuid")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in HTSP background tune notification", e)
                }
            }

            val streamUrl = "http://$host:$httpPort/stream/channel/$uuid?profile=matroska"
            Log.d(TAG, "Constructed Stream URL: $streamUrl (user=$user)")

            releasePlayer()

            val exoPlayer = ExoPlayer.Builder(baseContext).build().apply {
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("AndroidTV-TIFClient")
                    .setAllowCrossProtocolRedirects(true)

                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    Log.d(TAG, "Applying HTTP Basic Authentication credentials for ExoPlayer data source.")
                    httpDataSourceFactory.setDefaultRequestProperties(
                        mapOf("Authorization" to Credentials.basic(user, pass))
                    )
                }

                val mediaSource = ProgressiveMediaSource.Factory(httpDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))

                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(this@TvheadendSession)
            }
            player = exoPlayer

            if (this.surface != null) {
                Log.d(TAG, "Binding existing surface to ExoPlayer.")
                exoPlayer.setVideoSurface(this.surface)
            } else {
                Log.w(TAG, "Surface is null when starting playback; video surface not bound yet.")
            }
            notifyVideoAvailable()
        }

        private fun releasePlayer() {
            Log.d(TAG, "Releasing player and disconnecting HTSP client...")
            kotlin.concurrent.thread {
                try {
                    htspClient?.unsubscribe()
                    htspClient?.disconnect()
                    htspClient = null
                } catch (ignored: Exception) {}
            }
            player?.release()
            player = null
        }

        override fun onRelease() {
            Log.d(TAG, "onRelease called on session.")
            releasePlayer()
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
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayback error encountered: errorCodeName = ${error.errorCodeName}, message = ${error.message}", error)
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        }
    }
}
package com.nmaclean.tvheadend

import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource

class TvheadendInputService : TvInputService() {
    companion object {
        const val TAG = "TvheadendInputService"
    }

    override fun onCreateSession(inputId: String): Session {
        return TvhSession(this)
    }

    class TvhSession(private val context: Context) : TvInputService.Session(context), Player.Listener {
        private var player: ExoPlayer? = null
        private var surface: Surface? = null
        private var overlayView: View? = null
        private var channelTitleView: TextView? = null

        private var currentChannelUuid: String = ""

        override fun onCreateOverlayView(): View? {
            val inflater = LayoutInflater.from(context)
            overlayView = inflater.inflate(R.layout.tv_overlay_banner, null)
            channelTitleView = overlayView?.findViewById(R.id.channelTitleText)
            overlayView?.visibility = View.GONE
            return overlayView
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            this.surface = surface
            player?.setVideoSurface(surface)
            return true
        }

        override fun onSetCaptionEnabled(enabled: Boolean) {}

        override fun onTune(uri: Uri): Boolean {
            Log.d(TAG, "=== INSTANT TUNE CHANNEL ===")
            Log.d(TAG, "Tune URI received: $uri")

            currentChannelUuid = ""

            try {
                val projection = arrayOf(
                    TvContract.Channels._ID,
                    TvContract.Channels.COLUMN_DISPLAY_NAME,
                    TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                    TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                )
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    Log.d(TAG, "Cursor row count: ${it.count}")
                    if (it.moveToFirst()) {
                        val columnNames = it.columnNames
                        for (col in columnNames) {
                            val idx = it.getColumnIndex(col)
                            val type = it.getType(idx)
                            val valStr = when (type) {
                                android.database.Cursor.FIELD_TYPE_STRING -> it.getString(idx)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> it.getLong(idx).toString()
                                android.database.Cursor.FIELD_TYPE_BLOB -> {
                                    val blob = it.getBlob(idx)
                                    if (blob != null) String(blob, Charsets.UTF_8) else "null blob"
                                }
                                else -> "null/other"
                            }
                            Log.d(TAG, "Column [$col] (type=$type) = $valStr")
                        }

                        val dataCol = it.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)
                        if (dataCol >= 0 && !it.isNull(dataCol)) {
                            val blob = it.getBlob(dataCol)
                            if (blob != null && blob.isNotEmpty()) {
                                currentChannelUuid = String(blob, Charsets.UTF_8)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to query channel details from URI $uri", e)
            }

            Log.d(TAG, "Resolved channel UUID/streamKey from TvContract: '$currentChannelUuid'")

            if (currentChannelUuid.isEmpty()) {
                Log.e(TAG, "ERROR: Channel UUID is empty! Cannot tune.")
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return false
            }

            tuneToChannel(currentChannelUuid)
            return true
        }

        private fun tuneToChannel(uuid: String) {
            releasePlayer()

            val prefs = context.getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)
            val host = prefs.getString("host", "192.168.4.100") ?: "192.168.4.100"
            val user = prefs.getString("user", "admin") ?: "admin"
            val pass = prefs.getString("pass", "ab1903") ?: "ab1903"

            val streamUrl = "http://$host:9981/stream/channel/$uuid?profile=matroska"
            Log.d(TAG, "Constructed Stream URL: $streamUrl (user=$user)")

            val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
            val credentials = "$user:$pass"
            val authHeader = "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to authHeader))
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(10000)
                .setUserAgent("AndroidTV-TIFClient")

            player = ExoPlayer.Builder(context).build().apply {
                addListener(this@TvhSession)
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                surface?.let { setVideoSurface(it) }
            }

            channelTitleView?.text = "Channel: $uuid"
            showOverlayBriefly()
            notifyVideoAvailable()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val stateStr = when (playbackState) {
                Player.STATE_IDLE -> "STATE_IDLE"
                Player.STATE_BUFFERING -> "STATE_BUFFERING"
                Player.STATE_READY -> "STATE_READY"
                Player.STATE_ENDED -> "STATE_ENDED"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "ExoPlayer playback state changed: $stateStr ($playbackState)")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "ExoPlayer isPlaying changed: $isPlaying")
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> return true
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    toggleOverlay()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }

        private fun showOverlayBriefly() {
            setOverlayViewEnabled(true)
            overlayView?.visibility = View.VISIBLE
            overlayView?.handler?.postDelayed({
                overlayView?.visibility = View.GONE
                setOverlayViewEnabled(false)
            }, 3000)
        }

        private fun toggleOverlay() {
            if (overlayView?.visibility == View.VISIBLE) {
                overlayView?.visibility = View.GONE
                setOverlayViewEnabled(false)
            } else {
                setOverlayViewEnabled(true)
                overlayView?.visibility = View.VISIBLE
            }
        }

        override fun onSetStreamVolume(volume: Float) {
            player?.volume = volume
        }

        override fun onRelease() {
            releasePlayer()
        }

        private fun releasePlayer() {
            player?.release()
            player = null
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "ExoPlayback error: ${error.errorCodeName} - ${error.message}", error)
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
        }
    }
}

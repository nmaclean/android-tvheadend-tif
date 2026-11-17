package com.nmaclean.tvheadend.ui

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.player.HtspDvrDataSource
import com.nmaclean.tvheadend.sync.DvrRepository
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.TvTheme
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay

/**
 * Fullscreen DVR recording player over HTSP (`fileOpen`/`fileRead`) with HTTP fallback,
 * D-pad +/-15s & +/-60s seeking, resume prompt, and Midnight-Cyan playback HUD.
 */
@OptIn(UnstableApi::class)
class DvrPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_DVR_ID = "dvr_id"
        const val EXTRA_TITLE = "dvr_title"
        const val EXTRA_CHANNEL_NAME = "dvr_channel_name"
        const val EXTRA_RESUME_MS = "dvr_resume_ms"
        private const val TAG = "DvrPlayerActivity"
    }

    private var player: ExoPlayer? = null
    private var dvrId: Long = -1L
    private var title: String = "Recording"
    private var channelName: String = ""
    private var usedHttpFallback = false

    private var hudVisible by mutableStateOf(true)
    private var hudInteractionTick by mutableIntStateOf(0)
    private var isPlayingState by mutableStateOf(true)
    private var positionMsState by mutableLongStateOf(0L)
    private var durationMsState by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        dvrId = intent.getLongExtra(EXTRA_DVR_ID, -1L)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Recording"
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        val passedResume = intent.getLongExtra(EXTRA_RESUME_MS, 0L)
        val savedResume = maxOf(passedResume, DvrRepository.getResumePosition(this, dvrId))

        val exo = ExoPlayer.Builder(this).build().also { p ->
            player = p
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    isPlayingState = isPlaying
                    if (!isPlaying) persistResumePosition()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "HTSP DVR playback error (${error.message}); attempting HTTP /dvrfile fallback")
                    if (!usedHttpFallback) {
                        usedHttpFallback = true
                        loadHttpFallback(p, savedResume)
                    }
                }
            })
        }

        startHtspPlayback(exo)

        if (savedResume > 5_000L) {
            val mins = (savedResume / 60_000L).toInt()
            val secs = ((savedResume % 60_000L) / 1000L).toInt()
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Resume Playback?")
                .setMessage("Resume \"$title\" from ${String.format("%02d:%02d", mins, secs)}?")
                .setPositiveButton("Resume") { _, _ ->
                    exo.seekTo(savedResume)
                    exo.playWhenReady = true
                }
                .setNegativeButton("Start Over") { _, _ ->
                    exo.seekTo(0L)
                    exo.playWhenReady = true
                }
                .show()
        } else {
            exo.playWhenReady = true
        }

        setContent {
            TvTheme {
                DvrPlayerScreen(
                    player = exo,
                    title = title,
                    channelName = channelName,
                    hudVisible = hudVisible,
                    hudInteractionTick = hudInteractionTick,
                    isPlaying = isPlayingState,
                    positionMs = positionMsState,
                    durationMs = durationMsState,
                    onPollProgress = {
                        positionMsState = exo.currentPosition.coerceAtLeast(0L)
                        durationMsState = exo.duration.coerceAtLeast(0L)
                    },
                    onHudTimeout = { hudVisible = false }
                )
            }
        }
    }

    private fun startHtspPlayback(exo: ExoPlayer) {
        val settings = TvhSettings(this)
        val uri = Uri.parse("htspdvr://${settings.host}:${settings.htspPort}/$dvrId")
        val source = ProgressiveMediaSource.Factory(HtspDvrDataSource.Factory(this))
            .createMediaSource(MediaItem.fromUri(uri))
        exo.setMediaSource(source)
        exo.prepare()
    }

    private fun loadHttpFallback(exo: ExoPlayer, seekPosMs: Long) {
        val settings = TvhSettings(this)
        val url = "http://${settings.host}:${settings.httpPort}/dvrfile/$dvrId"
        val headers = if (settings.username.isNotBlank()) {
            val creds = "${settings.username}:${settings.password}"
            val encoded = Base64.encodeToString(creds.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            mapOf("Authorization" to "Basic $encoded")
        } else emptyMap()
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val source = ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        exo.setMediaSource(source)
        exo.prepare()
        if (seekPosMs > 0L) exo.seekTo(seekPosMs)
        exo.playWhenReady = true
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (p.currentPosition + deltaMs).coerceIn(0L, dur)
        p.seekTo(target)
        positionMsState = target
        hudVisible = true
        hudInteractionTick++
    }

    private fun persistResumePosition() {
        val p = player ?: return
        DvrRepository.saveResumePosition(
            this,
            dvrId,
            p.currentPosition.coerceAtLeast(0L),
            p.duration.coerceAtLeast(0L)
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-15_000L)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(15_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-60_000L)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(60_000L)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val p = player
                if (p != null) {
                    p.playWhenReady = !p.playWhenReady
                }
                hudVisible = true
                hudInteractionTick++
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                hudVisible = !hudVisible
                hudInteractionTick++
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (hudVisible) {
                    hudVisible = false
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        persistResumePosition()
        super.onStop()
    }

    override fun onDestroy() {
        persistResumePosition()
        player?.release()
        player = null
        super.onDestroy()
    }
}

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DvrPlayerScreen(
    player: ExoPlayer,
    title: String,
    channelName: String,
    hudVisible: Boolean,
    hudInteractionTick: Int,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPollProgress: () -> Unit,
    onHudTimeout: () -> Unit
) {
    LaunchedEffect(Unit) {
        while (true) {
            onPollProgress()
            delay(500L)
        }
    }

    LaunchedEffect(hudVisible, hudInteractionTick) {
        if (hudVisible) {
            delay(5_000L)
            onHudTimeout()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xAA060A12), Color.Transparent)))
                        .padding(
                            horizontal = MidnightCyanTokens.OverscanHorizontal,
                            vertical = MidnightCyanTokens.OverscanVertical
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MidnightCyanTokens.CardBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isPlaying) "RECORDING • PLAYING" else "RECORDING • PAUSED",
                            color = MidnightCyanTokens.CyanAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                    }
                    TvClockHeader()
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xCC060A12), Color(0xF2060A12))
                            )
                        )
                        .padding(
                            horizontal = MidnightCyanTokens.OverscanHorizontal,
                            vertical = MidnightCyanTokens.OverscanVertical
                        )
                ) {
                    if (channelName.isNotBlank()) {
                        Text(
                            text = channelName,
                            color = MidnightCyanTokens.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = title,
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val fraction = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(5.dp)
                                .background(MidnightCyanTokens.CyanAccent)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                            color = MidnightCyanTokens.CyanAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "◄ ► ±15s   |   OK Play/Pause",
                            color = MidnightCyanTokens.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSec = ms / 1000L
    val hours = totalSec / 3600L
    val mins = (totalSec % 3600L) / 60L
    val secs = totalSec % 60L
    return if (hours > 0L) {
        String.format("%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

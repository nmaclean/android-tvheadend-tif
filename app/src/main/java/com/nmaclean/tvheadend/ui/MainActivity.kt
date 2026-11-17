package com.nmaclean.tvheadend.ui

import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.htsp.HtspConnectionPool
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhRecording
import com.nmaclean.tvheadend.model.TvhSeriesRule
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.sync.TvhSyncWorker
import com.nmaclean.tvheadend.ui.components.TvhNavDestination
import com.nmaclean.tvheadend.ui.components.TvhNavigationRail
import com.nmaclean.tvheadend.ui.screens.GuideScreen
import com.nmaclean.tvheadend.ui.screens.LiveTvScreen
import com.nmaclean.tvheadend.ui.screens.RecordingsScreen
import com.nmaclean.tvheadend.ui.screens.SearchScreen
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTheme
import kotlinx.coroutines.launch

/**
 * Main Android TV Launcher Activity hosting the Midnight-Navy & Electric-Cyan Jetpack Compose for TV shell.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = TvhSettings(this)
        if (!settings.isFirstInstallCleanupDone) {
            settings.isFirstInstallCleanupDone = true
            TvhSyncManager.clearAllChannelsAndPrograms(this)
        }

        TvhSyncWorker.schedulePeriodicSync(this)
        HtspConnectionPool.preconnectAsync(this)

        setContent {
            TvTheme {
                TvhLauncherShell(
                    onTuneChannel = { channel -> launchPlayerForChannel(channel) },
                    onOpenSettings = { startActivity(Intent(this, InputManagerActivity::class.java)) },
                    onScheduleProgram = { channel, program, onRefresh ->
                        promptScheduleProgram(channel, program, onRefresh)
                    },
                    onPlayRecording = { rec, onRefresh ->
                        promptRecordingAction(rec, onRefresh)
                    },
                    onCancelScheduled = { rec, onRefresh ->
                        promptCancelScheduled(rec, onRefresh)
                    },
                    onDeleteSeriesRule = { rule, onRefresh ->
                        promptDeleteSeriesRule(rule, onRefresh)
                    }
                )
            }
        }
    }

    @Composable
    private fun TvhLauncherShell(
        onTuneChannel: (TvhChannel) -> Unit,
        onOpenSettings: () -> Unit,
        onScheduleProgram: (TvhChannel, EpgRepository.ProgramInfo, () -> Unit) -> Unit,
        onPlayRecording: (TvhRecording, () -> Unit) -> Unit,
        onCancelScheduled: (TvhRecording, () -> Unit) -> Unit,
        onDeleteSeriesRule: (TvhSeriesRule, () -> Unit) -> Unit
    ) {
        var selectedDestination by remember { mutableStateOf(TvhNavDestination.LIVE_TV) }
        var channels by remember { mutableStateOf<List<TvhChannel>>(emptyList()) }
        var currentEpg by remember { mutableStateOf<Map<Long, EpgRepository.ChannelEpg>>(emptyMap()) }
        var guidePrograms by remember { mutableStateOf<Map<Long, List<EpgRepository.ProgramInfo>>>(emptyMap()) }
        var recordings by remember { mutableStateOf<List<TvhRecording>>(emptyList()) }
        var seriesRules by remember { mutableStateOf<List<TvhSeriesRule>>(emptyList()) }
        var isLoadingChannels by remember { mutableStateOf(true) }
        var isLoadingDvr by remember { mutableStateOf(false) }
        var windowStartMs by remember { mutableLongStateOf(System.currentTimeMillis() - 30 * 60_000L) }
        var dvrRefreshTick by remember { mutableIntStateOf(0) }
        val guideGridFocusRequester = remember { FocusRequester() }
        var focusGuideTick by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            isLoadingChannels = true
            val loadedChannels = TvhUiRepository.loadChannels(this@MainActivity)
            channels = loadedChannels
            currentEpg = TvhUiRepository.loadCurrentEpgMap(this@MainActivity, loadedChannels)
            isLoadingChannels = false
        }

        LaunchedEffect(selectedDestination, channels) {
            if (selectedDestination == TvhNavDestination.GUIDE && channels.isNotEmpty()) {
                val now = System.currentTimeMillis()
                windowStartMs = now - 30 * 60_000L
                guidePrograms = TvhUiRepository.loadGuideWindow(
                    this@MainActivity,
                    channels,
                    windowStartMs,
                    windowStartMs + 4 * 60 * 60_000L
                )
            }
        }

        LaunchedEffect(selectedDestination, dvrRefreshTick) {
            if (selectedDestination == TvhNavDestination.RECORDINGS) {
                isLoadingDvr = true
                val (recs, rules) = TvhUiRepository.loadDvrState(this@MainActivity)
                recordings = recs
                seriesRules = rules
                isLoadingDvr = false
            }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MidnightCyanTokens.BackgroundBrush)
        ) {
            TvhNavigationRail(
                selectedDestination = selectedDestination,
                onSelectDestination = { dest ->
                    if (dest == TvhNavDestination.SETTINGS) {
                        onOpenSettings()
                    } else {
                        selectedDestination = dest
                        if (dest == TvhNavDestination.GUIDE) {
                            focusGuideTick++
                        }
                    }
                }
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedDestination) {
                    TvhNavDestination.SEARCH -> SearchScreen(
                        channels = channels,
                        currentEpg = currentEpg,
                        onChannelClick = onTuneChannel
                    )
                    TvhNavDestination.LIVE_TV -> LiveTvScreen(
                        channels = channels,
                        currentEpg = currentEpg,
                        isLoading = isLoadingChannels,
                        onChannelClick = onTuneChannel
                    )
                    TvhNavDestination.GUIDE -> {
                        LaunchedEffect(focusGuideTick, channels.size) {
                            if (focusGuideTick > 0 && channels.isNotEmpty()) {
                                kotlinx.coroutines.delay(50L)
                                try {
                                    guideGridFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                            }
                        }
                        GuideScreen(
                            channels = channels,
                            currentEpg = currentEpg,
                            guidePrograms = guidePrograms,
                            windowStartMs = windowStartMs,
                            onTuneChannel = onTuneChannel,
                            onScheduleProgram = { ch, prog ->
                                onScheduleProgram(ch, prog) { dvrRefreshTick++ }
                            },
                            gridFocusRequester = guideGridFocusRequester
                        )
                    }
                    TvhNavDestination.RECORDINGS -> RecordingsScreen(
                        recordings = recordings,
                        seriesRules = seriesRules,
                        isLoading = isLoadingDvr,
                        onPlayRecording = { rec ->
                            onPlayRecording(rec) { dvrRefreshTick++ }
                        },
                        onCancelScheduled = { rec ->
                            onCancelScheduled(rec) { dvrRefreshTick++ }
                        },
                        onDeleteSeriesRule = { rule ->
                            onDeleteSeriesRule(rule) { dvrRefreshTick++ }
                        }
                    )
                    TvhNavDestination.SETTINGS -> {
                        // Settings opens InputManagerActivity directly from the rail
                    }
                }
            }
        }
    }

    private fun launchPlayerForChannel(channel: TvhChannel) {
        val intent = Intent(this, TvPlayerActivity::class.java).apply {
            putExtra("channel_id", channel.id.toString())
            putExtra("channel_name", channel.name)
            putExtra("channel_number", channel.number)
            putExtra("input_id", channel.inputId)
        }
        startActivity(intent)
    }

    private fun promptScheduleProgram(
        channel: TvhChannel,
        program: EpgRepository.ProgramInfo,
        onRefresh: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(program.title)
            .setMessage("${channel.name} • ${TvhUiRepository.formatTimeRange(program.startTimeMs, program.endTimeMs)}")
            .setPositiveButton("Record Once") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = TvhUiRepository.scheduleRecording(this@MainActivity, program.eventId)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Scheduled '${program.title}'" else "Could not schedule recording",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNeutralButton("Record Series") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = TvhUiRepository.addSeriesRule(this@MainActivity, program.title, channel.id)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Added series rule for '${program.title}'" else "Could not add series rule",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNegativeButton("Tune Live") { _, _ ->
                launchPlayerForChannel(channel)
            }
            .show()
    }

    private fun promptRecordingAction(rec: TvhRecording, onRefresh: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(rec.title)
            .setMessage("${rec.channelName} • ${rec.durationMinutes} min")
            .setPositiveButton("Play") { _, _ ->
                val url = TvhUiRepository.buildRecordingStreamUrl(this, rec.id)
                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "video/*")
                }
                try {
                    startActivity(playIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "No external video player available for $url", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Delete") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = TvhUiRepository.deleteRecording(this@MainActivity, rec.id)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Deleted '${rec.title}'" else "Failed to delete recording",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun promptCancelScheduled(rec: TvhRecording, onRefresh: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Scheduled Recording?")
            .setMessage("${rec.title} (${rec.channelName})")
            .setPositiveButton("Cancel Timer") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = TvhUiRepository.deleteRecording(this@MainActivity, rec.id)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Cancelled '${rec.title}'" else "Failed to cancel timer",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun promptDeleteSeriesRule(rule: TvhSeriesRule, onRefresh: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete Series Rule?")
            .setMessage("${rule.title} (${rule.channelName})")
            .setPositiveButton("Delete") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = TvhUiRepository.deleteSeriesRule(this@MainActivity, rule.id)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Removed rule '${rule.title}'" else "Failed to remove rule",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, InputManagerActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

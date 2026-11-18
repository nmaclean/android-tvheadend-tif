package com.nmaclean.tvheadend.ui

import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.nmaclean.tvheadend.sync.DvrRepository
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.sync.TvhSyncWorker
import com.nmaclean.tvheadend.ui.components.ProgramDetailsModal
import com.nmaclean.tvheadend.ui.components.TvhActionModal
import com.nmaclean.tvheadend.ui.components.TvhModalAction
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

    private val shortcutNavTick = mutableIntStateOf(0)
    private val shortcutDestination = mutableStateOf<TvhNavDestination?>(null)
    private val restoreRefreshTick = mutableIntStateOf(0)
    private val needsSetupState = mutableStateOf(false)

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val ok = com.nmaclean.tvheadend.model.SettingsBackupManager.importFromUri(this@MainActivity, uri)
            if (ok) {
                TvhSyncManager.performSync(this@MainActivity)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    needsSetupState.value = false
                    restoreRefreshTick.intValue++
                    Toast.makeText(this@MainActivity, "Restored Tvheadend settings from backup", Toast.LENGTH_LONG).show()
                }
            } else {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Could not read settings backup file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val dvrPlayerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            DvrRepository.refresh(this@MainActivity, force = true)
        }
    }

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
        val recordings by DvrRepository.recordings.collectAsState()
        val seriesRules by DvrRepository.seriesRules.collectAsState()
        val scheduledEventIds by DvrRepository.scheduledEventIds.collectAsState()
        val seriesTitles by DvrRepository.seriesTitles.collectAsState()
        val isLoadingDvr by DvrRepository.isLoading.collectAsState()
        val dvrError by DvrRepository.errorMessage.collectAsState()
        var isLoadingChannels by remember { mutableStateOf(true) }
        var windowStartMs by remember { mutableLongStateOf(System.currentTimeMillis() - 30 * 60_000L) }
        var selectedProgramModal by remember { mutableStateOf<Pair<TvhChannel, EpgRepository.ProgramInfo>?>(null) }
        var selectedRecordingModal by remember { mutableStateOf<TvhRecording?>(null) }
        var selectedCancelModal by remember { mutableStateOf<TvhRecording?>(null) }
        var selectedSeriesModal by remember { mutableStateOf<TvhSeriesRule?>(null) }
        val guideGridFocusRequester = remember { FocusRequester() }
        var focusGuideTick by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(restoreRefreshTick.intValue) {
            isLoadingChannels = true
            val restoredFromBackup = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (com.nmaclean.tvheadend.model.SettingsBackupManager.tryAutoImportIfFresh(this@MainActivity)) {
                    TvhSyncManager.performSync(this@MainActivity)
                    true
                } else {
                    false
                }
            }
            if (restoredFromBackup) {
                Toast.makeText(this@MainActivity, "Restored Tvheadend settings from backup", Toast.LENGTH_LONG).show()
            }
            needsSetupState.value = TvhSettings(this@MainActivity).host.isBlank()
            val loadedChannels = TvhUiRepository.loadChannels(this@MainActivity)
            channels = loadedChannels
            isLoadingChannels = false
            // Single batch 12-hour EPG query powers both Live TV cards and the Guide timeline
            if (loadedChannels.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val startMs = now - 30 * 60_000L
                windowStartMs = startMs
                val loadedGuide = TvhUiRepository.loadGuideWindow(
                    this@MainActivity,
                    loadedChannels,
                    startMs,
                    startMs + 12 * 60 * 60_000L
                )
                guidePrograms = loadedGuide
                currentEpg = TvhUiRepository.deriveCurrentEpgFromWindow(loadedChannels, loadedGuide, now)
            }
        }

        LaunchedEffect(shortcutNavTick.intValue) {
            val dest = shortcutDestination.value
            if (shortcutNavTick.intValue > 0 && dest != null) {
                selectedDestination = dest
                if (dest == TvhNavDestination.GUIDE) {
                    focusGuideTick++
                }
            }
        }

        LaunchedEffect(selectedDestination, channels) {
            if (selectedDestination == TvhNavDestination.GUIDE && channels.isNotEmpty()) {
                val now = System.currentTimeMillis()
                windowStartMs = now - 30 * 60_000L
                guidePrograms = TvhUiRepository.loadGuideWindow(
                    this@MainActivity,
                    channels,
                    windowStartMs,
                    windowStartMs + 12 * 60 * 60_000L
                )
            }
        }

        LaunchedEffect(selectedDestination) {
            if (selectedDestination == TvhNavDestination.RECORDINGS || selectedDestination == TvhNavDestination.GUIDE) {
                DvrRepository.refresh(this@MainActivity)
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
                        recordings = recordings,
                        onChannelClick = onTuneChannel,
                        onRecordingClick = { rec -> selectedRecordingModal = rec }
                    )
                    TvhNavDestination.LIVE_TV -> LiveTvScreen(
                        channels = channels,
                        currentEpg = currentEpg,
                        isLoading = isLoadingChannels,
                        needsSetup = needsSetupState.value,
                        onRestoreBackup = { restoreBackupLauncher.launch(arrayOf("application/json", "*/*")) },
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
                            scheduledEventIds = scheduledEventIds,
                            seriesTitles = seriesTitles,
                            onTuneChannel = onTuneChannel,
                            onScheduleProgram = { ch, prog ->
                                selectedProgramModal = ch to prog
                            },
                            gridFocusRequester = guideGridFocusRequester
                        )
                    }
                    TvhNavDestination.RECORDINGS -> RecordingsScreen(
                        recordings = recordings,
                        seriesRules = seriesRules,
                        isLoading = isLoadingDvr,
                        errorMessage = dvrError,
                        onRetry = { scope.launch { DvrRepository.refresh(this@MainActivity, force = true) } },
                        onPlayRecording = { rec ->
                            selectedRecordingModal = rec
                        },
                        onCancelScheduled = { rec ->
                            selectedCancelModal = rec
                        },
                        onSeriesRuleClick = { rule ->
                            selectedSeriesModal = rule
                        }
                    )
                    TvhNavDestination.SETTINGS -> {
                        // Settings opens InputManagerActivity directly from the rail
                    }
                }

                val modalPair = selectedProgramModal
                if (modalPair != null) {
                    val (ch, prog) = modalPair
                    val now = System.currentTimeMillis()
                    val isLiveNow = now in prog.startTimeMs..prog.endTimeMs
                    val htspChId = produceState(
                        initialValue = ch.uuid.toLongOrNull() ?: 0L,
                        key1 = ch.id,
                        key2 = ch.uuid
                    ) {
                        if (value <= 0L) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                DvrRepository.resolveHtspChannelId(this@MainActivity, ch)
                            }
                        }
                    }.value
                    val existing = DvrRepository.findActiveRecording(
                        eventId = prog.eventId,
                        channelId = htspChId,
                        title = prog.title,
                        startTimeMs = prog.startTimeMs
                    )
                    val isScheduled = existing != null || (prog.eventId > 0L && prog.eventId in scheduledEventIds)
                    val hasSeries = prog.title.trim().lowercase() in seriesTitles
                    ProgramDetailsModal(
                        channel = ch,
                        program = prog,
                        isScheduled = isScheduled,
                        hasSeriesRule = hasSeries,
                        isLiveNow = isLiveNow,
                        onTuneLive = {
                            selectedProgramModal = null
                            onTuneChannel(ch)
                        },
                        onToggleRecordEpisode = {
                            scope.launch {
                                val currentExisting = DvrRepository.findActiveRecording(
                                    eventId = prog.eventId,
                                    channelId = htspChId,
                                    title = prog.title,
                                    startTimeMs = prog.startTimeMs
                                )
                                val ok = if (currentExisting != null) {
                                    DvrRepository.cancelTimer(this@MainActivity, currentExisting.id)
                                } else {
                                    DvrRepository.scheduleProgram(this@MainActivity, ch, prog)
                                }
                                if (!ok) {
                                    Toast.makeText(this@MainActivity, "DVR action failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onScheduleSeries = {
                            scope.launch {
                                val ok = DvrRepository.scheduleSeries(this@MainActivity, prog.title, htspChId)
                                if (!ok) {
                                    Toast.makeText(this@MainActivity, "Could not add series rule", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDismiss = { selectedProgramModal = null }
                    )
                }

                val recModal = selectedRecordingModal
                if (recModal != null) {
                    val resumePosMs = maxOf(
                        recModal.resumePositionMs,
                        DvrRepository.getResumePosition(this@MainActivity, recModal.id)
                    )
                    val hasResume = resumePosMs > 5_000L
                    val resumeTotalSec = resumePosMs / 1000L
                    val resumeMins = resumeTotalSec / 60L
                    val resumeSecs = resumeTotalSec % 60L
                    val resumeLabel = String.format("%02d:%02d", resumeMins, resumeSecs)

                    val actions = buildList {
                        if (hasResume) {
                            add(
                                TvhModalAction(
                                    label = "Resume ($resumeLabel)",
                                    selected = true,
                                    onClick = {
                                        selectedRecordingModal = null
                                        launchDvrPlayer(recModal, resumePosMs, autoResume = true)
                                    }
                                )
                            )
                            add(
                                TvhModalAction(
                                    label = "Start Over",
                                    onClick = {
                                        selectedRecordingModal = null
                                        launchDvrPlayer(recModal, 0L, autoResume = true)
                                    }
                                )
                            )
                        } else {
                            add(
                                TvhModalAction(
                                    label = "Play",
                                    selected = true,
                                    onClick = {
                                        selectedRecordingModal = null
                                        launchDvrPlayer(recModal, 0L, autoResume = true)
                                    }
                                )
                            )
                        }
                        add(
                            TvhModalAction(
                                label = "Delete",
                                onClick = {
                                    selectedRecordingModal = null
                                    scope.launch {
                                        val ok = DvrRepository.deleteRecording(this@MainActivity, recModal.id)
                                        if (!ok) {
                                            Toast.makeText(this@MainActivity, "Failed to delete recording", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            )
                        )
                        add(
                            TvhModalAction(
                                label = "Close",
                                onClick = { selectedRecordingModal = null }
                            )
                        )
                    }

                    TvhActionModal(
                        title = recModal.title,
                        subtitle = "${recModal.channelName} • ${recModal.durationMinutes} min",
                        body = recModal.description.ifBlank {
                            recModal.subtitle.ifBlank { "Recorded TV programme" }
                        },
                        badgeText = if (recModal.watchedPercent > 0) "WATCHED ${recModal.watchedPercent}%" else "RECORDED",
                        imageUrl = recModal.imageUrl.ifBlank { recModal.channelIcon },
                        fallbackLabel = recModal.channelName,
                        actions = actions,
                        onDismiss = { selectedRecordingModal = null }
                    )
                }

                val cancelModal = selectedCancelModal
                if (cancelModal != null) {
                    TvhActionModal(
                        title = "Cancel Scheduled Recording?",
                        subtitle = "${cancelModal.title} • ${cancelModal.channelName}",
                        body = TvhUiRepository.formatTimeRange(cancelModal.startTimeMs, cancelModal.stopTimeMs),
                        badgeText = "SCHEDULED TIMER",
                        imageUrl = cancelModal.imageUrl.ifBlank { cancelModal.channelIcon },
                        fallbackLabel = cancelModal.channelName,
                        actions = listOf(
                            TvhModalAction(
                                label = "Cancel Timer",
                                selected = true,
                                onClick = {
                                    selectedCancelModal = null
                                    scope.launch {
                                        val ok = DvrRepository.cancelTimer(this@MainActivity, cancelModal.id)
                                        if (!ok) {
                                            Toast.makeText(this@MainActivity, "Failed to cancel timer", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ),
                            TvhModalAction(
                                label = "Keep",
                                onClick = { selectedCancelModal = null }
                            )
                        ),
                        onDismiss = { selectedCancelModal = null }
                    )
                }

                val seriesModal = selectedSeriesModal
                if (seriesModal != null) {
                    TvhActionModal(
                        title = seriesModal.title,
                        subtitle = "${seriesModal.channelName} • ${if (seriesModal.enabled) "Active" else "Paused"}",
                        body = seriesModal.comment.ifBlank { "AutoRec series recording rule" },
                        badgeText = "SERIES RULE",
                        actions = listOf(
                            TvhModalAction(
                                label = if (seriesModal.enabled) "Pause Rule" else "Enable Rule",
                                selected = true,
                                onClick = {
                                    selectedSeriesModal = null
                                    scope.launch {
                                        val ok = DvrRepository.toggleSeriesRule(this@MainActivity, seriesModal)
                                        if (!ok) {
                                            Toast.makeText(this@MainActivity, "Failed to update rule", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ),
                            TvhModalAction(
                                label = "Delete Rule",
                                onClick = {
                                    selectedSeriesModal = null
                                    scope.launch {
                                        val ok = DvrRepository.deleteSeriesRule(this@MainActivity, seriesModal.id)
                                        if (!ok) {
                                            Toast.makeText(this@MainActivity, "Failed to remove rule", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ),
                            TvhModalAction(
                                label = "Close",
                                onClick = { selectedSeriesModal = null }
                            )
                        ),
                        onDismiss = { selectedSeriesModal = null }
                    )
                }
            }
        }
    }

    private fun launchDvrPlayer(rec: TvhRecording, resumePosMs: Long, autoResume: Boolean) {
        val playIntent = Intent(this, DvrPlayerActivity::class.java).apply {
            putExtra(DvrPlayerActivity.EXTRA_DVR_ID, rec.id)
            putExtra(DvrPlayerActivity.EXTRA_DVR_UUID, rec.uuid)
            putExtra(DvrPlayerActivity.EXTRA_TITLE, rec.title)
            putExtra(DvrPlayerActivity.EXTRA_CHANNEL_NAME, rec.channelName)
            putExtra(DvrPlayerActivity.EXTRA_RESUME_MS, resumePosMs)
            putExtra(DvrPlayerActivity.EXTRA_AUTO_RESUME, autoResume)
        }
        dvrPlayerLauncher.launch(playIntent)
    }

    private fun launchPlayerForChannel(channel: TvhChannel) {
        val intent = Intent(this, TvPlayerActivity::class.java).apply {
            putExtra("channel_id", channel.id.toString())
            putExtra("channel_uuid", channel.uuid)
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(program.title)
            .setMessage("${channel.name} • ${TvhUiRepository.formatTimeRange(program.startTimeMs, program.endTimeMs)}")
            .setPositiveButton("Record Once") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = DvrRepository.scheduleProgram(this@MainActivity, channel, program)
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
                    val htspChId = DvrRepository.resolveHtspChannelId(this@MainActivity, channel)
                    val ok = DvrRepository.scheduleSeries(this@MainActivity, program.title, htspChId)
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(rec.title)
            .setMessage("${rec.channelName} • ${rec.durationMinutes} min")
            .setPositiveButton("Play") { _, _ ->
                val playIntent = Intent(this, DvrPlayerActivity::class.java).apply {
                    putExtra(DvrPlayerActivity.EXTRA_DVR_ID, rec.id)
                    putExtra(DvrPlayerActivity.EXTRA_TITLE, rec.title)
                    putExtra(DvrPlayerActivity.EXTRA_CHANNEL_NAME, rec.channelName)
                    putExtra(DvrPlayerActivity.EXTRA_RESUME_MS, rec.resumePositionMs)
                }
                dvrPlayerLauncher.launch(playIntent)
            }
            .setNegativeButton("Delete") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = DvrRepository.deleteRecording(this@MainActivity, rec.id)
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Cancel Scheduled Recording?")
            .setMessage("${rec.title} (${rec.channelName})")
            .setPositiveButton("Cancel Timer") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = DvrRepository.cancelTimer(this@MainActivity, rec.id)
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
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(rule.title)
            .setMessage("${rule.channelName} • ${if (rule.enabled) "Active" else "Paused"}")
            .setPositiveButton(if (rule.enabled) "Pause Rule" else "Enable Rule") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = DvrRepository.toggleSeriesRule(this@MainActivity, rule)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Updated '${rule.title}'" else "Failed to update rule",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNegativeButton("Delete Rule") { _, _ ->
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val ok = DvrRepository.deleteSeriesRule(this@MainActivity, rule.id)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "Removed rule '${rule.title}'" else "Failed to remove rule",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (ok) onRefresh()
                }
            }
            .setNeutralButton("Close", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_GUIDE) {
            shortcutDestination.value = TvhNavDestination.GUIDE
            shortcutNavTick.intValue++
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, InputManagerActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

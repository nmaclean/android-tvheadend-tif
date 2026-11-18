package com.nmaclean.tvheadend.ui

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.TvheadendInputService
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.htsp.HtspConnectionPool
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhRecordingStatus
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.DvrRepository
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.ui.components.PlayerOsdOverlay
import com.nmaclean.tvheadend.ui.components.ProgramDetailsModal
import com.nmaclean.tvheadend.ui.components.TrackOption
import com.nmaclean.tvheadend.ui.components.TrackSelectionOverlay
import com.nmaclean.tvheadend.ui.screens.GuideScreen
import com.nmaclean.tvheadend.ui.theme.TvTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sony "TV Menu" Fullscreen Live Player & Mini-Guide Overlay Activity tuning 100% via Android TV TIF.
 */
@UnstableApi
class TvPlayerActivity : FragmentActivity() {

    private lateinit var tvView: TvView
    private lateinit var playerView: PlayerView

    // Top OSD Banner
    private lateinit var topOsdBanner: View
    private lateinit var osdChannelNum: TextView
    private lateinit var osdProgramTitle: TextView
    private lateinit var osdChannelName: TextView
    private lateinit var osdProgramTime: TextView
    private lateinit var osdProgramProgress: ProgressBar
    private lateinit var osdChannelLogo: ImageView
    private lateinit var osdProgramDescription: TextView
    private lateinit var osdBtnAudio: ImageView
    private lateinit var osdBtnSubtitles: ImageView

    // Bottom Sony "TV Menu" Overlay
    private lateinit var bottomMochiMenu: View
    private lateinit var mochiBtnAudio: ImageView
    private lateinit var mochiBtnSubtitles: ImageView

    private var exoPlayer: ExoPlayer? = null
    private val channelList = CopyOnWriteArrayList<TvhChannel>()
    private var currentChannelIndex = 0
    private var currentlyTunedChannelId: Long? = null
    private var currentlyTunedStreamKey: String? = null
    private var openedFromEpgIntent = false
    private var epgChannelSelected = false

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        topOsdBanner.visibility = View.GONE
        bottomMochiMenu.visibility = View.GONE
    }

    private val settings by lazy { TvhSettings(this) }

    private val osdVisibleState = mutableStateOf(false)
    private val isTuningState = mutableStateOf(false)
    private val isPausedState = mutableStateOf(false)
    private val osdInteractionTickState = mutableIntStateOf(0)
    private val channelNumberEntryState = mutableStateOf("")
    private var zapRunnable: Runnable? = null
    private var numberEntryRunnable: Runnable? = null
    private val osdChannelState = mutableStateOf<TvhChannel?>(null)
    private val osdCurrentEpgState = mutableStateOf<EpgRepository.ChannelEpg?>(null)
    private val osdAdjacentChannelsState = mutableStateOf<List<TvhChannel>>(emptyList())
    private val osdAdjacentEpgState = mutableStateOf<Map<Long, EpgRepository.ChannelEpg>>(emptyMap())

    private val guideOverlayVisibleState = mutableStateOf(false)
    private val guideProgramModalState = mutableStateOf<Pair<TvhChannel, EpgRepository.ProgramInfo>?>(null)
    private val guideChannelsState = mutableStateOf<List<TvhChannel>>(emptyList())
    private val guideCurrentEpgState = mutableStateOf<Map<Long, EpgRepository.ChannelEpg>>(emptyMap())
    private val guideProgramsState = mutableStateOf<Map<Long, List<EpgRepository.ProgramInfo>>>(emptyMap())
    private val guideWindowStartState = mutableLongStateOf(System.currentTimeMillis() - 30 * 60_000L)

    private val trackOverlayVisibleState = mutableStateOf(false)
    private val audioTrackOptionsState = mutableStateOf<List<TrackOption>>(emptyList())
    private val selectedAudioIdState = mutableStateOf<String?>(null)
    private val subtitleTrackOptionsState = mutableStateOf<List<TrackOption>>(emptyList())
    private val selectedSubtitleIdState = mutableStateOf<String?>(null)

    private fun updateDebugOverlay() {
        val debugBadge = findViewById<View>(R.id.debug_overlay_badge)
        val isDebugOn = settings.isStreamingDebugEnabled
        debugBadge?.visibility = if (isDebugOn) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        updateDebugOverlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        HtspConnectionPool.preconnectAsync(this)

        tvView = findViewById(R.id.tv_view)
        playerView = findViewById(R.id.player_view)
        updateDebugOverlay()

        // Top Banner
        topOsdBanner = findViewById(R.id.top_osd_banner)
        osdChannelNum = findViewById(R.id.osd_channel_num)
        osdProgramTitle = findViewById(R.id.osd_program_title)
        osdChannelName = findViewById(R.id.osd_channel_name)
        osdProgramTime = findViewById(R.id.osd_program_time)
        osdProgramProgress = findViewById(R.id.osd_program_progress)
        osdChannelLogo = findViewById(R.id.osd_channel_logo)
        osdProgramDescription = findViewById(R.id.osd_program_description)

        // Action Buttons
        osdBtnAudio = findViewById(R.id.osd_btn_audio)
        osdBtnSubtitles = findViewById(R.id.osd_btn_subtitles)

        mochiBtnAudio = findViewById(R.id.mochi_btn_audio)
        mochiBtnSubtitles = findViewById(R.id.mochi_btn_subtitles)

        osdBtnAudio.setOnClickListener { showTrackSelectionDialog(TvTrackInfo.TYPE_AUDIO) }
        osdBtnSubtitles.setOnClickListener { showTrackSelectionDialog(TvTrackInfo.TYPE_SUBTITLE) }

        mochiBtnAudio.setOnClickListener { showTrackSelectionDialog(TvTrackInfo.TYPE_AUDIO) }
        mochiBtnSubtitles.setOnClickListener { showTrackSelectionDialog(TvTrackInfo.TYPE_SUBTITLE) }

        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onTracksChanged(inputId: String, tracks: List<TvTrackInfo>) {
                Log.i("TvPlayerActivity", "TvView tracks changed: ${tracks.size} tracks reported")
                updateTrackButtons()
            }

            override fun onTrackSelected(inputId: String, type: Int, trackId: String?) {
                Log.i("TvPlayerActivity", "TvView track selected: type=$type, trackId=$trackId")
            }

            override fun onVideoAvailable(inputId: String) {
                Log.i("TvPlayerActivity", "TvView onVideoAvailable: inputId=$inputId")
                isTuningState.value = false
            }

            override fun onVideoUnavailable(inputId: String, reason: Int) {
                Log.w("TvPlayerActivity", "TvView onVideoUnavailable: inputId=$inputId, reason=$reason")
            }

            override fun onConnectionFailed(inputId: String) {
                Log.e("TvPlayerActivity", "TvView onConnectionFailed: inputId=$inputId")
            }

            override fun onContentAllowed(inputId: String) {
                Log.i("TvPlayerActivity", "TvView onContentAllowed: inputId=$inputId")
            }

            override fun onContentBlocked(inputId: String, rating: TvContentRating) {
                Log.w("TvPlayerActivity", "TvView onContentBlocked: inputId=$inputId, rating=$rating")
            }
        })

        // Bottom Overlay
        bottomMochiMenu = findViewById(R.id.bottom_mochi_menu)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!consumeOverlayBackPress()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )

        findViewById<ComposeView>(R.id.compose_osd_overlay)?.setContent {
            TvTheme {
                val allRecordings = DvrRepository.recordings.collectAsState()
                val currentChannel = osdChannelState.value ?: channelList.getOrNull(currentChannelIndex)
                val currentHtspChId = remember(currentChannel) {
                    currentChannel?.let { DvrRepository.resolveHtspChannelId(this@TvPlayerActivity, it) } ?: 0L
                }
                val activeChannelRecording = remember(allRecordings.value, currentHtspChId, osdCurrentEpgState.value) {
                    if (currentHtspChId <= 0L) {
                        null
                    } else {
                        allRecordings.value.firstOrNull { rec ->
                            rec.channelId == currentHtspChId && rec.status == TvhRecordingStatus.RECORDING
                        } ?: osdCurrentEpgState.value?.currentProgram?.let { prog ->
                            DvrRepository.findActiveRecording(
                                eventId = prog.eventId,
                                channelId = currentHtspChId,
                                title = prog.title,
                                startTimeMs = prog.startTimeMs
                            )
                        }
                    }
                }
                val isCurrentChannelRecording = activeChannelRecording != null

                LaunchedEffect(Unit) {
                    DvrRepository.refresh(this@TvPlayerActivity)
                }

                val anyOverlayActive = trackOverlayVisibleState.value ||
                    guideProgramModalState.value != null ||
                    guideOverlayVisibleState.value ||
                    osdVisibleState.value
                BackHandler(enabled = anyOverlayActive) {
                    consumeOverlayBackPress()
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    PlayerOsdOverlay(
                        visible = osdVisibleState.value && !guideOverlayVisibleState.value && !trackOverlayVisibleState.value,
                        isTuning = isTuningState.value,
                        isPaused = isPausedState.value,
                        isRecording = isCurrentChannelRecording,
                        interactionTick = osdInteractionTickState.intValue,
                        currentChannel = osdChannelState.value,
                        currentEpg = osdCurrentEpgState.value,
                        adjacentChannels = osdAdjacentChannelsState.value,
                        adjacentEpg = osdAdjacentEpgState.value,
                        onTimeout = { osdVisibleState.value = false },
                        onTogglePause = { toggleLivePause() },
                        onToggleRecord = {
                            osdInteractionTickState.intValue++
                            toggleChannelRecording(currentChannel, activeChannelRecording)
                        },
                        onGuideClick = {
                            osdVisibleState.value = false
                            openEpgGuide()
                        },
                        onAudioSubtitlesClick = {
                            osdVisibleState.value = false
                            showAudioOrSubtitlePicker()
                        },
                        onQuickZapChannel = { selectedChannel ->
                            osdInteractionTickState.intValue++
                            tuneToSelectedChannel(selectedChannel)
                        }
                    )

                    if (isCurrentChannelRecording && !osdVisibleState.value && !guideOverlayVisibleState.value && !trackOverlayVisibleState.value) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(top = 24.dp, start = 48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(androidx.compose.ui.graphics.Color(0xCC1E0B10))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(0xFFFF1744))
                            )
                            androidx.tv.material3.Text(
                                text = "REC",
                                color = androidx.compose.ui.graphics.Color(0xFFFF5252),
                                fontSize = 12.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }

                    val typedNum = channelNumberEntryState.value
                    if (typedNum.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopEnd)
                                .padding(top = 28.dp, end = 36.dp)
                                .background(
                                    androidx.compose.ui.graphics.Color(0xEB0B1221),
                                    androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            androidx.tv.material3.Text(
                                text = "CH $typedNum",
                                color = androidx.compose.ui.graphics.Color(0xFF00E5FF),
                                fontSize = 24.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }

                    if (guideOverlayVisibleState.value) {
                        val gridFocusRequester = remember { FocusRequester() }
                        LaunchedEffect(guideChannelsState.value.size) {
                            if (guideChannelsState.value.isNotEmpty()) {
                                kotlinx.coroutines.delay(60L)
                                try {
                                    gridFocusRequester.requestFocus()
                                } catch (_: Exception) {}
                            }
                        }
                        val scheduledIds = DvrRepository.scheduledEventIds.collectAsState()
                        val seriesTitles = DvrRepository.seriesTitles.collectAsState()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(androidx.compose.ui.graphics.Color(0xEB060A12))
                        ) {
                            GuideScreen(
                                channels = guideChannelsState.value,
                                currentEpg = guideCurrentEpgState.value,
                                guidePrograms = guideProgramsState.value,
                                windowStartMs = guideWindowStartState.longValue,
                                scheduledEventIds = scheduledIds.value,
                                seriesTitles = seriesTitles.value,
                                onTuneChannel = { selectedChannel ->
                                    epgChannelSelected = true
                                    guideProgramModalState.value = null
                                    guideOverlayVisibleState.value = false
                                    tuneToSelectedChannel(selectedChannel)
                                },
                                onScheduleProgram = { ch, prog ->
                                    guideProgramModalState.value = ch to prog
                                },
                                gridFocusRequester = gridFocusRequester
                            )

                            val modalPair = guideProgramModalState.value
                            if (modalPair != null) {
                                val (ch, prog) = modalPair
                                val now = System.currentTimeMillis()
                                val isLiveNow = now in prog.startTimeMs..prog.endTimeMs
                                val htspChId = DvrRepository.resolveHtspChannelId(this@TvPlayerActivity, ch)
                                val existing = DvrRepository.findActiveRecording(
                                    eventId = prog.eventId,
                                    channelId = htspChId,
                                    title = prog.title,
                                    startTimeMs = prog.startTimeMs
                                )
                                val isScheduled = existing != null || (prog.eventId > 0L && prog.eventId in scheduledIds.value)
                                val hasSeries = prog.title.trim().lowercase() in seriesTitles.value
                                ProgramDetailsModal(
                                    channel = ch,
                                    program = prog,
                                    isScheduled = isScheduled,
                                    hasSeriesRule = hasSeries,
                                    isLiveNow = isLiveNow,
                                    onTuneLive = {
                                        epgChannelSelected = true
                                        guideProgramModalState.value = null
                                        guideOverlayVisibleState.value = false
                                        tuneToSelectedChannel(ch)
                                    },
                                    onToggleRecordEpisode = {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            val currentExisting = DvrRepository.findActiveRecording(
                                                eventId = prog.eventId,
                                                channelId = htspChId,
                                                title = prog.title,
                                                startTimeMs = prog.startTimeMs
                                            )
                                            val ok = if (currentExisting != null) {
                                                DvrRepository.cancelTimer(this@TvPlayerActivity, currentExisting.id)
                                            } else {
                                                DvrRepository.scheduleProgram(this@TvPlayerActivity, ch, prog)
                                            }
                                            if (!ok) {
                                                Toast.makeText(this@TvPlayerActivity, "DVR action failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onScheduleSeries = {
                                        CoroutineScope(Dispatchers.Main).launch {
                                            val ok = DvrRepository.scheduleSeries(this@TvPlayerActivity, prog.title, htspChId)
                                            if (!ok) {
                                                Toast.makeText(this@TvPlayerActivity, "Could not add series rule", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onDismiss = { guideProgramModalState.value = null }
                                )
                            }
                        }
                    }

                    if (trackOverlayVisibleState.value) {
                        TrackSelectionOverlay(
                            audioTracks = audioTrackOptionsState.value,
                            selectedAudioId = selectedAudioIdState.value,
                            subtitleTracks = subtitleTrackOptionsState.value,
                            selectedSubtitleId = selectedSubtitleIdState.value,
                            onSelectAudio = { trackId ->
                                Log.i("TvPlayerActivity", "Selected audio track: $trackId")
                                tvView.selectTrack(TvTrackInfo.TYPE_AUDIO, trackId)
                                selectedAudioIdState.value = trackId
                            },
                            onSelectSubtitle = { trackId ->
                                if (trackId == null) {
                                    Log.i("TvPlayerActivity", "Subtitles turned off")
                                    tvView.setCaptionEnabled(false)
                                    tvView.selectTrack(TvTrackInfo.TYPE_SUBTITLE, null)
                                } else {
                                    Log.i("TvPlayerActivity", "Selected subtitle track: $trackId")
                                    tvView.setCaptionEnabled(true)
                                    tvView.selectTrack(TvTrackInfo.TYPE_SUBTITLE, trackId)
                                }
                                selectedSubtitleIdState.value = trackId
                            },
                            onDismiss = { trackOverlayVisibleState.value = false }
                        )
                    }
                }
            }
        }

        handleTuneIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTuneIntent(intent)
    }

    private fun findChannelIndex(target: TvhChannel): Int {
        return channelList.indexOfFirst {
            it.id == target.id ||
                (it.uuid.isNotEmpty() && it.uuid == target.uuid) ||
                (it.number > 0 && it.number == target.number)
        }
    }

    private fun tuneToSelectedChannel(selectedChannel: TvhChannel) {
        val idx = findChannelIndex(selectedChannel)
        if (idx != -1) {
            val targetChannel = channelList[idx]
            val targetStreamKey = if (targetChannel.uuid.isNotEmpty()) targetChannel.uuid else targetChannel.id.toString()
            if (currentlyTunedChannelId == targetChannel.id &&
                (currentlyTunedStreamKey == null || currentlyTunedStreamKey == targetStreamKey || currentlyTunedStreamKey == targetChannel.id.toString())
            ) {
                showTopOsdBanner(targetChannel)
            } else {
                currentChannelIndex = idx
                playCurrentChannel()
            }
        } else if (selectedChannel.id > 0L) {
            val tifInputId = TvContract.buildInputId(ComponentName(this, TvheadendInputService::class.java))
            val inputId = if (selectedChannel.inputId.isNotEmpty() && selectedChannel.inputId.contains("/")) selectedChannel.inputId else tifInputId
            currentlyTunedChannelId = selectedChannel.id
            currentlyTunedStreamKey = selectedChannel.uuid.ifEmpty { selectedChannel.id.toString() }
            isTuningState.value = true
            tvView.tune(inputId, TvContract.buildChannelUri(selectedChannel.id))
            showTopOsdBanner(selectedChannel)
        }
    }

    private fun handleTuneIntent(intent: Intent?) {
        if (intent == null) return
        val openEpg = intent.getBooleanExtra("open_epg", false)
        if (openEpg) {
            openedFromEpgIntent = true
            epgChannelSelected = false
        }

        val channelId = intent.getStringExtra("channel_id")
            ?: intent.getLongExtra("channel_id", -1L).takeIf { it > 0L }?.toString()
            ?: ""
        val channelUuid = intent.getStringExtra("channel_uuid")?.takeIf { it.isNotEmpty() } ?: channelId
        val channelName = intent.getStringExtra("channel_name") ?: "Channel"
        val channelNum = intent.getIntExtra("channel_number", 1)
        val channelInputId = intent.getStringExtra("input_id") ?: ""

        val idLong = channelId.toLongOrNull()
        if (idLong != null && idLong > 0L) {
            val tifInputId = TvContract.buildInputId(ComponentName(this, TvheadendInputService::class.java))
            val targetInputId = if (channelInputId.contains("/")) channelInputId else tifInputId
            val logoUri = TvContract.buildChannelLogoUri(idLong).toString()
            val initialChannel = TvhChannel(idLong, channelUuid, channelName, channelNum, logoUri, targetInputId)
            val streamKey = if (initialChannel.uuid.isNotEmpty()) initialChannel.uuid else initialChannel.id.toString()

            val isAlreadyTuned = currentlyTunedChannelId == idLong &&
                    (currentlyTunedStreamKey == null || currentlyTunedStreamKey == streamKey || currentlyTunedStreamKey == channelId)

            if (isAlreadyTuned) {
                Log.i("TvPlayerActivity", "Channel #${initialChannel.number} '${initialChannel.name}' ($idLong) is already currently playing; skipping redundant tune.")
                showTopOsdBanner(initialChannel)
            } else {
                currentlyTunedChannelId = idLong
                currentlyTunedStreamKey = streamKey
                playerView.visibility = View.GONE
                tvView.visibility = View.VISIBLE

                val tvInputManager = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
                val inputInfo = tvInputManager?.getTvInputInfo(targetInputId)
                Log.i("TvPlayerActivity", "Diagnostic: targetInputId=$targetInputId, inputInfo found=${inputInfo != null}")
                tvInputManager?.tvInputList?.forEach { info ->
                    Log.i("TvPlayerActivity", "Registered TIF Input: id=${info.id}, service=${info.serviceInfo.name}")
                }

                Log.i("TvPlayerActivity", "Immediate tune for channel: ${initialChannel.name} ($idLong) on $targetInputId")
                isTuningState.value = true
                tvView.tune(targetInputId, TvContract.buildChannelUri(idLong))
                showTopOsdBanner(initialChannel)
            }

            val idx = channelList.indexOfFirst { it.id == idLong || (it.uuid.isNotEmpty() && it.uuid == channelUuid) }
            if (idx != -1) {
                currentChannelIndex = idx
            }
        }

        loadChannels(channelId, channelUuid, channelName, channelNum, openEpg)
    }

    private fun loadChannels(
        initialId: String,
        initialUuid: String,
        initialName: String,
        initialNum: Int,
        openEpg: Boolean = false
    ) {
        val cachedChannels = ChannelRegistry.getAllChannels()
        if (cachedChannels.isNotEmpty()) {
            val mappedCached = cachedChannels.map { ch ->
                if (ch.icon.isBlank() && ch.id > 0L) {
                    ch.copy(icon = TvContract.buildChannelLogoUri(ch.id).toString())
                } else ch
            }.sortedBy { it.number }
            channelList.clear()
            channelList.addAll(mappedCached)
            currentChannelIndex = channelList.indexOfFirst {
                it.id.toString() == initialId ||
                    (initialUuid.isNotEmpty() && it.uuid == initialUuid) ||
                    it.uuid == initialId ||
                    it.number == initialNum
            }
            if (currentChannelIndex == -1) currentChannelIndex = 0
            if (!openEpg && currentlyTunedChannelId == null && channelList.isNotEmpty()) {
                playCurrentChannel()
            }
            if (openEpg && supportFragmentManager.findFragmentByTag("epg_timeline") == null) {
                openEpgGuide()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolver = contentResolver
                val tifInputId = TvContract.buildInputId(ComponentName(this@TvPlayerActivity, TvheadendInputService::class.java))
                val loadedChannels = mutableListOf<TvhChannel>()

                resolver.query(
                    TvContract.Channels.CONTENT_URI,
                    arrayOf(
                        TvContract.Channels._ID,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                        TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_SERVICE_ID,
                        TvContract.Channels.COLUMN_INPUT_ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                    ),
                    null, null, null
                )?.use { cursor ->
                    val dbIdCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val numCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                    val nameCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                    val serviceIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)
                    val inputIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                    val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)

                    while (cursor.moveToNext()) {
                        val dbId = cursor.getLong(dbIdCol)
                        val num = cursor.getString(numCol)?.toIntOrNull() ?: 1
                        val name = cursor.getString(nameCol) ?: "Channel"
                        val serviceId = if (serviceIdCol >= 0) cursor.getLong(serviceIdCol) else dbId
                        val inputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: tifInputId else tifInputId

                        val streamKey = if (dataCol >= 0) {
                            val bytes = cursor.getBlob(dataCol)
                            if (bytes != null && bytes.isNotEmpty()) String(bytes, Charsets.UTF_8).trim() else serviceId.toString()
                        } else serviceId.toString()

                        val logoUri = TvContract.buildChannelLogoUri(dbId).toString()
                        loadedChannels.add(TvhChannel(dbId, streamKey, name, num, logoUri, inputId))
                    }
                }

                val uniqueChannels = loadedChannels.distinctBy {
                    if (it.uuid.isNotEmpty()) it.uuid else if (it.number > 0) "num_${it.number}" else "name_${it.name}"
                }.sortedWith(compareBy({ it.number }, { it.name }))

                if (uniqueChannels.isNotEmpty()) {
                    ChannelRegistry.updateChannels(uniqueChannels)
                }

                withContext(Dispatchers.Main) {
                    if (uniqueChannels.isNotEmpty()) {
                        channelList.clear()
                        channelList.addAll(uniqueChannels)
                        currentChannelIndex = channelList.indexOfFirst {
                            it.id.toString() == initialId ||
                                (initialUuid.isNotEmpty() && it.uuid == initialUuid) ||
                                it.uuid == initialId ||
                                it.number == initialNum
                        }
                        if (currentChannelIndex == -1) currentChannelIndex = 0
                    }
                    val resolvedChannel = channelList.getOrNull(currentChannelIndex)
                    if (!openEpg && resolvedChannel != null) {
                        if (currentlyTunedChannelId == null || currentlyTunedChannelId != resolvedChannel.id) {
                            playCurrentChannel()
                        }
                    }

                    if (openEpg && supportFragmentManager.findFragmentByTag("epg_timeline") == null) {
                        openEpgGuide()
                    }
                }
            } catch (e: Exception) {
                Log.w("TvPlayerActivity", "Error loading channels in background: ${e.message}")
            }
        }
    }

    private fun playCurrentChannel() {
        if (channelList.isEmpty()) return
        val channel = channelList[currentChannelIndex]

        val tifInputId = TvContract.buildInputId(ComponentName(this, TvheadendInputService::class.java))
        val inputId = if (channel.inputId.isNotEmpty() && channel.inputId.contains("/")) channel.inputId else tifInputId

        playerView.visibility = View.GONE
        tvView.visibility = View.VISIBLE

        val channelUri = TvContract.buildChannelUri(channel.id)
        val streamKey = if (channel.uuid.isNotEmpty()) channel.uuid else channel.id.toString()

        if (currentlyTunedChannelId == channel.id || (currentlyTunedStreamKey != null && (currentlyTunedStreamKey == streamKey || currentlyTunedStreamKey == channel.id.toString()))) {
            Log.i("TvPlayerActivity", "Channel #${channel.number} '${channel.name}' is already currently playing; skipping redundant re-tune.")
            showTopOsdBanner(channel)
            return
        }

        currentlyTunedChannelId = channel.id
        currentlyTunedStreamKey = streamKey
        isPausedState.value = false

        Log.i("TvPlayerActivity", "Tuning TIF TvView with inputId=$inputId, channelUri=$channelUri")
        isTuningState.value = true
        tvView.tune(inputId, channelUri)
        showTopOsdBanner(channel)
    }

    private fun showAudioOrSubtitlePicker() {
        val rawAudio = tvView.getTracks(TvTrackInfo.TYPE_AUDIO) ?: emptyList()
        val rawSubs = tvView.getTracks(TvTrackInfo.TYPE_SUBTITLE) ?: emptyList()

        audioTrackOptionsState.value = rawAudio.mapIndexed { idx, info ->
            val lang = info.language?.takeIf { it.isNotBlank() }?.let {
                try { Locale(it).displayLanguage.ifEmpty { it } } catch (_: Exception) { it }
            } ?: "Audio ${idx + 1}"
            val chText = if (info.audioChannelCount > 0) "${info.audioChannelCount} ch" else ""
            TrackOption(id = info.id, label = lang, detail = chText)
        }
        selectedAudioIdState.value = tvView.getSelectedTrack(TvTrackInfo.TYPE_AUDIO)
            ?: rawAudio.firstOrNull()?.id

        val subOptions = mutableListOf(TrackOption(id = null, label = "Off", detail = "Disable subtitles"))
        rawSubs.forEachIndexed { idx, info ->
            val lang = info.language?.takeIf { it.isNotBlank() }?.let {
                try { Locale(it).displayLanguage.ifEmpty { it } } catch (_: Exception) { it }
            } ?: "Subtitle ${idx + 1}"
            subOptions.add(TrackOption(id = info.id, label = lang))
        }
        subtitleTrackOptionsState.value = subOptions
        selectedSubtitleIdState.value = tvView.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE)
        trackOverlayVisibleState.value = true
    }

    private fun showComposeOsdOverlay(channel: TvhChannel?) {
        val listSnapshot = channelList.toList()
        val target = channel ?: listSnapshot.getOrNull(currentChannelIndex)
        osdChannelState.value = target
        osdVisibleState.value = true
        osdInteractionTickState.intValue++

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentEpg = if (target != null) {
                    EpgRepository.getCurrentAndNextProgram(this@TvPlayerActivity, target.id)
                } else null
                val currentSnapshot = channelList.toList().ifEmpty { listSnapshot }
                val adjacent = currentSnapshot.filter { it.id != target?.id }.take(12)
                val adjacentMap = TvhUiRepository.loadCurrentEpgMap(this@TvPlayerActivity, adjacent)
                withContext(Dispatchers.Main) {
                    osdCurrentEpgState.value = currentEpg
                    osdAdjacentChannelsState.value = adjacent
                    osdAdjacentEpgState.value = adjacentMap
                }
            } catch (e: Exception) {
                Log.w("TvPlayerActivity", "Error updating OSD overlay EPG: ${e.message}")
            }
        }
    }

    private fun showTopOsdBanner(channel: TvhChannel) {
        showComposeOsdOverlay(channel)
        return
        osdChannelNum.text = channel.number.toString()
        osdChannelName.text = channel.name

        CoroutineScope(Dispatchers.IO).launch {
            val epg = EpgRepository.getCurrentAndNextProgram(this@TvPlayerActivity, channel.id)
            val currentProg = epg.currentProgram

            var logoBitmap: Bitmap? = null
            try {
                val logoUri = TvContract.buildChannelLogoUri(channel.id)
                contentResolver.openInputStream(logoUri)?.use { input ->
                    logoBitmap = BitmapFactory.decodeStream(input)
                }
            } catch (ignored: Exception) {}

            withContext(Dispatchers.Main) {
                if (currentProg != null) {
                    osdProgramTitle.text = currentProg.title
                    val startStr = timeFormat.format(Date(currentProg.startTimeMs))
                    val endStr = timeFormat.format(Date(currentProg.endTimeMs))
                    val nowMs = System.currentTimeMillis()
                    val remainingMins = ((currentProg.endTimeMs - nowMs) / 60000).coerceAtLeast(0)
                    osdProgramTime.text = "$startStr - $endStr (${remainingMins}m left)"
                    osdProgramProgress.progress = currentProg.progressPercent
                    osdProgramProgress.visibility = View.VISIBLE
                    osdProgramDescription.text = currentProg.summary ?: ""
                    osdProgramDescription.visibility = if (currentProg.summary.isNullOrEmpty()) View.GONE else View.VISIBLE
                } else {
                    osdProgramTitle.text = "No Program Information"
                    osdProgramTime.text = ""
                    osdProgramProgress.visibility = View.GONE
                    osdProgramDescription.visibility = View.GONE
                }

                if (logoBitmap != null) {
                    osdChannelLogo.setImageBitmap(logoBitmap)
                    osdChannelLogo.visibility = View.VISIBLE
                } else {
                    osdChannelLogo.visibility = View.GONE
                }
            }
        }

        topOsdBanner.visibility = View.VISIBLE
        updateTrackButtons()

        osdHandler.removeCallbacks(hideOsdRunnable)
        osdHandler.postDelayed(hideOsdRunnable, 6000)
    }

    private fun updateTrackButtons() {
        val audioTracks = tvView.getTracks(TvTrackInfo.TYPE_AUDIO) ?: emptyList()
        val subtitleTracks = tvView.getTracks(TvTrackInfo.TYPE_SUBTITLE) ?: emptyList()

        val showAudio = audioTracks.size > 1
        val showSubs = subtitleTracks.isNotEmpty()

        osdBtnAudio.visibility = if (showAudio) View.VISIBLE else View.GONE
        osdBtnSubtitles.visibility = if (showSubs) View.VISIBLE else View.GONE

        mochiBtnAudio.visibility = if (showAudio) View.VISIBLE else View.GONE
        mochiBtnSubtitles.visibility = if (showSubs) View.VISIBLE else View.GONE

        mochiBtnAudio.nextFocusRightId = if (showSubs) R.id.mochi_btn_subtitles else R.id.tv_menu_channel_scroll
        mochiBtnAudio.nextFocusDownId = R.id.tv_menu_channel_scroll

        mochiBtnSubtitles.nextFocusLeftId = if (showAudio) R.id.mochi_btn_audio else View.NO_ID
        mochiBtnSubtitles.nextFocusRightId = R.id.tv_menu_channel_scroll
        mochiBtnSubtitles.nextFocusDownId = R.id.tv_menu_channel_scroll
    }

    private fun showTrackSelectionDialog(trackType: Int) {
        val tracks = tvView.getTracks(trackType) ?: emptyList()
        val selectedTrackId = tvView.getSelectedTrack(trackType)

        if (trackType == TvTrackInfo.TYPE_AUDIO) {
            if (tracks.isEmpty()) {
                Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show()
                return
            }

            val labels = tracks.mapIndexed { idx, info ->
                val langCode = info.language ?: ""
                val localeName = if (langCode.isNotEmpty()) {
                    try { Locale(langCode).displayLanguage.ifEmpty { langCode } } catch (_: Exception) { langCode }
                } else "Track ${idx + 1}"
                val channels = if (info.audioChannelCount > 0) " (${info.audioChannelCount} ch)" else ""
                "$localeName$channels"
            }.toTypedArray()

            var selectedIndex = tracks.indexOfFirst { it.id == selectedTrackId }
            if (selectedIndex == -1) selectedIndex = 0

            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Select Audio Track")
                .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                    val chosen = tracks[which]
                    Log.i("TvPlayerActivity", "Selected audio track: ${chosen.id}")
                    tvView.selectTrack(TvTrackInfo.TYPE_AUDIO, chosen.id)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else if (trackType == TvTrackInfo.TYPE_SUBTITLE) {
            val labels = mutableListOf("Off")
            tracks.forEachIndexed { idx, info ->
                val langCode = info.language ?: ""
                val localeName = if (langCode.isNotEmpty()) {
                    try { Locale(langCode).displayLanguage.ifEmpty { langCode } } catch (_: Exception) { langCode }
                } else "Subtitle ${idx + 1}"
                labels.add(localeName)
            }

            val selectedIndex = if (selectedTrackId == null) 0 else (tracks.indexOfFirst { it.id == selectedTrackId } + 1)

            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                .setTitle("Select Subtitles")
                .setSingleChoiceItems(labels.toTypedArray(), selectedIndex.coerceAtLeast(0)) { dialog, which ->
                    if (which == 0) {
                        Log.i("TvPlayerActivity", "Subtitles turned off")
                        tvView.setCaptionEnabled(false)
                        tvView.selectTrack(TvTrackInfo.TYPE_SUBTITLE, null)
                    } else {
                        val chosen = tracks[which - 1]
                        Log.i("TvPlayerActivity", "Selected subtitle track: ${chosen.id}")
                        tvView.setCaptionEnabled(true)
                        tvView.selectTrack(TvTrackInfo.TYPE_SUBTITLE, chosen.id)
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun populateTvMenuOverlay() {
        val menuContainer = findViewById<LinearLayout>(R.id.tv_menu_channel_container) ?: return
        menuContainer.removeAllViews()

        val nowMs = System.currentTimeMillis()
        val cardDrawable = ContextCompat.getDrawable(this, R.drawable.bg_program_selector)
        val colorPrimary = ContextCompat.getColor(this, R.color.tv_text_primary)
        val colorSecondary = ContextCompat.getColor(this, R.color.tv_text_secondary)
        val colorCyan = ContextCompat.getColor(this, R.color.tv_accent_cyan)
        val colorSubtext = Color.parseColor("#B0BEC5")
        val dividerDrawable = ColorDrawable(Color.parseColor("#2E3A50"))

        CoroutineScope(Dispatchers.IO).launch {
            val allProgramsMap = EpgRepository.getAllProgramsInWindow(
                this@TvPlayerActivity,
                nowMs - 30 * 60 * 1000L,
                nowMs + 12 * 3600 * 1000L
            )

            withContext(Dispatchers.Main) {
                var focusedViewToRequest: View? = null

                for ((index, ch) in channelList.withIndex()) {
                    val programs = allProgramsMap[ch.id] ?: emptyList()
                    val currentProg = programs.find { nowMs in it.startTimeMs..it.endTimeMs }

                    val startStr = if (currentProg != null) timeFormat.format(Date(currentProg.startTimeMs)) else "11:00"
                    val endStr = if (currentProg != null) timeFormat.format(Date(currentProg.endTimeMs)) else "15:00"
                    val timeSlotStr = "$startStr - $endStr"

                    val cardView = LinearLayout(this@TvPlayerActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = cardDrawable?.constantState?.newDrawable(resources) ?: cardDrawable
                        isFocusable = true
                        isClickable = true
                        setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))

                        val cardParams = LinearLayout.LayoutParams(dpToPx(230), dpToPx(105)).apply {
                            setMargins(0, 0, dpToPx(12), 0)
                        }
                        layoutParams = cardParams

                        // Wire D-pad focus navigation upwards & leftwards to Audio & Subtitle action buttons
                        if (mochiBtnAudio.visibility == View.VISIBLE) {
                            nextFocusUpId = R.id.mochi_btn_audio
                        } else if (mochiBtnSubtitles.visibility == View.VISIBLE) {
                            nextFocusUpId = R.id.mochi_btn_subtitles
                        }

                        if (index == 0) {
                            if (mochiBtnSubtitles.visibility == View.VISIBLE) {
                                nextFocusLeftId = R.id.mochi_btn_subtitles
                            } else if (mochiBtnAudio.visibility == View.VISIBLE) {
                                nextFocusLeftId = R.id.mochi_btn_audio
                            }
                        }

                        // Source label (Tvheadend TIF)
                        val sourceTv = TextView(this@TvPlayerActivity).apply {
                            text = "Tvheadend TIF"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                            setTextColor(colorSubtext)
                            visibility = View.GONE
                        }

                        // Channel Number + Name
                        val headerTv = TextView(this@TvPlayerActivity).apply {
                            text = "${ch.number} ${ch.name}"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setTextColor(colorPrimary)
                            setTypeface(null, Typeface.BOLD)
                            maxLines = 1
                        }

                        // Time slot string
                        val timeTv = TextView(this@TvPlayerActivity).apply {
                            text = timeSlotStr
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setTextColor(colorCyan)
                            setTypeface(null, Typeface.BOLD)
                            visibility = View.GONE
                        }

                        // Divider line
                        val divider = View(this@TvPlayerActivity).apply {
                            background = dividerDrawable
                            val divParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dpToPx(1)
                            ).apply { setMargins(0, dpToPx(4), 0, dpToPx(4)) }
                            layoutParams = divParams
                        }

                        // Program title
                        val programTv = TextView(this@TvPlayerActivity).apply {
                            text = currentProg?.title ?: "No Program Information"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setTextColor(colorSecondary)
                            maxLines = 2
                        }

                        addView(sourceTv)
                        addView(headerTv)
                        addView(timeTv)
                        addView(divider)
                        addView(programTv)

                        setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                val expandedParams = layoutParams as LinearLayout.LayoutParams
                                expandedParams.width = dpToPx(260)
                                expandedParams.height = dpToPx(135)
                                layoutParams = expandedParams

                                sourceTv.visibility = View.VISIBLE
                                timeTv.visibility = View.VISIBLE
                                headerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                                programTv.setTextColor(colorPrimary)
                            } else {
                                val normalParams = layoutParams as LinearLayout.LayoutParams
                                normalParams.width = dpToPx(230)
                                normalParams.height = dpToPx(105)
                                layoutParams = normalParams

                                sourceTv.visibility = View.GONE
                                timeTv.visibility = View.GONE
                                headerTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                programTv.setTextColor(colorSecondary)
                            }
                        }

                        setOnClickListener {
                            currentChannelIndex = index
                            playCurrentChannel()
                            bottomMochiMenu.visibility = View.GONE
                        }
                    }

                    menuContainer.addView(cardView)

                    if (index == currentChannelIndex) {
                        focusedViewToRequest = cardView
                    }
                }

                focusedViewToRequest?.requestFocus()
            }
        }
    }

    private fun toggleBottomMochiMenu() {
        if (bottomMochiMenu.visibility == View.VISIBLE) {
            bottomMochiMenu.visibility = View.GONE
        } else {
            topOsdBanner.visibility = View.GONE
            bottomMochiMenu.visibility = View.VISIBLE
            populateTvMenuOverlay()
        }
    }

    private fun promptScheduleFromGuide(channel: TvhChannel, program: EpgRepository.ProgramInfo) {
        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle(program.title)
            .setMessage("${channel.name} • ${TvhUiRepository.formatTimeRange(program.startTimeMs, program.endTimeMs)}")
            .setPositiveButton("Record Once") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    val ok = com.nmaclean.tvheadend.sync.DvrRepository.scheduleProgram(this@TvPlayerActivity, channel, program)
                    if (!ok) {
                        Toast.makeText(this@TvPlayerActivity, "Could not schedule recording", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton("Record Series") { _, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    val htspChId = com.nmaclean.tvheadend.sync.DvrRepository.resolveHtspChannelId(this@TvPlayerActivity, channel)
                    val ok = com.nmaclean.tvheadend.sync.DvrRepository.scheduleSeries(this@TvPlayerActivity, program.title, htspChId)
                    if (!ok) {
                        Toast.makeText(this@TvPlayerActivity, "Could not add series rule", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Tune Live") { _, _ ->
                guideOverlayVisibleState.value = false
                tuneToSelectedChannel(channel)
            }
            .show()
    }

    private fun openEpgGuide() {
        if (guideOverlayVisibleState.value) return

        osdHandler.removeCallbacks(hideOsdRunnable)
        osdVisibleState.value = false
        topOsdBanner.visibility = View.GONE
        bottomMochiMenu.visibility = View.GONE

        val startMs = System.currentTimeMillis() - 30 * 60_000L
        guideWindowStartState.longValue = startMs
        guideProgramModalState.value = null
        guideOverlayVisibleState.value = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channels = TvhUiRepository.loadChannels(this@TvPlayerActivity)
                val currentMap = TvhUiRepository.loadCurrentEpgMap(this@TvPlayerActivity, channels)
                val windowMap = TvhUiRepository.loadGuideWindow(
                    this@TvPlayerActivity,
                    channels,
                    startMs,
                    startMs + 4 * 60 * 60_000L
                )
                withContext(Dispatchers.Main) {
                    guideChannelsState.value = channels
                    guideCurrentEpgState.value = currentMap
                    guideProgramsState.value = windowMap
                }
            } catch (e: Exception) {
                Log.w("TvPlayerActivity", "Error loading guide overlay data: ${e.message}")
            }
        }
    }

    private fun consumeOverlayBackPress(): Boolean {
        if (trackOverlayVisibleState.value) {
            trackOverlayVisibleState.value = false
            return true
        }
        if (guideProgramModalState.value != null) {
            guideProgramModalState.value = null
            return true
        }
        if (guideOverlayVisibleState.value) {
            guideOverlayVisibleState.value = false
            if (openedFromEpgIntent && !epgChannelSelected) {
                finish()
            }
            return true
        }
        if (osdVisibleState.value) {
            osdVisibleState.value = false
            return true
        }
        if (bottomMochiMenu.visibility == View.VISIBLE || topOsdBanner.visibility == View.VISIBLE) {
            osdHandler.removeCallbacks(hideOsdRunnable)
            topOsdBanner.visibility = View.GONE
            bottomMochiMenu.visibility = View.GONE
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (consumeOverlayBackPress()) {
                return true
            }
        }

        if (trackOverlayVisibleState.value || guideOverlayVisibleState.value) {
            return super.onKeyDown(keyCode, event)
        }

        if (osdVisibleState.value) {
            osdInteractionTickState.intValue++
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
            ) {
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> setLivePaused(true)
                    KeyEvent.KEYCODE_MEDIA_PLAY -> setLivePaused(false)
                    else -> toggleLivePause()
                }
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP && channelList.isNotEmpty()) {
                stepChannel(1)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN && channelList.isNotEmpty()) {
                stepChannel(-1)
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && channelList.isNotEmpty()) {
            handleDigitKey(keyCode - KeyEvent.KEYCODE_0)
            return true
        }

        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            channelNumberEntryState.value.isNotEmpty()
        ) {
            commitChannelNumberEntry()
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_CAPTIONS -> {
                showTrackSelectionDialog(TvTrackInfo.TYPE_SUBTITLE)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                showTrackSelectionDialog(TvTrackInfo.TYPE_AUDIO)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (channelList.isNotEmpty()) {
                    stepChannel(1)
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channelList.isNotEmpty()) {
                    stepChannel(-1)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showComposeOsdOverlay(channelList.getOrNull(currentChannelIndex))
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                toggleLivePause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                setLivePaused(true)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                setLivePaused(false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_RECORD -> {
                val ch = osdChannelState.value ?: channelList.getOrNull(currentChannelIndex)
                val htspChId = ch?.let { DvrRepository.resolveHtspChannelId(this, it) } ?: 0L
                val existing = DvrRepository.recordings.value.firstOrNull {
                    it.channelId == htspChId && it.status == TvhRecordingStatus.RECORDING
                }
                toggleChannelRecording(ch, existing)
                return true
            }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                openEpgGuide()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleChannelRecording(
        channel: TvhChannel?,
        existingRecording: com.nmaclean.tvheadend.model.TvhRecording?
    ) {
        val target = channel ?: return
        CoroutineScope(Dispatchers.Main).launch {
            val ok = if (existingRecording != null) {
                DvrRepository.cancelTimer(this@TvPlayerActivity, existingRecording.id)
            } else {
                val nowMs = System.currentTimeMillis()
                val prog = osdCurrentEpgState.value?.currentProgram ?: EpgRepository.ProgramInfo(
                    eventId = 0L,
                    channelId = target.id,
                    title = target.name,
                    summary = null,
                    startTimeMs = nowMs,
                    endTimeMs = nowMs + 3_600_000L
                )
                DvrRepository.scheduleProgram(this@TvPlayerActivity, target, prog)
            }
            if (!ok) {
                Toast.makeText(this@TvPlayerActivity, "DVR action failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggleLivePause() {
        setLivePaused(!isPausedState.value)
    }

    private fun setLivePaused(paused: Boolean) {
        isPausedState.value = paused
        if (paused) {
            tvView.timeShiftPause()
            showComposeOsdOverlay(channelList.getOrNull(currentChannelIndex))
        } else {
            tvView.timeShiftResume()
            osdInteractionTickState.intValue++
        }
    }

    private fun stepChannel(delta: Int) {
        if (channelList.isEmpty()) return
        currentChannelIndex = (currentChannelIndex + delta).mod(channelList.size)
        val previewChannel = channelList[currentChannelIndex]
        showTopOsdBanner(previewChannel)
        zapRunnable?.let { osdHandler.removeCallbacks(it) }
        val runnable = Runnable { playCurrentChannel() }
        zapRunnable = runnable
        osdHandler.postDelayed(runnable, 150L)
    }

    private fun handleDigitKey(digit: Int) {
        val next = (channelNumberEntryState.value + digit.toString()).take(4)
        channelNumberEntryState.value = next
        numberEntryRunnable?.let { osdHandler.removeCallbacks(it) }
        val runnable = Runnable { commitChannelNumberEntry() }
        numberEntryRunnable = runnable
        osdHandler.postDelayed(runnable, 1200L)
    }

    private fun commitChannelNumberEntry() {
        numberEntryRunnable?.let { osdHandler.removeCallbacks(it) }
        numberEntryRunnable = null
        val targetNumber = channelNumberEntryState.value.toIntOrNull()
        channelNumberEntryState.value = ""
        if (targetNumber != null) {
            val idx = channelList.indexOfFirst { it.number == targetNumber }
            if (idx >= 0) {
                currentChannelIndex = idx
                playCurrentChannel()
            } else {
                Toast.makeText(this, "Channel $targetNumber not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun releasePlayer() {
        zapRunnable?.let { osdHandler.removeCallbacks(it) }
        zapRunnable = null
        numberEntryRunnable?.let { osdHandler.removeCallbacks(it) }
        numberEntryRunnable = null
        currentlyTunedChannelId = null
        currentlyTunedStreamKey = null
        try {
            tvView.reset()
        } catch (ignored: Exception) {}
        try {
            exoPlayer?.clearVideoSurface()
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlayer?.release()
        } catch (ignored: Exception) {}
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}

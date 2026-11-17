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
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
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
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.ui.components.PlayerOsdOverlay
import com.nmaclean.tvheadend.ui.guide.EpgTimelineOverlayFragment
import com.nmaclean.tvheadend.ui.theme.TvTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val channelList = mutableListOf<TvhChannel>()
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
    private val osdInteractionTickState = mutableIntStateOf(0)
    private val osdChannelState = mutableStateOf<TvhChannel?>(null)
    private val osdCurrentEpgState = mutableStateOf<EpgRepository.ChannelEpg?>(null)
    private val osdAdjacentChannelsState = mutableStateOf<List<TvhChannel>>(emptyList())
    private val osdAdjacentEpgState = mutableStateOf<Map<Long, EpgRepository.ChannelEpg>>(emptyMap())

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

        findViewById<ComposeView>(R.id.compose_osd_overlay)?.setContent {
            TvTheme {
                PlayerOsdOverlay(
                    visible = osdVisibleState.value,
                    interactionTick = osdInteractionTickState.intValue,
                    currentChannel = osdChannelState.value,
                    currentEpg = osdCurrentEpgState.value,
                    adjacentChannels = osdAdjacentChannelsState.value,
                    adjacentEpg = osdAdjacentEpgState.value,
                    onTimeout = { osdVisibleState.value = false },
                    onGuideClick = {
                        osdVisibleState.value = false
                        openEpgGuide()
                    },
                    onAudioSubtitlesClick = {
                        osdInteractionTickState.intValue++
                        showAudioOrSubtitlePicker()
                    },
                    onSettingsClick = {
                        osdVisibleState.value = false
                        startActivity(Intent(this, InputManagerActivity::class.java))
                    },
                    onQuickZapChannel = { selectedChannel ->
                        osdInteractionTickState.intValue++
                        val idx = channelList.indexOfFirst { it.id == selectedChannel.id }
                        if (idx != -1 && idx != currentChannelIndex) {
                            currentChannelIndex = idx
                            playCurrentChannel()
                        }
                    }
                )
            }
        }

        handleTuneIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleTuneIntent(intent)
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
        val channelName = intent.getStringExtra("channel_name") ?: "Channel"
        val channelNum = intent.getIntExtra("channel_number", 1)
        val channelInputId = intent.getStringExtra("input_id") ?: ""

        val idLong = channelId.toLongOrNull()
        if (idLong != null && idLong > 0L) {
            val tifInputId = TvContract.buildInputId(ComponentName(this, TvheadendInputService::class.java))
            val targetInputId = if (channelInputId.contains("/")) channelInputId else tifInputId
            val initialChannel = TvhChannel(idLong, channelId, channelName, channelNum, "", targetInputId)
            val streamKey = if (initialChannel.uuid.isNotEmpty()) initialChannel.uuid else initialChannel.id.toString()

            val isAlreadyTuned = currentlyTunedChannelId == idLong ||
                    (currentlyTunedStreamKey != null && (currentlyTunedStreamKey == streamKey || currentlyTunedStreamKey == channelId))

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
                tvView.tune(targetInputId, TvContract.buildChannelUri(idLong))
                showTopOsdBanner(initialChannel)
            }

            val idx = channelList.indexOfFirst { it.id == idLong }
            if (idx != -1) {
                currentChannelIndex = idx
            }
        }

        loadChannels(channelId, channelName, channelNum, openEpg)
    }

    private fun loadChannels(initialId: String, initialName: String, initialNum: Int, openEpg: Boolean = false) {
        val cachedChannels = ChannelRegistry.getAllChannels()
        if (cachedChannels.isNotEmpty()) {
            channelList.clear()
            channelList.addAll(cachedChannels)
            channelList.sortBy { it.number }
            currentChannelIndex = channelList.indexOfFirst { it.id.toString() == initialId || it.uuid == initialId || it.number == initialNum }
            if (currentChannelIndex == -1) currentChannelIndex = 0
            if (!openEpg && currentlyTunedChannelId == null && channelList.isNotEmpty()) {
                playCurrentChannel()
            }
            if (openEpg && supportFragmentManager.findFragmentByTag("epg_timeline") == null) {
                openEpgGuide()
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
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
                        if (bytes != null && bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else serviceId.toString()
                    } else serviceId.toString()

                    loadedChannels.add(TvhChannel(dbId, streamKey, name, num, "", inputId))
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
                    currentChannelIndex = channelList.indexOfFirst { it.id.toString() == initialId || it.uuid == initialId || it.number == initialNum }
                    if (currentChannelIndex == -1) currentChannelIndex = 0
                }
                if (!openEpg && currentlyTunedChannelId == null && channelList.isNotEmpty()) {
                    playCurrentChannel()
                }

                if (openEpg && supportFragmentManager.findFragmentByTag("epg_timeline") == null) {
                    openEpgGuide()
                }
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

        Log.i("TvPlayerActivity", "Tuning TIF TvView with inputId=$inputId, channelUri=$channelUri")
        tvView.tune(inputId, channelUri)
        showTopOsdBanner(channel)
    }

    private fun showAudioOrSubtitlePicker() {
        AlertDialog.Builder(this)
            .setTitle("Audio & Subtitles")
            .setItems(arrayOf("Audio Track", "Subtitles")) { _, which ->
                if (which == 0) {
                    showTrackSelectionDialog(TvTrackInfo.TYPE_AUDIO)
                } else {
                    showTrackSelectionDialog(TvTrackInfo.TYPE_SUBTITLE)
                }
            }
            .show()
    }

    private fun showComposeOsdOverlay(channel: TvhChannel?) {
        val target = channel ?: channelList.getOrNull(currentChannelIndex)
        osdChannelState.value = target
        osdVisibleState.value = true
        osdInteractionTickState.intValue++

        CoroutineScope(Dispatchers.IO).launch {
            val currentEpg = if (target != null) {
                EpgRepository.getCurrentAndNextProgram(this@TvPlayerActivity, target.id)
            } else null
            val adjacent = channelList.filter { it.id != target?.id }.take(12)
            val adjacentMap = TvhUiRepository.loadCurrentEpgMap(this@TvPlayerActivity, adjacent)
            withContext(Dispatchers.Main) {
                osdCurrentEpgState.value = currentEpg
                osdAdjacentChannelsState.value = adjacent
                osdAdjacentEpgState.value = adjacentMap
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
                        tvView.selectTrack(TvTrackInfo.TYPE_SUBTITLE, null)
                    } else {
                        val chosen = tracks[which - 1]
                        Log.i("TvPlayerActivity", "Selected subtitle track: ${chosen.id}")
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

    private fun openEpgGuide() {
        if (supportFragmentManager.findFragmentByTag("epg_timeline") != null) return

        osdHandler.removeCallbacks(hideOsdRunnable)
        topOsdBanner.visibility = View.GONE
        bottomMochiMenu.visibility = View.GONE

        val fragment = EpgTimelineOverlayFragment.newInstance { selectedChannel ->
            epgChannelSelected = true
            val idx = channelList.indexOfFirst {
                it.id == selectedChannel.id ||
                (it.uuid.isNotEmpty() && it.uuid == selectedChannel.uuid) ||
                (it.number > 0 && it.number == selectedChannel.number)
            }
            if (idx != -1) {
                val targetChannel = channelList[idx]
                val targetStreamKey = if (targetChannel.uuid.isNotEmpty()) targetChannel.uuid else targetChannel.id.toString()

                if (currentlyTunedChannelId == targetChannel.id || (currentlyTunedStreamKey != null && (currentlyTunedStreamKey == targetStreamKey || currentlyTunedStreamKey == targetChannel.id.toString()))) {
                    Log.i("TvPlayerActivity", "Selected channel '${targetChannel.name}' is already active/playing; doing nothing.")
                    showTopOsdBanner(targetChannel)
                } else {
                    currentChannelIndex = idx
                    playCurrentChannel()
                }
            }
        }
        fragment.onDismissListener = {
            if (openedFromEpgIntent && !epgChannelSelected) {
                finish()
            }
        }
        fragment.show(supportFragmentManager, "epg_timeline")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
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
        }

        if (osdVisibleState.value) {
            osdInteractionTickState.intValue++
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP && channelList.isNotEmpty()) {
                currentChannelIndex = (currentChannelIndex + 1) % channelList.size
                playCurrentChannel()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN && channelList.isNotEmpty()) {
                currentChannelIndex = if (currentChannelIndex - 1 < 0) channelList.size - 1 else currentChannelIndex - 1
                playCurrentChannel()
                return true
            }
            return super.onKeyDown(keyCode, event)
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
                    currentChannelIndex = (currentChannelIndex + 1) % channelList.size
                    playCurrentChannel()
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channelList.isNotEmpty()) {
                    currentChannelIndex = if (currentChannelIndex - 1 < 0) channelList.size - 1 else currentChannelIndex - 1
                    playCurrentChannel()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                showComposeOsdOverlay(channelList.getOrNull(currentChannelIndex))
                return true
            }
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                openEpgGuide()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun releasePlayer() {
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

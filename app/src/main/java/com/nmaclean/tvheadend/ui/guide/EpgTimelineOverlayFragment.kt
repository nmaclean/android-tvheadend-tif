package com.nmaclean.tvheadend.ui.guide

import android.content.ComponentName
import android.content.Context
import android.annotation.SuppressLint
import androidx.appcompat.widget.AppCompatTextView
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.TvheadendInputService
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.htsp.HtspClient
import com.nmaclean.tvheadend.htsp.HtspConnectionPool
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.EpgRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

/**
 * Custom TextView for EPG program blocks that overrides focusSearch to guarantee
 * D-pad left/right navigation never skips adjacent program blocks, and unifies scroll handling
 * inside requestRectangleOnScreen to eliminate jittery grid jumps.
 */
@SuppressLint("AppCompatCustomView")
class ProgramBlockTextView(ctx: Context) : TextView(ctx) {
    var timelineStartMs: Long = 0L

    override fun focusSearch(direction: Int): View? {
        val parentRow = parent as? LinearLayout
        if (parentRow != null && (direction == FOCUS_RIGHT || direction == FOCUS_LEFT)) {
            val index = parentRow.indexOfChild(this)
            if (index != -1) {
                val targetIndex = if (direction == FOCUS_RIGHT) index + 1 else index - 1
                if (targetIndex in 0 until parentRow.childCount) {
                    return parentRow.getChildAt(targetIndex)
                }
            }
        }
        return super.focusSearch(direction)
    }

    override fun requestRectangleOnScreen(rectangle: Rect?, immediate: Boolean): Boolean {
        var p = parent
        var parentScroll: HorizontalScrollView? = null
        while (p != null) {
            if (p is HorizontalScrollView) {
                parentScroll = p
                break
            }
            p = p.parent
        }
        if (parentScroll != null) {
            val scrollX = parentScroll.scrollX
            val viewportWidth = parentScroll.width
            val viewLeft = left
            val viewRight = right

            val prog = tag as? EpgRepository.ProgramInfo
            val nowMs = System.currentTimeMillis()

            val targetX = if (prog != null && timelineStartMs > 0L && nowMs in prog.startTimeMs..prog.endTimeMs) {
                val diffMin = (nowMs - timelineStartMs) / (1000 * 60)
                (diffMin * 7f * resources.displayMetrics.density).toInt()
            } else {
                viewLeft
            }

            val px24 = (24 * resources.displayMetrics.density).toInt()
            if (viewLeft < scrollX || viewRight > scrollX + viewportWidth || (targetX < scrollX || targetX > scrollX + viewportWidth)) {
                val scrollToX = if (viewLeft < scrollX) {
                    (viewLeft - px24).coerceAtLeast(0)
                } else if (viewRight > scrollX + viewportWidth) {
                    (viewRight - viewportWidth + px24).coerceAtMost(parentScroll.getChildAt(0)?.width ?: viewRight)
                } else {
                    (targetX - viewportWidth / 2).coerceAtLeast(0)
                }
                parentScroll.smoothScrollTo(scrollToX, 0)
                return true
            }
        }
        return super.requestRectangleOnScreen(rectangle, immediate)
    }
}

/**
 * 10-foot Sony TV Style EPG Program Guide Overlay Fragment with 12-hour initial window,
 * lazy-loaded horizontal "Scan Forward" pagination, synchronized time ruler, live TIF PiP video preview, and remote color button bar.
 */
@OptIn(UnstableApi::class)
class EpgTimelineOverlayFragment : DialogFragment() {

    companion object {
        fun newInstance(onChannelSelected: (TvhChannel) -> Unit): EpgTimelineOverlayFragment {
            return EpgTimelineOverlayFragment().apply {
                this.onChannelSelected = onChannelSelected
            }
        }

        private const val DP_PER_MIN = 7f
        private const val ROW_HEIGHT_DP = 48
        private const val WINDOW_HOURS = 12
    }

    var onChannelSelected: ((TvhChannel) -> Unit)? = null
    var onDismissListener: (() -> Unit)? = null

    private lateinit var txtMainTitle: TextView
    private lateinit var txtDateClock: TextView
    private lateinit var txtRulerDate: TextView
    private lateinit var pipTvView: TvView

    private lateinit var timeSlotsHeaderContainer: LinearLayout
    private lateinit var timeSlotsScroll: HorizontalScrollView
    private lateinit var channelsColumnContainer: LinearLayout
    private lateinit var timelineRowsContainer: LinearLayout
    private lateinit var timelineHorizontalScroll: HorizontalScrollView
    private lateinit var currentTimeBlueLine: View
    private lateinit var epgLoadingOverlay: View

    private val dateFormat = SimpleDateFormat("EEE, d MMM HH:mm", Locale.getDefault())
    private val rulerDateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    private val hourFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var timelineStartMs = 0L
    private var timelineEndMs = 0L
    private val channelList = mutableListOf<TvhChannel>()
    private val rowLayoutMap = mutableMapOf<Long, LinearLayout>()
    private var isLoadingMoreHorizon = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_epg_timeline_overlay, container, false)

        HtspConnectionPool.preconnectAsync(requireContext())

        txtMainTitle = view.findViewById(R.id.txt_guide_main_title)
        txtDateClock = view.findViewById(R.id.txt_guide_date_clock)
        txtRulerDate = view.findViewById(R.id.txt_ruler_date)
        pipTvView = view.findViewById(R.id.pip_tv_view)

        timeSlotsHeaderContainer = view.findViewById(R.id.time_slots_header_container)
        timeSlotsScroll = view.findViewById(R.id.time_slots_scroll)
        channelsColumnContainer = view.findViewById(R.id.channels_column_container)
        timelineRowsContainer = view.findViewById(R.id.timeline_rows_container)
        timelineHorizontalScroll = view.findViewById(R.id.timeline_horizontal_scroll)
        currentTimeBlueLine = view.findViewById(R.id.current_time_blue_line)
        epgLoadingOverlay = view.findViewById(R.id.epg_loading_overlay)

        // Synchronize top time ruler scroll with timeline scroll and handle lazy loading ("Scan Forward")
        timelineHorizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            timeSlotsScroll.scrollTo(scrollX, 0)

            val child = timelineHorizontalScroll.getChildAt(0)
            val maxScroll = if (child != null) child.width - timelineHorizontalScroll.width else 0
            if (maxScroll > 0 && scrollX >= maxScroll - 600 && !isLoadingMoreHorizon) {
                isLoadingMoreHorizon = true
                expandTimelineHorizon()
            }
        }

        setupHeader()
        setupTimelineBounds()
        loadChannelsAndEpg()

        return view
    }

    private fun setupHeader() {
        val now = Date()
        txtDateClock.text = dateFormat.format(now)
        txtRulerDate.text = rulerDateFormat.format(now)
    }

    private fun setupTimelineBounds() {
        val ctx = context ?: return
        val cal = Calendar.getInstance()
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        timelineStartMs = cal.timeInMillis
        timelineEndMs = timelineStartMs + (WINDOW_HOURS * 3600 * 1000L)

        timeSlotsHeaderContainer.removeAllViews()

        // Initial window timeline divided into half-hour blocks
        val hourCal = Calendar.getInstance().apply { timeInMillis = timelineStartMs }
        val numHalfHours = WINDOW_HOURS * 2

        for (i in 0 until numHalfHours) {
            val timeText = hourFormat.format(hourCal.time)
            val slotWidthPx = dpToPx((30 * DP_PER_MIN).toInt())

            val slotView = TextView(ctx).apply {
                text = "│ $timeText"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                layoutParams = LinearLayout.LayoutParams(slotWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            timeSlotsHeaderContainer.addView(slotView)
            hourCal.add(Calendar.MINUTE, 30)
        }

        // Position current time vertical blue line
        val nowMs = System.currentTimeMillis()
        val diffMin = (nowMs - timelineStartMs) / (1000 * 60)
        if (diffMin >= 0) {
            val lineX = dpToPx((diffMin * DP_PER_MIN).toInt())
            val params = currentTimeBlueLine.layoutParams as RelativeLayout.LayoutParams
            params.leftMargin = lineX
            currentTimeBlueLine.layoutParams = params
        }
    }

    private fun expandTimelineHorizon() {
        val ctx = context ?: return
        val oldEndMs = timelineEndMs
        timelineEndMs += (WINDOW_HOURS * 3600 * 1000L)

        CoroutineScope(Dispatchers.IO).launch {
            // Blazingly fast single-query bulk fetch for scan forward window
            val additionalProgramsMap = EpgRepository.getAllProgramsInWindow(ctx, oldEndMs, timelineEndMs)

            withContext(Dispatchers.Main) {
                if (!isAdded || context == null || view == null) return@withContext
                val hourCal = Calendar.getInstance().apply { timeInMillis = oldEndMs }
                val numHalfHours = WINDOW_HOURS * 2

                for (i in 0 until numHalfHours) {
                    val timeText = hourFormat.format(hourCal.time)
                    val slotWidthPx = dpToPx((30 * DP_PER_MIN).toInt())

                    val slotView = TextView(ctx).apply {
                        text = "│ $timeText"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
                        setTypeface(null, Typeface.BOLD)
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        layoutParams = LinearLayout.LayoutParams(slotWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
                    }
                    timeSlotsHeaderContainer.addView(slotView)
                    hourCal.add(Calendar.MINUTE, 30)
                }

                val colorPrimary = ContextCompat.getColor(ctx, R.color.tv_text_primary)
                val colorCyan = ContextCompat.getColor(ctx, R.color.tv_accent_cyan)
                val selectorDrawable = ContextCompat.getDrawable(ctx, R.drawable.bg_program_selector)
                val dp2 = dpToPx(2)
                val dp8 = dpToPx(8)

                for ((chId, rowLayout) in rowLayoutMap) {
                    rowLayout.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    val channel = channelList.find { it.id == chId } ?: continue
                    val programs = additionalProgramsMap[chId] ?: emptyList()

                    // Remove trailing placeholder if it exists at the end of rowLayout
                    if (rowLayout.childCount > 0) {
                        val lastChild = rowLayout.getChildAt(rowLayout.childCount - 1)
                        val lastProg = lastChild.tag as? EpgRepository.ProgramInfo
                        if (lastProg != null && lastProg.eventId == 0L && lastProg.endTimeMs >= oldEndMs) {
                            rowLayout.removeViewAt(rowLayout.childCount - 1)
                        }
                    }

                    var currentCursorMs = oldEndMs

                    for (prog in programs) {
                        if (prog.endTimeMs <= oldEndMs) continue

                        val effectiveStartMs = prog.startTimeMs
                        if (effectiveStartMs > currentCursorMs) {
                            addGapBlocks(ctx, channel, rowLayout, currentCursorMs, effectiveStartMs, oldEndMs, timelineEndMs, colorPrimary, colorCyan, selectorDrawable, dp2, dp8)
                        }

                        rowLayout.addView(createProgramBlock(ctx, channel, prog, oldEndMs, timelineEndMs, colorPrimary, colorCyan, selectorDrawable, dp2, dp8))
                        currentCursorMs = prog.endTimeMs.coerceAtLeast(currentCursorMs)
                    }

                    if (currentCursorMs < timelineEndMs) {
                        addGapBlocks(ctx, channel, rowLayout, currentCursorMs, timelineEndMs, oldEndMs, timelineEndMs, colorPrimary, colorCyan, selectorDrawable, dp2, dp8)
                    }
                }

                isLoadingMoreHorizon = false
            }
        }
    }

    private fun loadChannelsAndEpg() {
        val ctx = context ?: return
        epgLoadingOverlay.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            val resolver = ctx.contentResolver
            val cachedChannels = ChannelRegistry.getAllChannels()

            val loadedChannels = if (cachedChannels.isNotEmpty()) {
                cachedChannels.sortedWith(compareBy({ it.number }, { it.name }))
            } else {
                val tifInputId = TvContract.buildInputId(ComponentName(ctx, TvheadendInputService::class.java))
                val queryChannels = mutableListOf<TvhChannel>()

                try {
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
                            val chInputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: tifInputId else tifInputId

                            val streamKey = if (dataCol >= 0) {
                                val bytes = cursor.getBlob(dataCol)
                                if (bytes != null && bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else serviceId.toString()
                            } else serviceId.toString()

                            queryChannels.add(TvhChannel(dbId, streamKey, name, num, "", chInputId))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("EpgTimeline", "Error querying TvContract.Channels", e)
                }

                if (queryChannels.isEmpty()) {
                    val settings = TvhSettings(ctx)
                    if (settings.host.isNotEmpty()) {
                        try {
                            val fetched = HtspClient(settings.host, settings.htspPort).use { client ->
                                if (client.connect(settings.username, settings.password)) {
                                    client.fetchChannels()
                                } else emptyList()
                            }
                            queryChannels.addAll(fetched)
                        } catch (e: Exception) {
                            Log.e("EpgTimeline", "Error in HTSP fallback", e)
                        }
                    }
                }

                queryChannels.distinctBy {
                    if (it.uuid.isNotEmpty()) it.uuid else if (it.number > 0) "num_${it.number}" else "name_${it.name}"
                }.sortedWith(compareBy({ it.number }, { it.name }))
            }

            if (loadedChannels.isNotEmpty()) {
                ChannelRegistry.updateChannels(loadedChannels)
            }

            // Single bulk query for all programs in the 12-hour window
            val programsMap = EpgRepository.getAllProgramsInWindow(ctx, timelineStartMs, timelineEndMs)

            // Load channel logos efficiently with downsampling
            val logoMap = mutableMapOf<Long, Bitmap>()
            val logoOptions = BitmapFactory.Options().apply { inSampleSize = 2 }
            for (ch in loadedChannels) {
                try {
                    val logoUri = TvContract.buildChannelLogoUri(ch.id)
                    resolver.openInputStream(logoUri)?.use { input ->
                        val bitmap = BitmapFactory.decodeStream(input, null, logoOptions)
                        if (bitmap != null) {
                            logoMap[ch.id] = bitmap
                        }
                    }
                } catch (ignored: Exception) {}
            }

            withContext(Dispatchers.Main) {
                if (!isAdded || context == null || view == null) return@withContext
                channelList.clear()
                channelList.addAll(loadedChannels)
                renderGridRows(logoMap, programsMap)
            }
        }
    }

    private suspend fun renderGridRows(
        logoMap: Map<Long, Bitmap>,
        programsMap: Map<Long, List<EpgRepository.ProgramInfo>>
    ) {
        val ctx = context ?: return
        channelsColumnContainer.removeAllViews()
        timelineRowsContainer.removeAllViews()
        rowLayoutMap.clear()

        val colorPrimary = ContextCompat.getColor(ctx, R.color.tv_text_primary)
        val colorCyan = ContextCompat.getColor(ctx, R.color.tv_accent_cyan)
        val rowBackground = ContextCompat.getDrawable(ctx, R.drawable.bg_channel_row)
        val selectorDrawable = ContextCompat.getDrawable(ctx, R.drawable.bg_program_selector)

        val dp2 = dpToPx(2)
        val dp4 = dpToPx(4)
        val dp6 = dpToPx(6)
        val dp8 = dpToPx(8)
        val dp10 = dpToPx(10)
        val dp32 = dpToPx(32)
        val dp44 = dpToPx(44)
        val dp210 = dpToPx(210)
        val rowHeightPx = dpToPx(ROW_HEIGHT_DP)

        val totalChannels = channelList.size
        val initialChunkSize = 8
        val remainingChunkSize = 5

        // Auto-scroll timeline horizontally directly to current live time
        val nowMs = System.currentTimeMillis()
        val diffMin = (nowMs - timelineStartMs) / (1000 * 60)
        if (diffMin > 0) {
            val currentX = dpToPx((diffMin * DP_PER_MIN).toInt())
            val targetScrollX = (currentX - dpToPx(120)).coerceAtLeast(0)
            timelineHorizontalScroll.post {
                timelineHorizontalScroll.scrollTo(targetScrollX, 0)
                timeSlotsScroll.scrollTo(targetScrollX, 0)
            }
        }

        var isFirstBatch = true

        for (i in 0 until totalChannels) {
            if (!isAdded || context == null || view == null) return
            val currentCtx = context ?: return
            val currentRes = currentCtx.resources
            val ch = channelList[i]

            // Render left channel sidebar box
            val channelBox = RelativeLayout(currentCtx).apply {
                background = rowBackground?.constantState?.newDrawable(currentRes) ?: rowBackground
                paddingBar()
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    onChannelSelected?.invoke(ch)
                    dismiss()
                }

                val params = LinearLayout.LayoutParams(dp210, rowHeightPx).apply {
                    setMargins(0, 0, 0, dp4)
                }
                layoutParams = params

                val logoIv = ImageView(currentCtx).apply {
                    id = View.generateViewId()
                    val bitmap = logoMap[ch.id]
                    if (bitmap != null) {
                        setImageBitmap(bitmap)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val logoParams = RelativeLayout.LayoutParams(dp44, dp32).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    setMargins(dp4, 0, dp8, 0)
                }
                addView(logoIv, logoParams)

                val channelInfoTv = TextView(currentCtx).apply {
                    text = "${ch.number}  •  ${ch.name}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(colorPrimary)
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }

                val textParams = RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    addRule(RelativeLayout.LEFT_OF, logoIv.id)
                    setMargins(dp10, 0, dp6, 0)
                }
                addView(channelInfoTv, textParams)
            }
            channelsColumnContainer.addView(channelBox)

            // Render right timeline row with EPG program blocks
            val rowLayout = LinearLayout(currentCtx).apply {
                orientation = LinearLayout.HORIZONTAL
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                isFocusable = false
                isFocusableInTouchMode = false
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    rowHeightPx
                ).apply {
                    setMargins(0, 0, 0, dp4)
                }
                layoutParams = params
            }
            rowLayoutMap[ch.id] = rowLayout

            val programs = programsMap[ch.id] ?: emptyList()
            var currentCursorMs = timelineStartMs

            for (prog in programs) {
                if (prog.endTimeMs <= timelineStartMs) continue

                val effectiveStartMs = prog.startTimeMs
                if (effectiveStartMs > currentCursorMs) {
                    addGapBlocks(currentCtx, ch, rowLayout, currentCursorMs, effectiveStartMs, timelineStartMs, timelineEndMs, colorPrimary, colorCyan, selectorDrawable, dp2, dp8)
                }

                rowLayout.addView(createProgramBlock(currentCtx, ch, prog, timelineStartMs, timelineEndMs, colorPrimary, colorCyan, selectorDrawable, dp2, dp8))
                currentCursorMs = prog.endTimeMs.coerceAtLeast(currentCursorMs)
            }

            if (currentCursorMs < timelineEndMs) {
                addGapBlocks(currentCtx, ch, rowLayout, currentCursorMs, timelineEndMs, timelineStartMs, timelineEndMs, colorPrimary, colorCyan, selectorDrawable, dp2, dp8)
            }

            timelineRowsContainer.addView(rowLayout)

            if (isFirstBatch && i == minOf(initialChunkSize - 1, totalChannels - 1)) {
                isFirstBatch = false
                epgLoadingOverlay.visibility = View.GONE
                focusFirstChannelProgram()
                delay(16)
            } else if (!isFirstBatch && (i + 1) % remainingChunkSize == 0 && i < totalChannels - 1) {
                delay(16)
            }
        }

        if (isFirstBatch) {
            epgLoadingOverlay.visibility = View.GONE
            focusFirstChannelProgram()
        }
    }

    override fun onResume() {
        super.onResume()
        focusFirstChannelProgram()
    }

    private fun focusFirstChannelProgram() {
        if (!isAdded || context == null || view == null) return
        if (channelList.isNotEmpty() && timelineRowsContainer.childCount > 0) {
            val firstRow = timelineRowsContainer.getChildAt(0) as? LinearLayout
            if (firstRow != null && firstRow.childCount > 0) {
                var targetBlock: View? = null
                val nowMs = System.currentTimeMillis()
                for (j in 0 until firstRow.childCount) {
                    val block = firstRow.getChildAt(j)
                    val prog = block.tag as? EpgRepository.ProgramInfo
                    if (prog != null && nowMs in prog.startTimeMs..prog.endTimeMs) {
                        targetBlock = block
                        break
                    }
                }
                val blockToFocus = targetBlock ?: firstRow.getChildAt(0)
                blockToFocus.post {
                    blockToFocus.requestFocus()
                }
            }
        }
    }

    private fun addGapBlocks(
        ctx: Context,
        channel: TvhChannel,
        rowLayout: LinearLayout,
        gapStartMs: Long,
        gapEndMs: Long,
        timelineStart: Long,
        timelineEnd: Long,
        colorPrimary: Int,
        colorCyan: Int,
        selectorDrawable: Drawable?,
        marginRightPx: Int,
        paddingPx: Int
    ) {
        val slotDurationMs = 30 * 60 * 1000L
        var cursor = gapStartMs
        while (cursor < gapEndMs) {
            val nextCursor = (cursor + slotDurationMs).coerceAtMost(gapEndMs)
            val gapProg = EpgRepository.ProgramInfo(
                eventId = 0L,
                channelId = channel.id,
                title = "No programme information.",
                summary = "",
                startTimeMs = cursor,
                endTimeMs = nextCursor
            )
            rowLayout.addView(createProgramBlock(ctx, channel, gapProg, timelineStart, timelineEnd, colorPrimary, colorCyan, selectorDrawable, marginRightPx, paddingPx))
            cursor = nextCursor
        }
    }

    private fun createProgramBlock(
        ctx: Context,
        channel: TvhChannel,
        program: EpgRepository.ProgramInfo,
        timelineStart: Long,
        timelineEnd: Long,
        colorPrimary: Int,
        colorCyan: Int,
        selectorDrawable: Drawable?,
        marginRightPx: Int,
        paddingPx: Int
    ): View {
        val effectiveStartMs = maxOf(program.startTimeMs, timelineStart)
        val effectiveEndMs = minOf(program.endTimeMs, timelineEnd + (12 * 3600 * 1000L))
        val durationMs = (effectiveEndMs - effectiveStartMs).coerceIn(15 * 60 * 1000L, 24 * 3600 * 1000L)
        val durationMin = durationMs / (1000 * 60)
        val widthPx = dpToPx((durationMin * DP_PER_MIN).toInt()).coerceAtLeast(dpToPx(60))

        val startStr = hourFormat.format(program.startTimeMs)
        val endStr = hourFormat.format(program.endTimeMs)

        val blockText = if (program.eventId == 0L) program.title else "$startStr  ${program.title}"

        val blockView = ProgramBlockTextView(ctx).apply {
            tag = program
            timelineStartMs = timelineStart
            text = blockText
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(colorPrimary)
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = selectorDrawable?.constantState?.newDrawable(ctx.resources) ?: selectorDrawable
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(paddingPx, 0, paddingPx, 0)

            val params = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                setMargins(0, 0, marginRightPx, 0)
            }
            layoutParams = params

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setTextColor(colorCyan)
                    txtDateClock.text = "${dateFormat.format(program.startTimeMs)} - $endStr"

                    // Auto-expand timeline horizon when D-pad focus approaches the right edge (within 2 hours of timelineEndMs)
                    if (program.endTimeMs >= timelineEndMs - (2 * 3600 * 1000L) && !isLoadingMoreHorizon) {
                        isLoadingMoreHorizon = true
                        expandTimelineHorizon()
                    }
                } else {
                    setTextColor(colorPrimary)
                }
            }

            setOnClickListener {
                onChannelSelected?.invoke(channel)
                dismiss()
            }
        }

        return blockView
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissListener?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            pipTvView.reset()
        } catch (ignored: Exception) {}
    }

    private fun View.paddingBar() {
        val ctx = context ?: return
        val px12 = (12 * ctx.resources.displayMetrics.density).toInt()
        val px8 = (8 * ctx.resources.displayMetrics.density).toInt()
        setPadding(px12, px8, px12, px8)
    }

    private fun dpToPx(dp: Int): Int {
        val ctx = context ?: return (dp * 2)
        return (dp * ctx.resources.displayMetrics.density).toInt()
    }
}

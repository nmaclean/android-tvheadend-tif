package com.nmaclean.tvheadend.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.ui.TvhUiRepository
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTopBarWithClock
import com.nmaclean.tvheadend.ui.theme.TvhPillButton
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DP_PER_MINUTE = 2.6f
private const val SLOT_MINUTES = 30
private const val TOTAL_SLOTS = 24 // 12-hour continuous window
private val CHANNEL_HEADER_WIDTH = 116.dp
private val ROW_HEIGHT = 46.dp

/**
 * Continuous EPG Guide with inline preview card, sticky 30-minute intervals, and vertical cyan NOW line (`epg.png`).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GuideScreen(
    channels: List<TvhChannel>,
    currentEpg: Map<Long, EpgRepository.ChannelEpg>,
    guidePrograms: Map<Long, List<EpgRepository.ProgramInfo>>,
    windowStartMs: Long,
    initialChannelId: Long? = null,
    scheduledEventIds: Set<Long> = emptySet(),
    seriesTitles: Set<String> = emptySet(),
    onTuneChannel: (TvhChannel) -> Unit,
    onScheduleProgram: (TvhChannel, EpgRepository.ProgramInfo) -> Unit,
    gridFocusRequester: FocusRequester = remember { FocusRequester() },
    modifier: Modifier = Modifier
) {
    val categories = TvhUiRepository.CHANNEL_CATEGORIES
    var selectedCategoryIdx by remember { mutableIntStateOf(0) }
    val selectedCategory = categories[selectedCategoryIdx]

    val filteredChannels = remember(channels, selectedCategory, currentEpg) {
        TvhUiRepository.filterChannelsByCategory(channels, selectedCategory, currentEpg)
    }

    val alignedStartMs = remember(windowStartMs) {
        val cal = Calendar.getInstance().apply {
            timeInMillis = windowStartMs
            set(Calendar.MINUTE, if (get(Calendar.MINUTE) < 30) 0 else 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        cal.timeInMillis
    }
    val windowEndMs = alignedStartMs + (TOTAL_SLOTS * SLOT_MINUTES * 60_000L)
    val timelineTotalWidthDp = (TOTAL_SLOTS * SLOT_MINUTES * DP_PER_MINUTE).dp

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }

    val horizontalScrollState = rememberScrollState()
    val showNowButton by remember { derivedStateOf { horizontalScrollState.value > 40 } }
    val slotFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val pillsFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val hourJumpPx = with(androidx.compose.ui.platform.LocalDensity.current) { (60f * DP_PER_MINUTE).dp.toPx().toInt() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            scope.launch {
                                horizontalScrollState.animateScrollTo(
                                    (horizontalScrollState.value + hourJumpPx).coerceAtMost(horizontalScrollState.maxValue)
                                )
                            }
                            true
                        }
                        android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            scope.launch {
                                horizontalScrollState.animateScrollTo(
                                    (horizontalScrollState.value - hourJumpPx).coerceAtLeast(0)
                                )
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .padding(
                horizontal = MidnightCyanTokens.OverscanHorizontal,
                vertical = MidnightCyanTokens.OverscanVertical
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvTopBarWithClock(
                pills = categories,
                selectedIndex = selectedCategoryIdx,
                onSelect = { selectedCategoryIdx = it },
                firstPillFocusRequester = pillsFocusRequester,
                downFocusRequester = gridFocusRequester,
                modifier = Modifier.weight(1f)
            )
            if (showNowButton) {
                TvhPillButton(
                    label = "Now",
                    selected = false,
                    onClick = { scope.launch { horizontalScrollState.animateScrollTo(0) } }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (filteredChannels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No channels available in $selectedCategory.",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 15.sp
                )
            }
            return@Column
        }

        // Sticky Time Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                modifier = Modifier
                    .width(CHANNEL_HEADER_WIDTH)
                    .padding(end = 8.dp, bottom = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "CHANNELS (${filteredChannels.size})",
                    color = MidnightCyanTokens.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScrollState)
            ) {
                // "NOW" pill sits above the time slot labels so it never overlaps them
                Box(modifier = Modifier.width(timelineTotalWidthDp)) {
                    GuideNowLabel(
                        alignedStartMs = alignedStartMs,
                        windowEndMs = windowEndMs
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Row(modifier = Modifier.width(timelineTotalWidthDp)) {
                    for (slot in 0 until TOTAL_SLOTS) {
                        val slotMs = alignedStartMs + slot * SLOT_MINUTES * 60_000L
                        Row(
                            modifier = Modifier
                                .width((SLOT_MINUTES * DP_PER_MINUTE).dp)
                                .padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(12.dp)
                                    .background(MidnightCyanTokens.DividerSubtle)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = slotFormat.format(Date(slotMs)),
                                color = MidnightCyanTokens.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MidnightCyanTokens.DividerSubtle)
        )
        Spacer(modifier = Modifier.height(6.dp))

        val rowProgramsByChannelId = remember(filteredChannels, guidePrograms, currentEpg, alignedStartMs, windowEndMs) {
            filteredChannels.associate { ch ->
                val raw = guidePrograms[ch.id].orEmpty().ifEmpty {
                    listOfNotNull(currentEpg[ch.id]?.currentProgram, currentEpg[ch.id]?.nextProgram)
                }
                val items = raw
                    .filter { it.endTimeMs > alignedStartMs && it.startTimeMs < windowEndMs }
                    .map { prog -> prog to TvhUiRepository.classifyEpgGenre(prog.title, prog.summary, ch.name) }
                ch.id to items
            }
        }

        val lazyListState = rememberLazyListState()
        val rowChannelFocusRequesters = remember(filteredChannels) {
            List(filteredChannels.size) { FocusRequester() }
        }
        val cellFocusRequesters = remember(filteredChannels, rowProgramsByChannelId) {
            filteredChannels.map { ch ->
                val count = maxOf(1, rowProgramsByChannelId[ch.id].orEmpty().size)
                List(count) { FocusRequester() }
            }
        }
        var focusedRowIndex by remember { mutableIntStateOf(0) }
        LaunchedEffect(initialChannelId, filteredChannels.size) {
            if (initialChannelId != null) {
                val idx = filteredChannels.indexOfFirst { it.id == initialChannelId }
                if (idx >= 0) {
                    focusedRowIndex = idx
                    lazyListState.scrollToItem(idx)
                    try {
                        cellFocusRequesters.getOrNull(idx)?.firstOrNull()?.requestFocus()
                    } catch (_: Exception) {}
                }
            }
        }
        var focusedIsChannelHeader by remember { mutableStateOf(false) }
        var isVerticalNav by remember { mutableStateOf(false) }
        var cursorTimeMs by remember(alignedStartMs, windowEndMs) {
            mutableLongStateOf(System.currentTimeMillis().coerceIn(alignedStartMs, windowEndMs))
        }

        val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
        val horizontalStabilitySpec = remember(defaultBringIntoViewSpec) {
            object : BringIntoViewSpec {
                override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                    return if (isVerticalNav) 0f else defaultBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
                }
            }
        }

        fun findTargetProgIndex(rowIdx: Int): Int {
            val progs = rowProgramsByChannelId[filteredChannels.getOrNull(rowIdx)?.id].orEmpty()
            if (progs.isEmpty()) return 0
            val exact = progs.indexOfFirst { (p, _) -> cursorTimeMs in p.startTimeMs..p.endTimeMs }
            if (exact >= 0) return exact
            return progs.indices.minByOrNull { idx ->
                val p = progs[idx].first
                val clampedStart = maxOf(p.startTimeMs, alignedStartMs)
                val clampedEnd = minOf(p.endTimeMs, windowEndMs)
                val mid = (clampedStart + clampedEnd) / 2L
                kotlin.math.abs(mid - cursorTimeMs)
            } ?: 0
        }

        fun focusRowAtCurrentColumn(targetRow: Int) {
            if (targetRow !in filteredChannels.indices) return
            isVerticalNav = true
            val visibleIndices = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
            val needScroll = targetRow !in visibleIndices
            scope.launch {
                if (needScroll) {
                    lazyListState.scrollToItem(targetRow)
                }
                val requester = if (focusedIsChannelHeader) {
                    rowChannelFocusRequesters[targetRow]
                } else {
                    val progIdx = findTargetProgIndex(targetRow)
                    cellFocusRequesters[targetRow][progIdx]
                }
                try {
                    requester.requestFocus()
                } catch (_: Exception) {}
            }
        }

        // Schedule Timeline Grid with overlaid vertical NOW line
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = lazyListState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRestorer()
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            when (keyEvent.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    if (focusedRowIndex + 1 < filteredChannels.size) {
                                        focusRowAtCurrentColumn(focusedRowIndex + 1)
                                    }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                    if (focusedRowIndex > 0) {
                                        focusRowAtCurrentColumn(focusedRowIndex - 1)
                                    } else {
                                        isVerticalNav = false
                                        try {
                                            pillsFocusRequester.requestFocus()
                                        } catch (_: Exception) {}
                                    }
                                    true
                                }
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                    isVerticalNav = false
                                    false
                                }
                                else -> false
                            }
                        } else false
                    }
            ) {
                itemsIndexed(filteredChannels, key = { _, ch -> ch.id }) { rowIndex, channel ->
                    val rowPrograms = rowProgramsByChannelId[channel.id].orEmpty()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Row Header: Channel Number + Picon (clickable to tune directly)
                        ChannelIdentityTile(
                            channel = channel,
                            onFocused = {
                                focusedRowIndex = rowIndex
                                focusedIsChannelHeader = true
                            },
                            onClick = { onTuneChannel(channel) },
                            modifier = Modifier
                                .width(CHANNEL_HEADER_WIDTH)
                                .fillMaxHeight()
                                .padding(end = 10.dp)
                                .focusRequester(rowChannelFocusRequesters[rowIndex])
                        )

                        // Timeline Program Cells
                        CompositionLocalProvider(LocalBringIntoViewSpec provides horizontalStabilitySpec) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .horizontalScroll(horizontalScrollState)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(timelineTotalWidthDp)
                                        .fillMaxHeight()
                                ) {
                                    if (rowPrograms.isEmpty()) {
                                        val firstCellMod = if (rowIndex == lazyListState.firstVisibleItemIndex) {
                                            Modifier
                                                .focusRequester(gridFocusRequester)
                                                .focusRequester(cellFocusRequesters[rowIndex][0])
                                        } else {
                                            Modifier.focusRequester(cellFocusRequesters[rowIndex][0])
                                        }
                                        GuideProgramCell(
                                            title = "${channel.name} — Live Broadcast",
                                            timeRange = "Live TV • Continuous Broadcast",
                                            genre = TvhUiRepository.classifyEpgGenre(channel.name, null, channel.name),
                                            isLiveNow = true,
                                            progressFraction = 0f,
                                            widthDp = timelineTotalWidthDp,
                                            onFocused = {
                                                focusedRowIndex = rowIndex
                                                focusedIsChannelHeader = false
                                            },
                                            onClick = { onTuneChannel(channel) },
                                            onLongClick = { onTuneChannel(channel) },
                                            modifier = firstCellMod.fillMaxHeight()
                                        )
                                    } else {
                                        rowPrograms.forEachIndexed { progIdx, (prog, genre) ->
                                            val clampedStartMs = maxOf(prog.startTimeMs, alignedStartMs)
                                            val clampedEndMs = minOf(prog.endTimeMs, windowEndMs)
                                            val startOffsetDp = (((clampedStartMs - alignedStartMs).coerceAtLeast(0L)) / 60_000f * DP_PER_MINUTE).dp
                                            val rawWidthDp = (((clampedEndMs - clampedStartMs).coerceAtLeast(60_000L)) / 60_000f * DP_PER_MINUTE) - 4f
                                            val widthDp = maxOf(36f, rawWidthDp).dp
                                            val isLiveNow = nowMs in prog.startTimeMs..prog.endTimeMs
                                            val progressFraction = if (isLiveNow && prog.endTimeMs > prog.startTimeMs) {
                                                ((nowMs - prog.startTimeMs).toFloat() / (prog.endTimeMs - prog.startTimeMs).toFloat()).coerceIn(0f, 1f)
                                            } else {
                                                0f
                                            }
                                            val isRecordingScheduled = (prog.eventId > 0L && prog.eventId in scheduledEventIds) ||
                                                prog.title.trim().lowercase() in seriesTitles
                                            val cellMod = if (rowIndex == lazyListState.firstVisibleItemIndex && progIdx == 0) {
                                                Modifier
                                                    .focusRequester(gridFocusRequester)
                                                    .focusRequester(cellFocusRequesters[rowIndex][progIdx])
                                            } else {
                                                Modifier.focusRequester(cellFocusRequesters[rowIndex][progIdx])
                                            }
                                            GuideProgramCell(
                                                title = prog.title,
                                                timeRange = TvhUiRepository.formatTimeRange(prog.startTimeMs, prog.endTimeMs),
                                                genre = genre,
                                                isLiveNow = isLiveNow,
                                                progressFraction = progressFraction,
                                                isRecordingScheduled = isRecordingScheduled,
                                                widthDp = widthDp,
                                                onFocused = {
                                                    focusedRowIndex = rowIndex
                                                    focusedIsChannelHeader = false
                                                    if (!isVerticalNav) {
                                                        cursorTimeMs = ((clampedStartMs + clampedEndMs) / 2L)
                                                            .coerceIn(alignedStartMs, windowEndMs)
                                                    }
                                                },
                                                onClick = {
                                                    if (isLiveNow) {
                                                        onTuneChannel(channel)
                                                    } else {
                                                        onScheduleProgram(channel, prog)
                                                    }
                                                },
                                                onLongClick = {
                                                    onScheduleProgram(channel, prog)
                                                },
                                                modifier = cellMod
                                                    .offset(x = startOffsetDp)
                                                    .fillMaxHeight()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Overlaid vertical NOW tracker line across all rows (isolated 30s tick, layout-phase scroll offset)
            GuideNowLineOverlay(
                alignedStartMs = alignedStartMs,
                windowEndMs = windowEndMs,
                scrollState = horizontalScrollState
            )
        }
    }
}

@Composable
private fun rememberNowOffsetDp(alignedStartMs: Long, windowEndMs: Long): androidx.compose.ui.unit.Dp {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    return TvhUiRepository.computeNowOffsetDp(
        nowMs = nowMs.coerceIn(alignedStartMs, windowEndMs),
        windowStartMs = alignedStartMs,
        dpPerMinute = DP_PER_MINUTE
    ).dp
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideNowLabel(alignedStartMs: Long, windowEndMs: Long) {
    val nowOffsetDp = rememberNowOffsetDp(alignedStartMs, windowEndMs)
    Box(
        modifier = Modifier
            .offset(x = (nowOffsetDp - 18.dp).coerceAtLeast(0.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(MidnightCyanTokens.CyanAccent.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = "NOW",
            color = MidnightCyanTokens.CyanAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun GuideNowLineOverlay(
    alignedStartMs: Long,
    windowEndMs: Long,
    scrollState: ScrollState
) {
    val nowOffsetDp = rememberNowOffsetDp(alignedStartMs, windowEndMs)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val headerPx = with(density) { CHANNEL_HEADER_WIDTH.toPx() }
    val nowOffsetPx = with(density) { nowOffsetDp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                val x = (headerPx + nowOffsetPx - scrollState.value).roundToInt()
                IntOffset(x = x, y = 0)
            }
            .width(2.dp)
            .fillMaxHeight()
            .shadow(
                elevation = 8.dp,
                ambientColor = MidnightCyanTokens.CyanAccent,
                spotColor = MidnightCyanTokens.CyanAccent
            )
            .background(MidnightCyanTokens.CyanAccent)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelIdentityTile(
    channel: TvhChannel,
    onFocused: () -> Unit = {},
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .then(
                if (isFocused) {
                    Modifier.focusCyanBorder(isFocused = true, cornerRadius = 10.dp)
                } else {
                    Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = channel.number.toString(),
            color = if (isFocused) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        var logoFailed by remember(channel.id, channel.icon) { mutableStateOf(false) }
        if (channel.icon.isNotBlank() && !logoFailed) {
            AsyncImage(
                model = channel.icon,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                onError = { logoFailed = true },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
            )
        } else {
            Text(
                text = channel.name,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideProgramCell(
    title: String,
    timeRange: String,
    genre: TvhUiRepository.EpgGenre = TvhUiRepository.EpgGenre.GENERAL,
    isLiveNow: Boolean = false,
    progressFraction: Float = 0f,
    isRecordingScheduled: Boolean = false,
    widthDp: androidx.compose.ui.unit.Dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var keyDownActive by remember { mutableStateOf(false) }
    var longPressHandled by remember { mutableStateOf(false) }

    LaunchedEffect(keyDownActive) {
        if (keyDownActive) {
            delay(350L)
            if (keyDownActive && !longPressHandled) {
                longPressHandled = true
                onLongClick()
            }
        }
    }

    val bgColor = when {
        isFocused -> Color(genre.cardFocusedHex)
        isLiveNow && genre == TvhUiRepository.EpgGenre.GENERAL -> MidnightCyanTokens.CardBgElevated
        else -> Color(genre.cardBgHex)
    }
    val accentColor = if (genre != TvhUiRepository.EpgGenre.GENERAL) {
        Color(genre.accentHex)
    } else if (isLiveNow) {
        MidnightCyanTokens.CyanAccent
    } else {
        Color.Transparent
    }
    val cellShape = RoundedCornerShape(9.dp)

    Box(
        modifier = modifier
            .width(widthDp)
            .clip(cellShape)
            .background(bgColor)
            .then(
                if (isFocused) {
                    Modifier.focusCyanBorder(isFocused = true, cornerRadius = 9.dp)
                } else {
                    Modifier.border(
                        1.dp,
                        if (isLiveNow) MidnightCyanTokens.CyanAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        cellShape
                    )
                }
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) {
                    keyDownActive = false
                    longPressHandled = false
                } else {
                    onFocused()
                }
            }
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                when {
                    code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == android.view.KeyEvent.KEYCODE_ENTER ||
                        code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            if (keyEvent.nativeKeyEvent.repeatCount == 0) {
                                keyDownActive = true
                                longPressHandled = false
                            } else if ((keyEvent.nativeKeyEvent.isLongPress || keyEvent.nativeKeyEvent.repeatCount >= 2) && !longPressHandled) {
                                longPressHandled = true
                                onLongClick()
                            }
                            true
                        } else if (keyEvent.type == KeyEventType.KeyUp) {
                            val wasLong = longPressHandled
                            keyDownActive = false
                            longPressHandled = false
                            if (!wasLong) {
                                onClick()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    keyEvent.type == KeyEventType.KeyDown && (
                        code == android.view.KeyEvent.KEYCODE_INFO ||
                            code == android.view.KeyEvent.KEYCODE_MENU ||
                            code == android.view.KeyEvent.KEYCODE_MEDIA_RECORD ||
                            code == android.view.KeyEvent.KEYCODE_PROG_RED ||
                            code == android.view.KeyEvent.KEYCODE_PROG_YELLOW ||
                            code == android.view.KeyEvent.KEYCODE_PROG_BLUE
                        ) -> {
                        onLongClick()
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (accentColor != Color.Transparent) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accentColor)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isLiveNow) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MidnightCyanTokens.LiveBadgeRed)
                        )
                    }
                    if (isRecordingScheduled) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF1744))
                        )
                    }
                    Text(
                        text = title,
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = if (isFocused || isLiveNow) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (widthDp >= 72.dp && timeRange.isNotBlank()) {
                    Spacer(modifier = Modifier.height(1.dp))
                    val metaText = buildString {
                        append(timeRange)
                        if (isFocused && isLiveNow && widthDp >= 180.dp) {
                            append("  •  INFO / Hold OK: Details")
                        } else if (genre != TvhUiRepository.EpgGenre.GENERAL && widthDp >= 145.dp) {
                            append("  •  ${genre.label}")
                        }
                    }
                    Text(
                        text = metaText,
                        color = if (isFocused && isLiveNow) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (isLiveNow && progressFraction > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.14f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .height(3.dp)
                        .background(MidnightCyanTokens.CyanAccent)
                )
            }
        }
    }
}

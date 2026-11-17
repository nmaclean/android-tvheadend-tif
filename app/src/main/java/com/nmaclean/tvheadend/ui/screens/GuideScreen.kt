package com.nmaclean.tvheadend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.ui.TvhUiRepository
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTopBarWithClock
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val DP_PER_MINUTE = 4.2f
private const val SLOT_MINUTES = 30
private const val TOTAL_SLOTS = 8 // 4-hour continuous window
private val CHANNEL_HEADER_WIDTH = 116.dp
private val ROW_HEIGHT = 64.dp

/**
 * Continuous EPG Guide with inline preview card, sticky 30-minute intervals, and vertical cyan NOW line (`epg.png`).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun GuideScreen(
    channels: List<TvhChannel>,
    currentEpg: Map<Long, EpgRepository.ChannelEpg>,
    guidePrograms: Map<Long, List<EpgRepository.ProgramInfo>>,
    windowStartMs: Long,
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

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
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

    var focusedChannel by remember(filteredChannels) { mutableStateOf(filteredChannels.firstOrNull()) }
    var focusedProgram by remember(filteredChannels, guidePrograms, currentEpg) {
        val firstCh = filteredChannels.firstOrNull()
        val firstProg = firstCh?.let { ch ->
            guidePrograms[ch.id]?.firstOrNull() ?: currentEpg[ch.id]?.currentProgram
        }
        mutableStateOf(firstProg)
    }

    val horizontalScrollState = rememberScrollState()
    val slotFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = MidnightCyanTokens.OverscanHorizontal,
                vertical = MidnightCyanTokens.OverscanVertical
            )
    ) {
        TvTopBarWithClock(
            pills = categories,
            selectedIndex = selectedCategoryIdx,
            onSelect = { selectedCategoryIdx = it },
            modifier = Modifier
                .fillMaxWidth()
                .focusProperties { down = gridFocusRequester }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Inline Preview Card (Left: 16:9 banner, Center: Title/time/synopsis, Right: white monochrome picon)
        GuideInlinePreviewCard(
            channel = focusedChannel,
            program = focusedProgram,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

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

        val nowOffsetDp = TvhUiRepository.computeNowOffsetDp(
            nowMs = nowMs.coerceIn(alignedStartMs, windowEndMs),
            windowStartMs = alignedStartMs,
            dpPerMinute = DP_PER_MINUTE
        ).dp

        // Sticky Time Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(CHANNEL_HEADER_WIDTH))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(horizontalScrollState)
            ) {
                Row(modifier = Modifier.width(timelineTotalWidthDp)) {
                    for (slot in 0 until TOTAL_SLOTS) {
                        val slotMs = alignedStartMs + slot * SLOT_MINUTES * 60_000L
                        Box(
                            modifier = Modifier
                                .width((SLOT_MINUTES * DP_PER_MINUTE).dp)
                                .padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = slotFormat.format(Date(slotMs)),
                                color = MidnightCyanTokens.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // "NOW" label aligned with the vertical cyan line
                Text(
                    text = "NOW",
                    color = MidnightCyanTokens.CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .offset(x = (nowOffsetDp - 14.dp).coerceAtLeast(0.dp))
                        .padding(bottom = 4.dp)
                )
            }
        }

        // Schedule Timeline Grid with overlaid vertical NOW line
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(gridFocusRequester)
                    .focusRestorer()
            ) {
                items(filteredChannels, key = { it.id }) { channel ->
                    val rowPrograms = guidePrograms[channel.id].orEmpty().ifEmpty {
                        listOfNotNull(currentEpg[channel.id]?.currentProgram, currentEpg[channel.id]?.nextProgram)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Row Header: Channel Number + Picon
                        ChannelIdentityTile(
                            channel = channel,
                            modifier = Modifier
                                .width(CHANNEL_HEADER_WIDTH)
                                .fillMaxHeight()
                                .padding(end = 8.dp)
                        )

                        // Timeline Program Cells
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
                                    GuideProgramCell(
                                        title = "${channel.name} — Live Broadcast",
                                        widthDp = (120 * DP_PER_MINUTE).dp,
                                        onFocused = {
                                            focusedChannel = channel
                                            focusedProgram = null
                                        },
                                        onClick = { onTuneChannel(channel) },
                                        modifier = Modifier.fillMaxHeight()
                                    )
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.fillMaxHeight()
                                    ) {
                                        rowPrograms.forEach { prog ->
                                            val widthDp = TvhUiRepository.computeProgramWidthDp(
                                                startMs = prog.startTimeMs,
                                                endMs = prog.endTimeMs,
                                                windowStartMs = alignedStartMs,
                                                windowEndMs = windowEndMs,
                                                dpPerMinute = DP_PER_MINUTE
                                            ).dp
                                            val isFuture = prog.startTimeMs > nowMs + 60_000L
                                            GuideProgramCell(
                                                title = prog.title,
                                                widthDp = widthDp,
                                                onFocused = {
                                                    focusedChannel = channel
                                                    focusedProgram = prog
                                                },
                                                onClick = {
                                                    if (isFuture) {
                                                        onScheduleProgram(channel, prog)
                                                    } else {
                                                        onTuneChannel(channel)
                                                    }
                                                },
                                                modifier = Modifier.fillMaxHeight()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Overlaid vertical NOW tracker line across all rows
            val scrollOffsetDp = (horizontalScrollState.value / androidx.compose.ui.platform.LocalDensity.current.density).dp
            val visibleNowX = CHANNEL_HEADER_WIDTH + nowOffsetDp - scrollOffsetDp
            if (visibleNowX >= CHANNEL_HEADER_WIDTH) {
                Box(
                    modifier = Modifier
                        .offset(x = visibleNowX)
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
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideInlinePreviewCard(
    channel: TvhChannel?,
    program: EpgRepository.ProgramInfo?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .height(104.dp)
            .clip(shape)
            .background(MidnightCyanTokens.CardBg.copy(alpha = 0.72f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: 16:9 show banner
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        listOf(MidnightCyanTokens.CardBgElevated, MidnightCyanTokens.BgGradientBottom)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!channel?.icon.isNullOrBlank()) {
                AsyncImage(
                    model = channel!!.icon,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = channel?.name ?: "TV",
                    color = MidnightCyanTokens.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Center: Title, time range, and episode synopsis
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = program?.title ?: channel?.name ?: "Select a program",
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (program != null) {
                Text(
                    text = TvhUiRepository.formatTimeRange(program.startTimeMs, program.endTimeMs),
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = program?.summary?.takeIf { it.isNotBlank() }
                    ?: "Press OK to tune to ${channel?.name ?: "this channel"} immediately.",
                color = MidnightCyanTokens.TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right: Broadcaster white monochrome picon
        Box(
            modifier = Modifier
                .width(108.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (!channel?.icon.isNullOrBlank()) {
                AsyncImage(
                    model = channel!!.icon,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                )
            } else {
                Text(
                    text = channel?.name ?: "",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChannelIdentityTile(
    channel: TvhChannel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MidnightCyanTokens.CardBg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = channel.number.toString(),
            color = MidnightCyanTokens.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (channel.icon.isNotBlank()) {
            AsyncImage(
                model = channel.icon,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                colorFilter = ColorFilter.tint(Color.White),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
            )
        } else {
            Text(
                text = channel.name,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GuideProgramCell(
    title: String,
    widthDp: androidx.compose.ui.unit.Dp,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .width(widthDp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
            .focusCyanBorder(isFocused = isFocused, cornerRadius = 10.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = title,
            color = MidnightCyanTokens.TextPrimary,
            fontSize = 14.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

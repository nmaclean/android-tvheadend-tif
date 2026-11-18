package com.nmaclean.tvheadend.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.TvhPillButton
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import kotlinx.coroutines.delay

/**
 * Interactive video HUD & overlay (`osd.jpg`) rendered over [TvView] / [PlayerView].
 * Fades out automatically after 5 seconds of inactivity.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerOsdOverlay(
    visible: Boolean,
    isTuning: Boolean = false,
    isPaused: Boolean = false,
    isRecording: Boolean = false,
    interactionTick: Int,
    currentChannel: TvhChannel?,
    currentEpg: EpgRepository.ChannelEpg?,
    adjacentChannels: List<TvhChannel>,
    adjacentEpg: Map<Long, EpgRepository.ChannelEpg>,
    onTimeout: () -> Unit,
    onTogglePause: () -> Unit = {},
    onToggleRecord: () -> Unit = {},
    onGuideClick: () -> Unit,
    onAudioSubtitlesClick: () -> Unit,
    onQuickZapChannel: (TvhChannel) -> Unit,
    modifier: Modifier = Modifier
) {
    val guideFocusRequester = remember { FocusRequester() }
    val firstQuickZapFocusRequester = remember { FocusRequester() }
    val quickZapListState = rememberLazyListState()

    LaunchedEffect(visible) {
        if (visible) {
            delay(60L)
            try {
                guideFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(visible, isPaused, interactionTick) {
        if (visible && !isPaused) {
            delay(5_000L)
            onTimeout()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Top bar: LIVE badge + Clock Header
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xAA060A12), Color.Transparent)
                        )
                    )
                    .padding(
                        horizontal = MidnightCyanTokens.OverscanHorizontal,
                        vertical = MidnightCyanTokens.OverscanVertical
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isPaused) Color(0xFFF59E0B) else MidnightCyanTokens.LiveBadgeRed)
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isPaused) "PAUSED" else "LIVE",
                            color = if (isPaused) Color(0xFF060A12) else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                    if (isRecording) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xCC1E0B10))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF1744))
                            )
                            Text(
                                text = "REC",
                                color = Color(0xFFFF5252),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                    if (isTuning) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MidnightCyanTokens.CardBg.copy(alpha = 0.9f))
                                .padding(horizontal = 10.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MidnightCyanTokens.CyanAccent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "TUNING...",
                                color = MidnightCyanTokens.CyanAccent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }
                }

                TvClockHeader()
            }

            // Bottom HUD + "ON NOW" Quick-Zap strip
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xCC060A12),
                                Color(0xF2060A12)
                            )
                        )
                    )
                    .padding(
                        horizontal = MidnightCyanTokens.OverscanHorizontal,
                        vertical = MidnightCyanTokens.OverscanVertical
                    )
            ) {
                val currProg = currentEpg?.currentProgram
                val nextProg = currentEpg?.nextProgram
                val progress = currProg?.progressPercent ?: 0
                val timeMeta = buildString {
                    if (currProg != null) {
                        append(TvhUiRepository.formatTimeRange(currProg.startTimeMs, currProg.endTimeMs))
                    } else {
                        append("Live Broadcast")
                    }
                    if (nextProg != null) {
                        append(" • Next: ${nextProg.title}")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Channel picon + Show info + cyan progress track
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var osdLogoFailed by remember(currentChannel?.id, currentChannel?.icon) { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MidnightCyanTokens.CardBg),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!currentChannel?.icon.isNullOrBlank() && !osdLogoFailed) {
                                AsyncImage(
                                    model = currentChannel!!.icon,
                                    contentDescription = currentChannel.name,
                                    contentScale = ContentScale.Fit,
                                    onError = { osdLogoFailed = true },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp)
                                )
                            } else {
                                Text(
                                    text = currentChannel?.name ?: "TV",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(6.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (currentChannel != null) "${currentChannel.number} • ${currentChannel.name}" else "",
                                color = MidnightCyanTokens.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = currProg?.title ?: currentChannel?.name ?: "Live TV",
                                color = MidnightCyanTokens.TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = timeMeta,
                                color = MidnightCyanTokens.TextSecondary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(240.dp)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.22f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((progress.coerceIn(0, 100)) / 100f)
                                            .height(4.dp)
                                            .background(MidnightCyanTokens.CyanAccent)
                                    )
                                }
                                Text(
                                    text = "$progress%",
                                    color = MidnightCyanTokens.CyanAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    // Center-Right: Pill Controls
                    val pillDownModifier = Modifier.focusProperties {
                        if (adjacentChannels.isNotEmpty() && quickZapListState.firstVisibleItemIndex == 0) {
                            down = firstQuickZapFocusRequester
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvhPillButton(
                            label = if (isPaused) "Resume" else "Pause",
                            selected = isPaused,
                            onClick = onTogglePause,
                            modifier = pillDownModifier
                        )
                        TvhPillButton(
                            label = if (isRecording) "Recording ✓" else "Record",
                            selected = isRecording,
                            onClick = onToggleRecord,
                            modifier = pillDownModifier
                        )
                        TvhPillButton(
                            label = "Guide",
                            selected = false,
                            onClick = onGuideClick,
                            modifier = pillDownModifier.focusRequester(guideFocusRequester)
                        )
                        TvhPillButton(
                            label = "Audio & Subtitles",
                            selected = false,
                            onClick = onAudioSubtitlesClick,
                            modifier = pillDownModifier
                        )
                    }
                }

                if (adjacentChannels.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "ON NOW",
                            color = MidnightCyanTokens.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(end = 14.dp)
                        )
                        LazyRow(
                            state = quickZapListState,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            itemsIndexed(adjacentChannels, key = { _, ch -> ch.id }) { index, ch ->
                                val epg = adjacentEpg[ch.id]?.currentProgram
                                QuickZapCard(
                                    channel = ch,
                                    programTitle = epg?.title ?: ch.name,
                                    progressPercent = epg?.progressPercent ?: 0,
                                    onClick = { onQuickZapChannel(ch) },
                                    modifier = Modifier
                                        .then(
                                            if (index == 0) Modifier.focusRequester(firstQuickZapFocusRequester)
                                            else Modifier
                                        )
                                        .focusProperties { up = guideFocusRequester }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickZapCard(
    channel: TvhChannel,
    programTitle: String,
    progressPercent: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .width(210.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
            .focusCyanBorder(isFocused = isFocused, cornerRadius = 10.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        var zapLogoFailed by remember(channel.id, channel.icon) { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .width(42.dp)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            if (channel.icon.isNotBlank() && !zapLogoFailed) {
                AsyncImage(
                    model = channel.icon,
                    contentDescription = channel.name,
                    contentScale = ContentScale.Fit,
                    onError = { zapLogoFailed = true },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = channel.number.toString(),
                    color = MidnightCyanTokens.CyanAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = programTitle,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((progressPercent.coerceIn(0, 100)) / 100f)
                        .height(2.dp)
                        .background(MidnightCyanTokens.CyanAccent)
                )
            }
        }
    }
}

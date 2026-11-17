package com.nmaclean.tvheadend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.nmaclean.tvheadend.ui.theme.TvhPillButton
import kotlinx.coroutines.delay

/**
 * 10-foot Midnight-Cyan modal for EPG programme details & DVR scheduling (`Record Episode`, `Record Series`, `Tune Live`).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun ProgramDetailsModal(
    channel: TvhChannel,
    program: EpgRepository.ProgramInfo,
    isScheduled: Boolean,
    hasSeriesRule: Boolean,
    isLiveNow: Boolean,
    onTuneLive: () -> Unit,
    onToggleRecordEpisode: () -> Unit,
    onScheduleSeries: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(program.eventId) {
        delay(50L)
        try {
            primaryFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    val genre = remember(program.eventId, channel.id) {
        TvhUiRepository.classifyEpgGenre(program.title, program.summary, channel.name)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xD9060A12)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(740.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MidnightCyanTokens.BgGradientTop)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                var logoFailed by remember(channel.id, channel.icon) { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(MidnightCyanTokens.CardBgElevated, MidnightCyanTokens.CardBg)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (channel.icon.isNotBlank() && !logoFailed) {
                        AsyncImage(
                            model = channel.icon,
                            contentDescription = channel.name,
                            contentScale = ContentScale.Fit,
                            onError = { logoFailed = true },
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    } else {
                        Text(
                            text = "${channel.number} • ${channel.name}",
                            color = MidnightCyanTokens.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(20.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${channel.number} • ${channel.name}",
                            color = MidnightCyanTokens.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (genre != TvhUiRepository.EpgGenre.GENERAL) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(genre.accentHex).copy(alpha = 0.22f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = genre.label,
                                    color = Color(genre.accentHex),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (isScheduled) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0x33FF1744))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF1744))
                                )
                                Text(
                                    text = "SCHEDULED",
                                    color = Color(0xFFFF5252),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = program.title,
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = TvhUiRepository.formatTimeRange(program.startTimeMs, program.endTimeMs),
                        color = MidnightCyanTokens.CyanAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = program.summary?.takeIf { it.isNotBlank() }
                    ?: "No episode description available.",
                color = MidnightCyanTokens.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLiveNow) {
                    TvhPillButton(
                        label = "Tune Live",
                        selected = true,
                        onClick = onTuneLive,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    )
                }
                TvhPillButton(
                    label = if (isScheduled) "Scheduled ✓ (Cancel)" else "Record Episode",
                    selected = !isLiveNow && isScheduled,
                    onClick = onToggleRecordEpisode,
                    modifier = if (!isLiveNow) Modifier.focusRequester(primaryFocusRequester) else Modifier
                )
                TvhPillButton(
                    label = if (hasSeriesRule) "Series Active ✓" else "Record Series",
                    selected = hasSeriesRule,
                    onClick = onScheduleSeries
                )
                Spacer(modifier = Modifier.weight(1f))
                TvhPillButton(
                    label = "Close",
                    selected = false,
                    onClick = onDismiss
                )
            }
        }
    }
}

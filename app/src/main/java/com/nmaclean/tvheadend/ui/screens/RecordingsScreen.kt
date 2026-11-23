package com.nmaclean.tvheadend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nmaclean.tvheadend.sync.DvrRepository
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.model.TvhRecording
import com.nmaclean.tvheadend.model.TvhRecordingStatus
import com.nmaclean.tvheadend.model.TvhSeriesRule
import com.nmaclean.tvheadend.ui.TvhUiRepository
import com.nmaclean.tvheadend.ui.components.TvhMediaCard
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTopBarWithClock
import com.nmaclean.tvheadend.ui.theme.TvhPillButton
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DVR Management screen (`Recorded`, `Scheduled`, `Series Rules`) matching `recording.jpg`.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun RecordingsScreen(
    onRetry: () -> Unit = {},
    onPlayRecording: (TvhRecording) -> Unit,
    onCancelScheduled: (TvhRecording) -> Unit,
    onSeriesRuleClick: (TvhSeriesRule) -> Unit,
    modifier: Modifier = Modifier
) {
    val dvrState by DvrRepository.dvrState.collectAsState()
    val recordings = dvrState.recordings
    val seriesRules = dvrState.seriesRules
    val isLoading = dvrState.isLoading
    val errorMessage = dvrState.errorMessage
    val recordedItems = remember(recordings) {
        recordings.filter { it.status == TvhRecordingStatus.RECORDED || it.status == TvhRecordingStatus.RECORDING }
    }
    val scheduledItems = remember(recordings) {
        recordings.filter { it.status == TvhRecordingStatus.SCHEDULED }
    }
    val tabs = remember(recordedItems.size, scheduledItems.size, seriesRules.size) {
        listOf(
            if (recordedItems.isNotEmpty()) "Recorded (${recordedItems.size})" else "Recorded",
            if (scheduledItems.isNotEmpty()) "Scheduled (${scheduledItems.size})" else "Scheduled",
            if (seriesRules.isNotEmpty()) "Series Rules (${seriesRules.size})" else "Series Rules"
        )
    }
    var selectedTab by remember { mutableIntStateOf(0) }
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = MidnightCyanTokens.OverscanHorizontal,
                vertical = MidnightCyanTokens.OverscanVertical
            )
    ) {
        TvTopBarWithClock(
            pills = tabs,
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (isLoading && recordings.isEmpty() && seriesRules.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Loading DVR library from Tvheadend...",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 16.sp
                )
            }
            return@Column
        }

        if (errorMessage != null && recordings.isEmpty() && seriesRules.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage,
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                TvhPillButton(
                    label = "Retry",
                    selected = true,
                    onClick = onRetry
                )
            }
            return@Column
        }

        when (selectedTab) {
            0 -> {
                if (recordedItems.isEmpty()) {
                    EmptyDvrState("No recorded programmes yet.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                    ) {
                        items(
                            items = recordedItems,
                            key = { it.id },
                            contentType = { "recording_card" }
                        ) { rec ->
                            val dateStr = if (rec.startTimeMs > 0L) dateFormat.format(Date(rec.startTimeMs)) else ""
                            val watchedBadge = if (rec.watchedPercent > 0) "WATCHED ${rec.watchedPercent}%" else null
                            TvhMediaCard(
                                title = rec.title,
                                subtitle = "${rec.channelName} • $dateStr • ${rec.durationMinutes}m",
                                imageUrl = rec.imageUrl,
                                channelFallbackLabel = rec.channelName,
                                progressPercent = rec.watchedPercent,
                                showLiveBadge = rec.status == TvhRecordingStatus.RECORDING,
                                topRightBadge = watchedBadge,
                                onClick = { onPlayRecording(rec) }
                            )
                        }
                    }
                }
            }
            1 -> {
                if (scheduledItems.isEmpty()) {
                    EmptyDvrState("No scheduled timers. Select a future show in the Guide to schedule one.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                    ) {
                        items(
                            items = scheduledItems,
                            key = { it.id },
                            contentType = { "scheduled_timer_card" }
                        ) { rec ->
                            val dateStr = if (rec.startTimeMs > 0L) dateFormat.format(Date(rec.startTimeMs)) else ""
                            val timeStr = TvhUiRepository.formatTimeRange(rec.startTimeMs, rec.stopTimeMs)
                            val isActive = rec.status == TvhRecordingStatus.RECORDING
                            TvhMediaCard(
                                title = rec.title,
                                subtitle = "${rec.channelName} • $dateStr • $timeStr",
                                imageUrl = rec.imageUrl,
                                channelFallbackLabel = rec.channelName,
                                progressPercent = if (isActive) rec.watchedPercent else 0,
                                showLiveBadge = isActive,
                                topRightBadge = if (isActive) "RECORDING" else "SCHEDULED",
                                onClick = { onCancelScheduled(rec) }
                            )
                        }
                    }
                }
            }
            else -> {
                if (seriesRules.isEmpty()) {
                    EmptyDvrState("No AutoRec series rules configured.")
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRestorer()
                    ) {
                        items(seriesRules, key = { it.id }) { rule ->
                            SeriesRuleCard(
                                rule = rule,
                                onClick = { onSeriesRuleClick(rule) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyDvrState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = MidnightCyanTokens.TextSecondary,
            fontSize = 16.sp
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeriesRuleCard(
    rule: TvhSeriesRule,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
            .focusCyanBorder(isFocused = isFocused, cornerRadius = 12.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = rule.title,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (rule.enabled) "ACTIVE" else "PAUSED",
                color = if (rule.enabled) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = rule.channelName,
            color = MidnightCyanTokens.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "Press OK to toggle or delete rule",
            color = MidnightCyanTokens.TextMuted,
            fontSize = 11.sp
        )
    }
}

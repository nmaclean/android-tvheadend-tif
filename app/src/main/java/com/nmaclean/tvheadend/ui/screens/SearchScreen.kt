package com.nmaclean.tvheadend.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhRecording
import com.nmaclean.tvheadend.model.TvhRecordingStatus
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.ui.TvhUiRepository
import com.nmaclean.tvheadend.ui.components.TvhMediaCard
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder

/**
 * 10-foot Search screen filtering live channels and currently airing EPG titles.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    channels: List<TvhChannel>,
    currentEpg: Map<Long, EpgRepository.ChannelEpg>,
    recordings: List<TvhRecording> = emptyList(),
    onChannelClick: (TvhChannel) -> Unit,
    onRecordingClick: (TvhRecording) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }

    val matchingChannels = remember(query, channels, currentEpg) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            channels
        } else {
            channels.filter { ch ->
                val cur = currentEpg[ch.id]?.currentProgram
                val next = currentEpg[ch.id]?.nextProgram
                ch.name.lowercase().contains(q) ||
                    ch.number.toString().contains(q) ||
                    (cur?.title?.lowercase()?.contains(q) == true) ||
                    (cur?.summary?.lowercase()?.contains(q) == true) ||
                    (next?.title?.lowercase()?.contains(q) == true)
            }
        }
    }

    val matchingRecordings = remember(query, recordings) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            emptyList()
        } else {
            recordings.filter { rec ->
                (rec.status == TvhRecordingStatus.RECORDED || rec.status == TvhRecordingStatus.RECORDING) &&
                    (rec.title.lowercase().contains(q) ||
                        rec.subtitle.lowercase().contains(q) ||
                        rec.description.lowercase().contains(q) ||
                        rec.channelName.lowercase().contains(q))
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = MidnightCyanTokens.OverscanHorizontal,
                vertical = MidnightCyanTokens.OverscanVertical
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MidnightCyanTokens.CardBg)
                    .focusCyanBorder(isFocused = searchFocused, cornerRadius = 12.dp)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = if (searchFocused) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search channels, numbers, or live shows...",
                            color = MidnightCyanTokens.TextMuted,
                            fontSize = 15.sp
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = MidnightCyanTokens.TextPrimary,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(MidnightCyanTokens.CyanAccent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            TvClockHeader()
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (matchingChannels.isEmpty() && matchingRecordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No channels, shows, or recordings matching \"$query\"",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 16.sp
                )
            }
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
                items(matchingChannels, key = { "ch_${it.id}" }) { channel ->
                    val epg = currentEpg[channel.id]?.currentProgram
                    val showTitle = epg?.title?.takeIf { it.isNotBlank() } ?: channel.name
                    val timeRange = if (epg != null) {
                        TvhUiRepository.formatTimeRange(epg.startTimeMs, epg.endTimeMs)
                    } else {
                        "Live Stream"
                    }
                    TvhMediaCard(
                        title = showTitle,
                        subtitle = "${channel.name} • $timeRange",
                        imageUrl = channel.icon,
                        channelFallbackLabel = "${channel.number}  ${channel.name}",
                        progressPercent = epg?.progressPercent ?: 0,
                        showLiveBadge = true,
                        onClick = { onChannelClick(channel) }
                    )
                }
                items(matchingRecordings, key = { "rec_${it.id}" }) { rec ->
                    TvhMediaCard(
                        title = rec.title,
                        subtitle = "${rec.channelName} • ${rec.durationMinutes}m",
                        imageUrl = rec.imageUrl,
                        channelFallbackLabel = rec.channelName,
                        progressPercent = rec.watchedPercent,
                        showLiveBadge = rec.status == TvhRecordingStatus.RECORDING,
                        topRightBadge = if (rec.watchedPercent > 0) "WATCHED ${rec.watchedPercent}%" else "RECORDED",
                        onClick = { onRecordingClick(rec) }
                    )
                }
            }
        }
    }
}

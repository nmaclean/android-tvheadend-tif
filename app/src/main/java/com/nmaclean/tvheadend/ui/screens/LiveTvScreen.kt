package com.nmaclean.tvheadend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.ui.TvhUiRepository
import com.nmaclean.tvheadend.ui.components.TvhMediaCard
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTopBarWithClock

/**
 * 3-column Live TV browser page matching the Midnight-Cyan reference layout (`tvheadned.png`).
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun LiveTvScreen(
    channels: List<TvhChannel>,
    currentEpg: Map<Long, EpgRepository.ChannelEpg>,
    isLoading: Boolean,
    onChannelClick: (TvhChannel) -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = TvhUiRepository.CHANNEL_CATEGORIES
    var selectedCategoryIdx by remember { mutableIntStateOf(0) }
    val selectedCategory = categories[selectedCategoryIdx]

    val filteredChannels = remember(channels, selectedCategory, currentEpg) {
        TvhUiRepository.filterChannelsByCategory(channels, selectedCategory, currentEpg)
    }

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
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        when {
            isLoading && channels.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Loading live channels...",
                        color = MidnightCyanTokens.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
            filteredChannels.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No channels in $selectedCategory. Check Settings or run a channel scan.",
                        color = MidnightCyanTokens.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer()
                ) {
                    items(filteredChannels, key = { it.id }) { channel ->
                        val epg = currentEpg[channel.id]?.currentProgram
                        val showTitle = epg?.title?.takeIf { it.isNotBlank() } ?: channel.name
                        val timeRange = if (epg != null) {
                            TvhUiRepository.formatTimeRange(epg.startTimeMs, epg.endTimeMs)
                        } else {
                            "Live Stream"
                        }
                        val subtitle = "${channel.name} • $timeRange"
                        val progress = epg?.progressPercent ?: 0

                        TvhMediaCard(
                            title = showTitle,
                            subtitle = subtitle,
                            imageUrl = channel.icon,
                            channelFallbackLabel = "${channel.number}  ${channel.name}",
                            progressPercent = progress,
                            showLiveBadge = true,
                            onClick = { onChannelClick(channel) }
                        )
                    }
                }
            }
        }
    }
}

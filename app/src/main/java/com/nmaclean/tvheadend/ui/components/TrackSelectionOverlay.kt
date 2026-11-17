package com.nmaclean.tvheadend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvhPillButton
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import kotlinx.coroutines.delay

data class TrackOption(
    val id: String?,
    val label: String,
    val detail: String = ""
)

/**
 * Midnight-Cyan 10-foot overlay for selecting Audio and Subtitle tracks without AppCompat dialog conflicts.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun TrackSelectionOverlay(
    audioTracks: List<TrackOption>,
    selectedAudioId: String?,
    subtitleTracks: List<TrackOption>,
    selectedSubtitleId: String?,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50L)
        try {
            firstItemFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xD9060A12)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MidnightCyanTokens.BgGradientTop)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Audio & Subtitles",
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Select audio language or toggle DVB/closed captions",
                        color = MidnightCyanTokens.TextSecondary,
                        fontSize = 13.sp
                    )
                }
                TvhPillButton(
                    label = "Close",
                    selected = false,
                    onClick = onDismiss
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Audio Column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AUDIO TRACK",
                        color = MidnightCyanTokens.CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (audioTracks.isEmpty()) {
                        Text(
                            text = "No audio tracks reported",
                            color = MidnightCyanTokens.TextMuted,
                            fontSize = 14.sp
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .heightIn(max = 320.dp)
                                .focusRestorer()
                        ) {
                            itemsIndexed(audioTracks) { index, track ->
                                val isSelected = track.id != null && track.id == selectedAudioId
                                val mod = if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier
                                TrackOptionRow(
                                    option = track,
                                    selected = isSelected,
                                    onClick = {
                                        track.id?.let(onSelectAudio)
                                        onDismiss()
                                    },
                                    modifier = mod
                                )
                            }
                        }
                    }
                }

                // Subtitles Column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SUBTITLES",
                        color = MidnightCyanTokens.CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .focusRestorer()
                    ) {
                        itemsIndexed(subtitleTracks) { index, track ->
                            val isSelected = track.id == selectedSubtitleId
                            val mod = if (audioTracks.isEmpty() && index == 0) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                            TrackOptionRow(
                                option = track,
                                selected = isSelected,
                                onClick = {
                                    onSelectSubtitle(track.id)
                                    onDismiss()
                                },
                                modifier = mod
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
private fun TrackOptionRow(
    option: TrackOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
            .focusCyanBorder(isFocused = isFocused, cornerRadius = 10.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 15.sp,
                fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (option.detail.isNotBlank()) {
                Text(
                    text = option.detail,
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (selected) MidnightCyanTokens.CyanAccent else Color.Transparent)
        )
    }
}

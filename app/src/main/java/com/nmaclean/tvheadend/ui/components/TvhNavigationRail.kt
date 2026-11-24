package com.nmaclean.tvheadend.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder

enum class TvhNavDestination(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Outlined.Search),
    LIVE_TV("Live TV", Icons.Outlined.LiveTv),
    GUIDE("Guide", Icons.Outlined.ViewTimeline),
    RECORDINGS("Recordings", Icons.Outlined.RadioButtonChecked),
    SETTINGS("Settings", Icons.Outlined.Settings)
}

/**
 * 10-foot pinned navigation rail: slim 64dp icon-only while browsing content,
 * expanding smoothly to 140dp with full labels and glowing cyan pill indicators on D-pad focus.
 */
@Composable
fun TvhNavigationRail(
    selectedDestination: TvhNavDestination,
    onSelectDestination: (TvhNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    var railHasFocus by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (railHasFocus) MidnightCyanTokens.RailExpandedWidth else MidnightCyanTokens.RailCollapsedWidth,
        label = "railWidth"
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MidnightCyanTokens.RailBg)
            .onFocusChanged { railHasFocus = it.hasFocus }
            .padding(vertical = MidnightCyanTokens.OverscanVertical, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            listOf(
                TvhNavDestination.SEARCH,
                TvhNavDestination.LIVE_TV,
                TvhNavDestination.GUIDE,
                TvhNavDestination.RECORDINGS
            ).forEach { destination ->
                RailNavItem(
                    destination = destination,
                    selected = selectedDestination == destination,
                    expanded = railHasFocus,
                    onClick = { onSelectDestination(destination) }
                )
            }
        }

        RailNavItem(
            destination = TvhNavDestination.SETTINGS,
            selected = selectedDestination == TvhNavDestination.SETTINGS,
            expanded = railHasFocus,
            onClick = { onSelectDestination(TvhNavDestination.SETTINGS) }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RailNavItem(
    destination: TvhNavDestination,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    var itemFocused by remember { mutableStateOf(false) }
    val pillColor = when {
        selected -> Color(0xFF0E2A40)
        itemFocused -> MidnightCyanTokens.CardBgElevated
        else -> Color.Transparent
    }
    val contentColor = when {
        selected || itemFocused -> MidnightCyanTokens.CyanAccent
        else -> MidnightCyanTokens.TextSecondary
    }

    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .background(pillColor)
            .focusCyanBorder(isFocused = itemFocused, cornerRadius = 12.dp)
            .onFocusChanged { itemFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = destination.label,
            tint = contentColor,
            modifier = Modifier.size(22.dp)
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row {
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = destination.label,
                    color = if (selected || itemFocused) MidnightCyanTokens.TextPrimary else MidnightCyanTokens.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

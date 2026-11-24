package com.nmaclean.tvheadend.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Midnight-Navy & Electric-Cyan design token system for the 10-foot Android TV interface.
 */
object MidnightCyanTokens {
    val BgGradientTop = Color(0xFF0B1221)
    val BgGradientBottom = Color(0xFF060A12)
    val CardBg = Color(0xFF162238)
    val CardBgElevated = Color(0xFF1D2D49)
    val RailBg = Color(0xFF080E1A)
    val CyanAccent = Color(0xFF00E5FF)
    val CyanGlow = Color(0x5500E5FF)
    val LiveBadgeRed = Color(0xFFE50914)
    val WatchedBadgeBg = Color(0xCC060A12)
    val TextPrimary = Color(0xFFF3F6FC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
    val PillUnselectedBg = Color(0xFF162238)
    val PillSelectedBg = Color(0xFFFFFFFF)
    val PillSelectedText = Color(0xFF060A12)
    val DividerSubtle = Color(0xFF1E293B)

    val OverscanHorizontal: Dp = 48.dp
    val OverscanVertical: Dp = 24.dp
    val RailCollapsedWidth: Dp = 64.dp
    val RailExpandedWidth: Dp = 164.dp
    val FocusBorderWidth: Dp = 2.dp
    val CardCornerRadius: Dp = 12.dp

    val BackgroundBrush: Brush = Brush.verticalGradient(
        colors = listOf(BgGradientTop, BgGradientBottom)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
private val MidnightCyanColorScheme = darkColorScheme(
    primary = MidnightCyanTokens.CyanAccent,
    onPrimary = MidnightCyanTokens.BgGradientBottom,
    secondary = MidnightCyanTokens.CardBgElevated,
    onSecondary = MidnightCyanTokens.TextPrimary,
    background = MidnightCyanTokens.BgGradientBottom,
    onBackground = MidnightCyanTokens.TextPrimary,
    surface = MidnightCyanTokens.CardBg,
    onSurface = MidnightCyanTokens.TextPrimary,
    surfaceVariant = MidnightCyanTokens.CardBgElevated,
    onSurfaceVariant = MidnightCyanTokens.TextSecondary
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MidnightCyanColorScheme,
        content = content
    )
}

/**
 * Applies a 2dp electric-cyan border and ambient cyan glow when focused on TV D-pad navigation.
 */
fun Modifier.focusCyanBorder(
    isFocused: Boolean,
    cornerRadius: Dp = MidnightCyanTokens.CardCornerRadius
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return if (isFocused) {
        this.border(
            BorderStroke(MidnightCyanTokens.FocusBorderWidth, MidnightCyanTokens.CyanAccent),
            shape = shape
        )
    } else {
        this
    }
}

/**
 * Reusable top-right clock header rendering current 24-hour time ("18:21") above the full date ("Tuesday, 17 November").
 */
@Composable
fun TvClockHeader(modifier: Modifier = Modifier) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(15_000L)
        }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    val currentDate = remember(nowMs) { Date(nowMs) }

    Column(
        modifier = modifier.widthIn(min = 164.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(
            text = timeFormat.format(currentDate),
            color = MidnightCyanTokens.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = dateFormat.format(currentDate),
            color = MidnightCyanTokens.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Interactive 10-foot pill button used for channel categories, DVR tabs, and OSD controls.
 */
@Composable
fun TvhPillButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val bgColor = when {
        selected -> MidnightCyanTokens.PillSelectedBg
        isFocused -> MidnightCyanTokens.CardBgElevated
        else -> MidnightCyanTokens.PillUnselectedBg
    }
    val textColor = when {
        selected -> MidnightCyanTokens.PillSelectedText
        else -> MidnightCyanTokens.TextPrimary
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .focusCyanBorder(isFocused = isFocused, cornerRadius = 50.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Standard top header row combining horizontal pill tabs on the left and [TvClockHeader] on the right.
 */
@Composable
fun TvTopBarWithClock(
    pills: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    firstPillFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(end = 16.dp),
            modifier = Modifier
                .weight(1f)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    if (listState.canScrollForward && size.width > 0f) {
                        val fadePx = 32.dp.toPx().coerceAtMost(size.width)
                        val startFade = ((size.width - fadePx) / size.width).coerceIn(0f, 1f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0f to Color.Black,
                                    startFade to Color.Black,
                                    1f to Color.Transparent
                                )
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
        ) {
            itemsIndexed(pills) { index, label ->
                var pillModifier: Modifier = Modifier
                if (index == 0 && firstPillFocusRequester != null) {
                    pillModifier = pillModifier.focusRequester(firstPillFocusRequester)
                }
                if (downFocusRequester != null) {
                    pillModifier = pillModifier.focusProperties { down = downFocusRequester }
                }
                TvhPillButton(
                    label = label,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = pillModifier
                )
            }
        }
        Spacer(modifier = Modifier.width(24.dp))
        TvClockHeader()
    }
}

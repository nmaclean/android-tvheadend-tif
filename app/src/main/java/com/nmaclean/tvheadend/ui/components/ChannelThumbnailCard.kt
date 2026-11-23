package com.nmaclean.tvheadend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder

/**
 * 16:9 media card used by both the Live TV grid (`LIVE` badge + cyan progress) and DVR screen (`WATCHED %` badge + cyan progress).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvhMediaCard(
    title: String,
    subtitle: String,
    imageUrl: String,
    channelFallbackLabel: String,
    progressPercent: Int,
    showLiveBadge: Boolean = false,
    topRightBadge: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
                .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
                .focusCyanBorder(isFocused = isFocused)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(onClick = onClick)
        ) {
            val context = LocalContext.current
            var imgFailed by remember(imageUrl) { mutableStateOf(false) }
            val imageRequest = remember(imageUrl, context) {
                if (imageUrl.isNotBlank()) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .crossfade(false)
                        .build()
                } else null
            }
            if (imageRequest != null && !imgFailed) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    onError = { imgFailed = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF162238)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channelFallbackLabel,
                        color = MidnightCyanTokens.TextPrimary.copy(alpha = 0.85f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Bottom subtle dark gradient for progress bar legibility
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(32.dp)
                    .background(MidnightCyanTokens.CardBottomGradient)
            )

            if (showLiveBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MidnightCyanTokens.LiveBadgeRed)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                }
            }

            if (!topRightBadge.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MidnightCyanTokens.WatchedBadgeBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = topRightBadge,
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp
                    )
                }
            }

            if (progressPercent > 0) {
                val fraction = (progressPercent.coerceIn(0, 100)) / 100f
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(MidnightCyanTokens.CyanAccent)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = title,
            color = MidnightCyanTokens.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            color = MidnightCyanTokens.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

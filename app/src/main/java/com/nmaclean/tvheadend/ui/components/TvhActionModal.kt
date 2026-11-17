package com.nmaclean.tvheadend.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
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
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvhPillButton
import kotlinx.coroutines.delay

data class TvhModalAction(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 10-foot Midnight-Navy & Electric-Cyan modal dialog replacing unstyled system AlertDialogs
 * for DVR recording actions, timer cancellation, series rules, and resume prompts.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun TvhActionModal(
    title: String,
    subtitle: String,
    body: String = "",
    badgeText: String? = null,
    imageUrl: String = "",
    fallbackLabel: String = "",
    actions: List<TvhModalAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primaryFocusRequester = remember { FocusRequester() }
    BackHandler(onBack = onDismiss)

    LaunchedEffect(title, subtitle) {
        delay(50L)
        try {
            primaryFocusRequester.requestFocus()
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
                .width(700.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MidnightCyanTokens.BgGradientTop)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (imageUrl.isNotBlank() || fallbackLabel.isNotBlank()) {
                    var logoFailed by remember(imageUrl) { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .width(148.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(MidnightCyanTokens.CardBgElevated, MidnightCyanTokens.CardBg)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (imageUrl.isNotBlank() && !logoFailed) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = title,
                                contentScale = ContentScale.Fit,
                                onError = { logoFailed = true },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            )
                        } else {
                            Text(
                                text = fallbackLabel.ifBlank { title },
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
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (!badgeText.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MidnightCyanTokens.CyanAccent.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = badgeText,
                                color = MidnightCyanTokens.CyanAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Text(
                        text = title,
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = subtitle,
                            color = MidnightCyanTokens.CyanAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (body.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = body,
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRestorer(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEachIndexed { index, action ->
                    TvhPillButton(
                        label = action.label,
                        selected = action.selected,
                        onClick = action.onClick,
                        modifier = if (index == 0) Modifier.focusRequester(primaryFocusRequester) else Modifier
                    )
                }
            }
        }
    }
}

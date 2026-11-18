package com.nmaclean.tvheadend.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.TvTheme
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import kotlin.concurrent.thread

/**
 * Midnight-Cyan Compose General Settings screen (diagnostic logs & EPG sync).
 * Server connection settings are managed via Android TV TIF Input Setup.
 */
@OptIn(UnstableApi::class)
class GeneralSettingsActivity : FragmentActivity() {

    private lateinit var settings: TvhSettings
    private val refreshTick = mutableIntStateOf(0)
    private val isSyncing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = TvhSettings(this)

        setContent {
            TvTheme {
                val tick = refreshTick.intValue
                val syncing = isSyncing.value
                GeneralSettingsScreen(
                    tick = tick,
                    isSyncing = syncing,
                    settings = settings,
                    onToggleDebug = {
                        val next = !settings.isStreamingDebugEnabled
                        settings.isStreamingDebugEnabled = next
                        refreshTick.intValue++
                    },
                    onSyncNow = {
                        if (!isSyncing.value) {
                            isSyncing.value = true
                            thread {
                                val result = TvhSyncManager.performSync(this)
                                runOnUiThread {
                                    isSyncing.value = false
                                    if (!result.success) {
                                        Toast.makeText(
                                            this,
                                            "Sync failed: ${result.error}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@kotlin.OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun GeneralSettingsScreen(
    tick: Int,
    isSyncing: Boolean,
    settings: TvhSettings,
    onToggleDebug: () -> Unit,
    onSyncNow: () -> Unit
) {
    val debugOn = remember(tick) { settings.isStreamingDebugEnabled }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightCyanTokens.BackgroundBrush)
            .padding(
                horizontal = MidnightCyanTokens.OverscanHorizontal,
                vertical = MidnightCyanTokens.OverscanVertical
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "General Settings",
                    color = MidnightCyanTokens.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Diagnostics & EPG sync (server configured via TIF Input)",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 14.sp
                )
            }
            TvClockHeader()
        }

        Spacer(modifier = Modifier.height(22.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer()
        ) {
            item { SectionLabel("PLAYBACK & DIAGNOSTICS") }
            item {
                SettingsRowItem(
                    title = "Streaming Debug Mode",
                    summary = if (debugOn) "Verbose Logcat logging enabled for HTSP, demuxing & ExoPlayer" else "Standard logging",
                    badgeText = if (debugOn) "ON" else "OFF",
                    badgeHighlight = debugOn,
                    onClick = onToggleDebug
                )
            }
            item {
                SettingsRowItem(
                    title = "Sync Channels & EPG Now",
                    summary = if (isSyncing) "Sync in progress..." else "Pull latest channels and EPG schedule from Tvheadend",
                    onClick = onSyncNow
                )
            }
        }
    }
}

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MidnightCyanTokens.CyanAccent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsRowItem(
    title: String,
    summary: String,
    badgeText: String? = null,
    badgeHighlight: Boolean = false,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
            .focusCyanBorder(isFocused = isFocused, cornerRadius = 12.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = summary,
                color = MidnightCyanTokens.TextSecondary,
                fontSize = 13.sp
            )
        }

        if (badgeText != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (badgeHighlight) MidnightCyanTokens.CyanAccent
                        else MidnightCyanTokens.BgGradientBottom
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Text(
                    text = badgeText,
                    color = if (badgeHighlight) MidnightCyanTokens.PillSelectedText else MidnightCyanTokens.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

package com.nmaclean.tvheadend.ui

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
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
import com.nmaclean.tvheadend.BuildConfig
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.TvTheme
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import com.nmaclean.tvheadend.updater.UpdatePromptDialog
import kotlin.concurrent.thread

/**
 * Midnight-Cyan Compose General Settings screen (server credentials, diagnostic logs, EPG sync & updates).
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
                    onEditText = { title, currentValue, isNumber, isPassword, onSave ->
                        promptTextSetting(title, currentValue, isNumber, isPassword) { newVal ->
                            onSave(newVal)
                            refreshTick.intValue++
                        }
                    },
                    onToggleDebug = {
                        val next = !settings.isStreamingDebugEnabled
                        settings.isStreamingDebugEnabled = next
                        refreshTick.intValue++
                        Toast.makeText(
                            this,
                            "Streaming Debug Mode: ${if (next) "ON" else "OFF"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onSyncNow = {
                        if (!isSyncing.value) {
                            isSyncing.value = true
                            Toast.makeText(this, "Syncing channels & EPG...", Toast.LENGTH_SHORT).show()
                            thread {
                                val result = TvhSyncManager.performSync(this)
                                runOnUiThread {
                                    isSyncing.value = false
                                    Toast.makeText(
                                        this,
                                        if (result.success) "Sync complete! ${result.channelCount} channels imported."
                                        else "Sync failed: ${result.error}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    },
                    onCheckUpdates = {
                        UpdatePromptDialog(this).show()
                    }
                )
            }
        }
    }

    private fun promptTextSetting(
        title: String,
        currentValue: String,
        isNumber: Boolean,
        isPassword: Boolean,
        onSave: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            setText(currentValue)
            setSelection(text.length)
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            inputType = when {
                isNumber -> InputType.TYPE_CLASS_NUMBER
                isPassword -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else -> InputType.TYPE_CLASS_TEXT
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                onSave(input.text.toString().trim())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

@kotlin.OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun GeneralSettingsScreen(
    tick: Int,
    isSyncing: Boolean,
    settings: TvhSettings,
    onEditText: (title: String, current: String, isNumber: Boolean, isPassword: Boolean, (String) -> Unit) -> Unit,
    onToggleDebug: () -> Unit,
    onSyncNow: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    val host = remember(tick) { settings.host.ifBlank { "Not configured" } }
    val htspPort = remember(tick) { settings.htspPort }
    val httpPort = remember(tick) { settings.httpPort }
    val username = remember(tick) { settings.username.ifBlank { "None" } }
    val passwordMask = remember(tick) { if (settings.password.isNotEmpty()) "••••••••" else "Not set" }
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
                    text = "Server connection, encrypted credentials & diagnostics",
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
            item { SectionLabel("TVHEADEND SERVER") }
            item {
                SettingsRowItem(
                    title = "Server Host / IP",
                    summary = host,
                    onClick = {
                        onEditText("Server Host / IP", settings.host, false, false) {
                            settings.host = it
                        }
                    }
                )
            }
            item {
                SettingsRowItem(
                    title = "HTSP Port",
                    summary = htspPort.toString(),
                    onClick = {
                        onEditText("HTSP Port", htspPort.toString(), true, false) { v ->
                            v.toIntOrNull()?.let { settings.htspPort = it }
                        }
                    }
                )
            }
            item {
                SettingsRowItem(
                    title = "HTTP Port",
                    summary = httpPort.toString(),
                    onClick = {
                        onEditText("HTTP Port", httpPort.toString(), true, false) { v ->
                            v.toIntOrNull()?.let { settings.httpPort = it }
                        }
                    }
                )
            }
            item {
                SettingsRowItem(
                    title = "Username",
                    summary = username,
                    onClick = {
                        onEditText("Username", settings.username, false, false) {
                            settings.username = it
                        }
                    }
                )
            }
            item {
                SettingsRowItem(
                    title = "Password",
                    summary = passwordMask,
                    onClick = {
                        onEditText("Password", settings.password, false, true) {
                            settings.password = it
                        }
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)); SectionLabel("PLAYBACK & DIAGNOSTICS") }
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

            item { Spacer(modifier = Modifier.height(8.dp)); SectionLabel("APP & UPDATES") }
            item {
                SettingsRowItem(
                    title = "Check for Updates",
                    summary = "Current version: v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME})",
                    onClick = onCheckUpdates
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
            .focusable()
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

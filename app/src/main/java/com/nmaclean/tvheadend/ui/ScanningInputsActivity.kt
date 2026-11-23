package com.nmaclean.tvheadend.ui

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.nmaclean.tvheadend.SetupActivity
import kotlinx.coroutines.launch
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.TvTheme
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder

/**
 * Midnight-Cyan Compose screen for configuring device TV inputs, running tuner scans, and managing stream sources.
 */
@OptIn(UnstableApi::class)
class ScanningInputsActivity : FragmentActivity() {

    private data class InputItem(
        val inputInfo: TvInputInfo,
        val label: String,
        val channelCount: Int
    )

    private val inputItemsState = mutableStateOf<List<InputItem>>(emptyList())
    private lateinit var tifChannelObserver: com.nmaclean.tvheadend.sync.TifChannelObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tifChannelObserver = com.nmaclean.tvheadend.sync.TifChannelObserver(contentResolver, "ScanningInputsActivity") {
            loadInputs()
        }
        tifChannelObserver.register()

        setContent {
            TvTheme {
                ScanningInputsScreen(
                    items = inputItemsState.value,
                    onInputClick = { item ->
                        val setupIntent = item.inputInfo.createSetupIntent()
                        if (setupIntent != null) {
                            startActivity(setupIntent)
                        } else if (item.inputInfo.id.contains("IptvTvInputService")) {
                            startActivity(Intent(this, com.nmaclean.tvheadend.ui.settings.IptvSetupWizardActivity::class.java))
                        } else {
                            val intent = Intent(this, ChannelGridActivity::class.java).apply {
                                putExtra("input_id", item.inputInfo.id)
                            }
                            startActivity(intent)
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadInputs()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tifChannelObserver.isInitialized) {
            tifChannelObserver.unregister()
        }
    }

    private fun loadInputs() {
        inputItemsState.value = TvhUiRepository.loadTvInputs(this).map {
            InputItem(it.inputInfo, it.label, it.channelCount)
        }
    }

    @kotlin.OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
    @Composable
    private fun ScanningInputsScreen(
        items: List<InputItem>,
        onInputClick: (InputItem) -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        var showResetConfigDialog by remember { mutableStateOf(false) }

        if (showResetConfigDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfigDialog = false },
                title = { Text("Clear All Channels, EPG & Config?", color = MidnightCyanTokens.TextPrimary) },
                text = { Text("This will delete all registered channels, EPG programs, and reset saved server credentials, preferences, and allowlists cleanly.", color = MidnightCyanTokens.TextSecondary) },
                confirmButton = {
                    Button(
                        onClick = {
                            showResetConfigDialog = false
                            scope.launch {
                                val scanManager = com.nmaclean.tvheadend.iptv.scan.SourceScanManager(context)
                                val res = scanManager.resetAllDataAndSettings()
                                android.widget.Toast.makeText(
                                    context,
                                    "Full reset complete: Deleted ${res.clearedChannels} channels and reset saved configuration.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                loadInputs()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679))
                    ) {
                        Text("Confirm Full Reset", color = Color.White)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.OutlinedButton(onClick = { showResetConfigDialog = false }) {
                        Text("Cancel", color = MidnightCyanTokens.TextPrimary)
                    }
                },
                containerColor = MidnightCyanTokens.CardBgElevated
            )
        }

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
                        text = "Scanning & Inputs",
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "TV inputs, stream sources & TIF channel setup",
                        color = MidnightCyanTokens.TextSecondary,
                        fontSize = 14.sp
                    )
                }

                TvClockHeader()
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No TV inputs found on this device.",
                        color = MidnightCyanTokens.TextSecondary,
                        fontSize = 16.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRestorer()
                ) {
                    item(key = "reset_all_config_card") {
                        ResetAllConfigCard(onClick = { showResetConfigDialog = true })
                    }
                    items(items, key = { it.inputInfo.id }) { item ->
                        InputSourceCard(item = item, onClick = { onInputClick(item) })
                    }
                }
            }
        }
    }

    @kotlin.OptIn(ExperimentalTvMaterial3Api::class)
    @Composable
    private fun ResetAllConfigCard(
        onClick: () -> Unit
    ) {
        var isFocused by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
                .background(if (isFocused) Color(0xFF4A1A24) else Color(0xFF2E1218))
                .focusCyanBorder(isFocused = isFocused)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "Clear All Channels, EPG & Config",
                    tint = Color(0xFFFF5252),
                    modifier = Modifier.size(28.dp)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFCF6679).copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Reset All",
                        color = Color(0xFFFF5252),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column {
                Text(
                    text = "Clear All Channels, EPG & Config",
                    color = MidnightCyanTokens.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Purge channels, guide & reset settings",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }

    @kotlin.OptIn(ExperimentalTvMaterial3Api::class)
    @Composable
    private fun InputSourceCard(
        item: InputItem,
        onClick: () -> Unit
    ) {
        var isFocused by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
                .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
                .focusCyanBorder(isFocused = isFocused)
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(onClick = onClick)
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.LiveTv,
                    contentDescription = item.label,
                    tint = if (isFocused) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (item.channelCount > 0) MidnightCyanTokens.CyanAccent.copy(alpha = 0.16f)
                            else MidnightCyanTokens.BgGradientBottom
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (item.channelCount > 0) "${item.channelCount} channels" else "Setup",
                        color = if (item.channelCount > 0) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column {
                Text(
                    text = item.label,
                    color = MidnightCyanTokens.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Press OK to configure & scan",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

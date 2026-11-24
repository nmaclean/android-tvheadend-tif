package com.nmaclean.tvheadend.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTheme
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder

@Composable
fun M3uCurationScreen(
    viewModel: M3uCurationViewModel = viewModel(),
    onClose: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val serverButtonFocusRequester = remember { FocusRequester() }
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear TV Input Channels & EPG?", color = MidnightCyanTokens.TextPrimary) },
            text = { Text("This will delete registered TV Input Framework (TIF) channels and EPG programs for IPTV. Your saved M3U allowlist JSON and preferences will be preserved, allowing you to re-scan at any time.", color = MidnightCyanTokens.TextSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearChannels()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679))
                ) {
                    Text("Confirm Clear", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = MidnightCyanTokens.TextPrimary)
                }
            },
            containerColor = MidnightCyanTokens.CardBgElevated
        )
    }

    LaunchedEffect(Unit) {
        serverButtonFocusRequester.requestFocus()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.toggleServer(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.toggleServer(false)
        }
    }

    TvTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MidnightCyanTokens.BackgroundBrush)
                .padding(horizontal = MidnightCyanTokens.OverscanHorizontal, vertical = MidnightCyanTokens.OverscanVertical),
            contentAlignment = Alignment.TopStart
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "IPTV LAN Curation & Channel Scan",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MidnightCyanTokens.CyanAccent
                )

                if (uiState.lanIp == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFCF6679)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Warning: No active LAN IP address detected. Ensure Wi-Fi or Ethernet is connected.",
                            color = Color.White,
                            modifier = Modifier.padding(20.dp),
                            fontSize = 16.sp
                        )
                    }
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MidnightCyanTokens.CardBg),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = if (uiState.isServerRunning) "LAN Curation Server is RUNNING" else "LAN Curation Server is STOPPED",
                                fontWeight = FontWeight.SemiBold,
                                color = if (uiState.isServerRunning) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary,
                                fontSize = 18.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            if (uiState.isServerRunning && uiState.lanIp != null) {
                                Text(
                                    text = "Open in browser on any LAN device:\nhttp://${uiState.lanIp}:${uiState.port}",
                                    color = MidnightCyanTokens.TextPrimary,
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                            Text(
                                text = "Saved Allowlist Channels: ${uiState.allowlistCount}",
                                color = MidnightCyanTokens.TextSecondary,
                                fontSize = 15.sp
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    var isServerButtonFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { viewModel.toggleServer(!uiState.isServerRunning) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isServerRunning) Color(0xFFCF6679) else MidnightCyanTokens.CardBgElevated
                        ),
                        modifier = Modifier
                            .focusRequester(serverButtonFocusRequester)
                            .onFocusChanged { isServerButtonFocused = it.isFocused }
                            .focusCyanBorder(isServerButtonFocused)
                    ) {
                        Text(
                            text = if (uiState.isServerRunning) "Stop Curation Server" else "Start Curation Server",
                            color = MidnightCyanTokens.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    var isScanButtonFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { viewModel.scanIptvChannels() },
                        enabled = !uiState.isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CardBgElevated),
                        modifier = Modifier
                            .onFocusChanged { isScanButtonFocused = it.isFocused }
                            .focusCyanBorder(isScanButtonFocused)
                    ) {
                        Text(
                            text = if (uiState.isSyncing) "Scanning..." else "Scan IPTV Channels",
                            color = MidnightCyanTokens.TextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    var isClearButtonFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { showClearDialog = true },
                        enabled = !uiState.isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679)),
                        modifier = Modifier
                            .onFocusChanged { isClearButtonFocused = it.isFocused }
                            .focusCyanBorder(isClearButtonFocused)
                    ) {
                        Text(
                            text = "Clear TV Input Channels",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    var isDoneButtonFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = { onClose() },
                        colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CyanAccent),
                        modifier = Modifier
                            .onFocusChanged { isDoneButtonFocused = it.isFocused }
                            .focusCyanBorder(isDoneButtonFocused)
                    ) {
                        Text(
                            text = "Done & Return",
                            color = Color(0xFF0B0E14),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (!uiState.syncStatusMessage.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.syncStatusMessage ?: "",
                        color = MidnightCyanTokens.CyanAccent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

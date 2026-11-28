package com.nmaclean.tvheadend.ui.settings

import android.content.Intent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.ui.TvPlayerActivity
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvTheme
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder

enum class IptvWizardStep(val title: String) {
    CHOOSE_SOURCE("1. Select Source"),
    CONFIGURE_SOURCE("2. Configure M3U"),
    SCAN_CHANNELS("3. Scan Channels"),
    EPG_CONFIG("4. EPG Guide"),
    COMPLETE("5. Complete")
}

enum class M3uSourceMode {
    LAN_SERVER,
    DIRECT_URL
}

@OptIn(UnstableApi::class)
@Composable
fun IptvSetupWizardScreen(
    viewModel: M3uCurationViewModel = viewModel(),
    onFinish: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var currentStep by remember { mutableStateOf(IptvWizardStep.CHOOSE_SOURCE) }
    var sourceMode by remember { mutableStateOf(M3uSourceMode.LAN_SERVER) }
    var directUrlText by remember { mutableStateOf("") }
    var epgUrlText by remember(uiState.suggestedEpgUrl) { mutableStateOf(uiState.suggestedEpgUrl ?: "") }
    var showClearDialog by remember { mutableStateOf(false) }

    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(currentStep) {
        try {
            primaryFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear IPTV Channels?", color = MidnightCyanTokens.TextPrimary) },
            text = { Text("This will delete registered IPTV TIF channels and EPG programs. Your saved allowlist will remain.", color = MidnightCyanTokens.TextSecondary) },
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

    TvTheme {

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.setIptvTifEnabled(!uiState.isIptvTifEnabled) }
            ) {
                Text(if (uiState.isIptvTifEnabled) "Disable IPTV TIF" else "Enable IPTV TIF")
            }
            OutlinedButton(
                onClick = {
                    epgUrlText = ""
                    viewModel.clearIptvEpg()
                }
            ) {
                Text("Clear EPG")
            }
            Button(
                onClick = { viewModel.clearIptvList() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679))
            ) {
                Text("Clear List", color = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MidnightCyanTokens.BackgroundBrush)
                .padding(
                    horizontal = MidnightCyanTokens.OverscanHorizontal,
                    vertical = MidnightCyanTokens.OverscanVertical
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header & Stepper Indicator
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "IPTV Stream Setup Wizard",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = MidnightCyanTokens.CyanAccent
                        )
                        Text(
                            text = "Step ${currentStep.ordinal + 1} of 5",
                            color = MidnightCyanTokens.TextSecondary,
                            fontSize = 14.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Step Pills Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IptvWizardStep.values().forEach { step ->
                            val isActive = step == currentStep
                            val isPassed = step.ordinal < currentStep.ordinal
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        when {
                                            isActive -> MidnightCyanTokens.CyanAccent
                                            isPassed -> MidnightCyanTokens.CardBgElevated
                                            else -> MidnightCyanTokens.CardBg
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = step.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) Color(0xFF0B0E14) else MidnightCyanTokens.TextPrimary
                                )
                            }
                        }
                    }
                }

                // Step Content Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (currentStep) {
                        IptvWizardStep.CHOOSE_SOURCE -> StepChooseSource(
                            sourceMode = sourceMode,
                            onSelectMode = { mode ->
                                sourceMode = mode
                                currentStep = IptvWizardStep.CONFIGURE_SOURCE
                            },
                            onClearClick = { showClearDialog = true },
                            focusRequester = primaryFocusRequester
                        )

                        IptvWizardStep.CONFIGURE_SOURCE -> StepConfigureSource(
                            sourceMode = sourceMode,
                            uiState = uiState,
                            directUrlText = directUrlText,
                            onDirectUrlChange = { directUrlText = it },
                            onToggleServer = { viewModel.toggleServer(!uiState.isServerRunning) },
                            onNext = {
                                if (sourceMode == M3uSourceMode.DIRECT_URL && directUrlText.isNotBlank()) {
                                    viewModel.importM3uUrl(directUrlText) { ok ->
                                        if (ok) currentStep = IptvWizardStep.SCAN_CHANNELS
                                    }
                                } else {
                                    currentStep = IptvWizardStep.SCAN_CHANNELS
                                }
                            },
                            onBack = { currentStep = IptvWizardStep.CHOOSE_SOURCE },
                            focusRequester = primaryFocusRequester
                        )

                        IptvWizardStep.SCAN_CHANNELS -> StepScanChannels(
                            uiState = uiState,
                            onStartScan = { viewModel.scanIptvChannels() },
                            onNext = { currentStep = IptvWizardStep.EPG_CONFIG },
                            onBack = { currentStep = IptvWizardStep.CONFIGURE_SOURCE },
                            focusRequester = primaryFocusRequester
                        )

                        IptvWizardStep.EPG_CONFIG -> StepEpgConfig(
                            epgUrlText = epgUrlText,
                            onEpgUrlChange = { epgUrlText = it },
                            onNext = {
                                viewModel.saveAndSyncEpgUrl(epgUrlText) {
                                    currentStep = IptvWizardStep.COMPLETE
                                }
                            },
                            onBack = { currentStep = IptvWizardStep.SCAN_CHANNELS },
                            focusRequester = primaryFocusRequester
                        )

                        IptvWizardStep.COMPLETE -> StepComplete(
                            channelCount = uiState.allowlistCount,
                            onWatchTv = {
                                context.startActivity(Intent(context, TvPlayerActivity::class.java))
                                onFinish()
                            },
                            onExit = { onFinish() },
                            focusRequester = primaryFocusRequester
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepChooseSource(
    sourceMode: M3uSourceMode,
    onSelectMode: (M3uSourceMode) -> Unit,
    onClearClick: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Step 1: Choose M3U Playlist Import Method",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MidnightCyanTokens.TextPrimary
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            var isLanFocused by remember { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isLanFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isLanFocused = it.isFocused }
                    .focusCyanBorder(isLanFocused)
                    .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Dns,
                        contentDescription = "LAN Web Server",
                        tint = MidnightCyanTokens.CyanAccent,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "LAN Web Server",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MidnightCyanTokens.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Upload M3U file or curate channel list from phone or PC browser",
                        fontSize = 13.sp,
                        color = MidnightCyanTokens.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onSelectMode(M3uSourceMode.LAN_SERVER) },
                        colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CyanAccent)
                    ) {
                        Text("Use LAN Server", color = Color(0xFF0B0E14), fontWeight = FontWeight.Bold)
                    }
                }
            }

            var isDirectFocused by remember { mutableStateOf(false) }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isDirectFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isDirectFocused = it.isFocused }
                    .focusCyanBorder(isDirectFocused)
                    .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDownload,
                        contentDescription = "Direct M3U URL",
                        tint = MidnightCyanTokens.CyanAccent,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Direct M3U URL",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MidnightCyanTokens.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter direct HTTP M3U playlist URL or load local M3U file",
                        fontSize = 13.sp,
                        color = MidnightCyanTokens.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { onSelectMode(M3uSourceMode.DIRECT_URL) },
                        colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CardBgElevated)
                    ) {
                        Text("Use Direct URL", color = MidnightCyanTokens.TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        var isClearFocused by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = onClearClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF6679)),
            modifier = Modifier
                .onFocusChanged { isClearFocused = it.isFocused }
                .focusCyanBorder(isClearFocused)
        ) {
            Text("Clear Existing IPTV Channels", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StepConfigureSource(
    sourceMode: M3uSourceMode,
    uiState: M3uCurationUiState,
    directUrlText: String,
    onDirectUrlChange: (String) -> Unit,
    onToggleServer: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = "Step 2: Configure ${if (sourceMode == M3uSourceMode.LAN_SERVER) "LAN Curation Server" else "Direct M3U URL"}",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MidnightCyanTokens.TextPrimary
        )

        if (sourceMode == M3uSourceMode.LAN_SERVER) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MidnightCyanTokens.CardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (uiState.isServerRunning) "LAN Curation Server is RUNNING" else "LAN Curation Server is STOPPED",
                        fontWeight = FontWeight.Bold,
                        color = if (uiState.isServerRunning) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (uiState.isServerRunning && uiState.lanIp != null) {
                        Text(
                            text = "Open on phone/PC: http://${uiState.lanIp}:${uiState.port}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MidnightCyanTokens.TextPrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Allowlist Channels Prepared: ${uiState.allowlistCount}",
                        color = MidnightCyanTokens.CyanAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    var isServerBtnFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = onToggleServer,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isServerRunning) Color(0xFFCF6679) else MidnightCyanTokens.CardBgElevated
                        ),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { isServerBtnFocused = it.isFocused }
                            .focusCyanBorder(isServerBtnFocused)
                    ) {
                        Text(if (uiState.isServerRunning) "Stop Server" else "Start LAN Server", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MidnightCyanTokens.CardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(text = "Enter M3U Playlist URL:", color = MidnightCyanTokens.TextPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = directUrlText,
                        onValueChange = onDirectUrlChange,
                        placeholder = { Text("http://example.com/playlist.m3u8") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MidnightCyanTokens.CyanAccent,
                            unfocusedBorderColor = Color(0xFF334155)
                        )
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back", color = MidnightCyanTokens.TextPrimary)
            }
            var isNextBtnFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CyanAccent),
                modifier = Modifier
                    .onFocusChanged { isNextBtnFocused = it.isFocused }
                    .focusCyanBorder(isNextBtnFocused)
            ) {
                Text("Next: Scan Channels >", color = Color(0xFF0B0E14), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepScanChannels(
    uiState: M3uCurationUiState,
    onStartScan: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = "Step 3: Scan & Import IPTV Streams into TIF",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MidnightCyanTokens.TextPrimary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MidnightCyanTokens.CardBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = "Scan",
                    tint = MidnightCyanTokens.CyanAccent,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.isSyncing) {
                    CircularProgressIndicator(color = MidnightCyanTokens.CyanAccent)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiState.syncStatusMessage ?: "Scanning channels...",
                        color = MidnightCyanTokens.CyanAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (!uiState.syncStatusMessage.isNullOrEmpty()) {
                    Icon(imageVector = Icons.Default.CheckCircle, contentDescription = "Done", tint = Color(0xFF00E676), modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.syncStatusMessage ?: "",
                        color = MidnightCyanTokens.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = "Click below to parse your M3U allowlist and register channels into Android TV's system provider.",
                        color = MidnightCyanTokens.TextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    var isScanBtnFocused by remember { mutableStateOf(false) }
                    Button(
                        onClick = onStartScan,
                        colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CyanAccent),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .onFocusChanged { isScanBtnFocused = it.isFocused }
                            .focusCyanBorder(isScanBtnFocused)
                    ) {
                        Text("Start Channel Scan", color = Color(0xFF0B0E14), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back", color = MidnightCyanTokens.TextPrimary)
            }
            Button(
                onClick = onNext,
                enabled = !uiState.isSyncing,
                colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CardBgElevated)
            ) {
                Text("Next: EPG Setup >", color = MidnightCyanTokens.TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepEpgConfig(
    epgUrlText: String,
    onEpgUrlChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Text(
            text = "Step 4: XMLTV EPG Guide Setup (Optional)",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MidnightCyanTokens.TextPrimary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MidnightCyanTokens.CardBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = "XMLTV EPG URL:", color = MidnightCyanTokens.TextPrimary, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = epgUrlText,
                    onValueChange = onEpgUrlChange,
                    placeholder = { Text("http://example.com/epg.xml") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MidnightCyanTokens.CyanAccent,
                        unfocusedBorderColor = Color(0xFF334155)
                    )
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Automatic background EPG updates will be scheduled via WorkManager.", color = MidnightCyanTokens.TextSecondary, fontSize = 13.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back", color = MidnightCyanTokens.TextPrimary)
            }
            var isFinishBtnFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onNext,
                colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CyanAccent),
                modifier = Modifier
                    .onFocusChanged { isFinishBtnFocused = it.isFocused }
                    .focusCyanBorder(isFinishBtnFocused)
            ) {
                Text("Finish Setup >", color = Color(0xFF0B0E14), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepComplete(
    channelCount: Int,
    onWatchTv: () -> Unit,
    onExit: () -> Unit,
    focusRequester: FocusRequester
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(0.8f)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Complete",
            tint = Color(0xFF00E676),
            modifier = Modifier.size(64.dp)
        )

        Text(
            text = "Step 5: Setup Complete!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MidnightCyanTokens.TextPrimary
        )

        Text(
            text = "Your IPTV stream source is configured and $channelCount TV channels are ready for live streaming.",
            fontSize = 15.sp,
            color = MidnightCyanTokens.TextSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            var isWatchTvFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onWatchTv,
                colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CyanAccent),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .onFocusChanged { isWatchTvFocused = it.isFocused }
                    .focusCyanBorder(isWatchTvFocused)
            ) {
                Icon(imageVector = Icons.Outlined.LiveTv, contentDescription = "Watch", tint = Color(0xFF0B0E14))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Watch Live TV", color = Color(0xFF0B0E14), fontWeight = FontWeight.Bold)
            }

            var isExitFocused by remember { mutableStateOf(false) }
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(containerColor = MidnightCyanTokens.CardBgElevated),
                modifier = Modifier
                    .onFocusChanged { isExitFocused = it.isFocused }
                    .focusCyanBorder(isExitFocused)
            ) {
                Text("Return to TV Inputs", color = MidnightCyanTokens.TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

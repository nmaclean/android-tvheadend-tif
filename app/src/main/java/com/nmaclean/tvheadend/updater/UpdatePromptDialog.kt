package com.nmaclean.tvheadend.updater

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI state machine for the Jetpack Compose for TV in-app updater card.
 */
sealed class UpdateUiState {
    data object Checking : UpdateUiState()
    data class UpToDate(val currentVersion: String) : UpdateUiState()
    data class Available(val release: GitHubReleaseInfo) : UpdateUiState()
    data class Downloading(val release: GitHubReleaseInfo, val progress: Float) : UpdateUiState()
    data class ReadyToInstall(val release: GitHubReleaseInfo, val apkFile: File) : UpdateUiState()
    data class Error(val message: String) : UpdateUiState()
}

/**
 * D-pad-navigable Jetpack Compose for TV dialog hosting [UpdatePromptCard].
 */
class UpdatePromptDialog(
    context: Context,
    private val updateManager: GitHubUpdateManager = GitHubUpdateManager()
) : ComponentDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        val composeView = ComposeView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setContent {
                MaterialTheme {
                    UpdatePromptCard(
                        context = context,
                        updateManager = updateManager,
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
        setContentView(composeView)
        composeView.post { composeView.requestFocus() }
    }
}

/**
 * D-pad-focusable Jetpack Compose for TV card supporting Check, Download progress, and Install states.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UpdatePromptCard(
    context: Context,
    updateManager: GitHubUpdateManager,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Checking) }
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        uiState = when (val result = updateManager.checkForUpdate(BuildConfig.VERSION_NAME)) {
            is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(result.release)
            is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(result.currentVersion)
            is UpdateCheckResult.Error -> UpdateUiState.Error(result.message)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState !is UpdateUiState.Checking && uiState !is UpdateUiState.Downloading) {
            delay(50)
            runCatching { primaryFocusRequester.requestFocus() }
        }
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 420.dp, max = 560.dp)
            .background(Color(0xFF141923), RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, Color(0xFF2E3A4E)), RoundedCornerShape(16.dp))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141923)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Software Update",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            when (val state = uiState) {
                is UpdateUiState.Checking -> {
                    Text(
                        text = "Checking GitHub Releases for updates (installed v${BuildConfig.VERSION_NAME})...",
                        color = Color(0xFFB8C4D4),
                        fontSize = 15.sp
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is UpdateUiState.UpToDate -> {
                    Text(
                        text = "You are running the latest version (v${state.currentVersion}).",
                        color = Color(0xFFB8C4D4),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    uiState = UpdateUiState.Checking
                                    uiState = when (val res = updateManager.checkForUpdate(BuildConfig.VERSION_NAME)) {
                                        is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(res.release)
                                        is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(res.currentVersion)
                                        is UpdateCheckResult.Error -> UpdateUiState.Error(res.message)
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(containerColor = Color(0xFF2A62FF))
                        ) {
                            Text("Check Again", color = Color.White)
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("Close", color = Color.White)
                        }
                    }
                }

                is UpdateUiState.Available -> {
                    Text(
                        text = "Update available: ${state.release.tagName} (installed v${BuildConfig.VERSION_NAME})",
                        color = Color(0xFF8FB8FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val notes = state.release.releaseNotes.ifBlank { "No release notes provided." }
                    Text(
                        text = notes,
                        color = Color(0xFFD5DFEC),
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                Log.i("UpdatePrompt", "Download & Install clicked for ${state.release.tagName} (${state.release.apkDownloadUrl})")
                                scope.launch {
                                    uiState = UpdateUiState.Downloading(state.release, 0f)
                                    try {
                                        val apk = updateManager.downloadApk(context, state.release.apkDownloadUrl) { done, total ->
                                            if (total > 0L) {
                                                val progressFraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                                scope.launch(Dispatchers.Main) {
                                                    if (uiState is UpdateUiState.Downloading) {
                                                        uiState = UpdateUiState.Downloading(state.release, progressFraction)
                                                    }
                                                }
                                            }
                                        }
                                        Log.i("UpdatePrompt", "APK downloaded to ${apk.absolutePath} (${apk.length()} bytes)")
                                        uiState = UpdateUiState.ReadyToInstall(state.release, apk)
                                        if (ApkInstaller.installApk(context, apk)) {
                                            onDismiss()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("UpdatePrompt", "Download/install failed", e)
                                        uiState = UpdateUiState.Error(e.message ?: "Download failed")
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(containerColor = Color(0xFF2A62FF))
                        ) {
                            Text("Download & Install", color = Color.White)
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("Later", color = Color.White)
                        }
                    }
                }

                is UpdateUiState.Downloading -> {
                    val pct = (state.progress * 100).toInt()
                    Text(
                        text = "Downloading ${state.release.assetName} ($pct%)...",
                        color = Color(0xFFB8C4D4),
                        fontSize = 15.sp
                    )
                    if (state.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                is UpdateUiState.ReadyToInstall -> {
                    Text(
                        text = "Downloaded ${state.release.tagName}. If prompted, allow 'Install unknown apps' and press Install.",
                        color = Color(0xFFB8C4D4),
                        fontSize = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (ApkInstaller.installApk(context, state.apkFile)) {
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(containerColor = Color(0xFF2A62FF))
                        ) {
                            Text("Install Now", color = Color.White)
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("Close", color = Color.White)
                        }
                    }
                }

                is UpdateUiState.Error -> {
                    Text(
                        text = "Update error: ${state.message}",
                        color = Color(0xFFFF8A8A),
                        fontSize = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    uiState = UpdateUiState.Checking
                                    uiState = when (val res = updateManager.checkForUpdate(BuildConfig.VERSION_NAME)) {
                                        is UpdateCheckResult.UpdateAvailable -> UpdateUiState.Available(res.release)
                                        is UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate(res.currentVersion)
                                        is UpdateCheckResult.Error -> UpdateUiState.Error(res.message)
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(primaryFocusRequester),
                            colors = ButtonDefaults.colors(containerColor = Color(0xFF2A62FF))
                        ) {
                            Text("Retry", color = Color.White)
                        }
                        OutlinedButton(onClick = onDismiss) {
                            Text("Close", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

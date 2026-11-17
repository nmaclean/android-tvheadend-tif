package com.nmaclean.tvheadend.updater

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.app.AlertDialog
import com.nmaclean.tvheadend.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * D-pad-navigable Android TV dialog for checking, downloading, and installing GitHub Releases APKs.
 */
class UpdatePromptDialog(
    private val context: Context,
    private val updateManager: GitHubUpdateManager = GitHubUpdateManager()
) {
    companion object {
        private const val TAG = "UpdatePrompt"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeJob: Job? = null
    private var dialog: AlertDialog? = null

    private lateinit var messageView: TextView
    private lateinit var progressBar: ProgressBar

    fun show() {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()

        messageView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            text = "Checking GitHub Releases for updates (installed v${BuildConfig.VERSION_NAME})..."
        }

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            max = 100
            setPadding(0, (16 * density).toInt(), 0, 0)
        }

        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (16 * density).toInt(), pad, (8 * density).toInt())
            addView(messageView)
            addView(progressBar)
        }

        val scrollView = ScrollView(context).apply {
            addView(contentLayout)
        }

        val alertDialog = AlertDialog.Builder(context)
            .setTitle("Software Update")
            .setView(scrollView)
            .setPositiveButton("Download & Install", null)
            .setNegativeButton("Close") { d, _ ->
                activeJob?.cancel()
                d.dismiss()
            }
            .setOnDismissListener {
                activeJob?.cancel()
            }
            .create()

        dialog = alertDialog
        alertDialog.show()

        // Hide positive button while checking
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE
        alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()

        startUpdateCheck()
    }

    private fun startUpdateCheck() {
        activeJob?.cancel()
        val d = dialog ?: return
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        messageView.text = "Checking GitHub Releases for updates (installed v${BuildConfig.VERSION_NAME})..."
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE

        activeJob = scope.launch {
            Log.i(TAG, "Checking for updates (installed v${BuildConfig.VERSION_NAME})...")
            when (val result = updateManager.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.UpdateAvailable -> showAvailable(result.release)
                is UpdateCheckResult.UpToDate -> showUpToDate(result.currentVersion)
                is UpdateCheckResult.Error -> showError(result.message)
            }
        }
    }

    private fun showUpToDate(currentVersion: String) {
        val d = dialog ?: return
        if (!d.isShowing) return
        progressBar.visibility = View.GONE
        messageView.text = "You are running the latest version (v$currentVersion)."
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = "Check Again"
            visibility = View.VISIBLE
            setOnClickListener { startUpdateCheck() }
            requestFocus()
        }
    }

    private fun showAvailable(release: GitHubReleaseInfo) {
        val d = dialog ?: return
        if (!d.isShowing) return
        Log.i(TAG, "Update available: ${release.tagName} (${release.apkDownloadUrl})")
        progressBar.visibility = View.GONE
        val notes = release.releaseNotes.ifBlank { "No release notes provided." }
        messageView.text = "Update available: ${release.tagName} (installed v${BuildConfig.VERSION_NAME})\n\n$notes"

        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Later"
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = "Download & Install"
            visibility = View.VISIBLE
            setOnClickListener {
                Log.i(TAG, "Download & Install clicked for ${release.tagName}")
                startDownloadAndInstall(release)
            }
            requestFocus()
        }
    }

    private fun startDownloadAndInstall(release: GitHubReleaseInfo) {
        val d = dialog ?: return
        if (!d.isShowing) return

        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressBar.progress = 0
        messageView.text = "Downloading ${release.assetName}..."
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility = View.GONE

        activeJob = scope.launch {
            try {
                val apkFile = updateManager.downloadApk(context, release.apkDownloadUrl) { done, total ->
                    if (total > 0L) {
                        val pct = ((done * 100L) / total).toInt().coerceIn(0, 100)
                        scope.launch(Dispatchers.Main) {
                            if (d.isShowing) {
                                progressBar.isIndeterminate = false
                                progressBar.progress = pct
                                messageView.text = "Downloading ${release.assetName}: $pct%"
                            }
                        }
                    }
                }
                Log.i(TAG, "APK downloaded to ${apkFile.absolutePath} (${apkFile.length()} bytes)")
                withContext(Dispatchers.Main) {
                    if (!d.isShowing) return@withContext
                    if (ApkInstaller.installApk(context, apkFile)) {
                        d.dismiss()
                    } else {
                        showReadyToInstall(release, apkFile)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download/install failed", e)
                withContext(Dispatchers.Main) {
                    showError(e.message ?: "Download failed")
                }
            }
        }
    }

    private fun showReadyToInstall(release: GitHubReleaseInfo, apkFile: File) {
        val d = dialog ?: return
        if (!d.isShowing) return
        progressBar.visibility = View.GONE
        messageView.text = "Downloaded ${release.tagName}. Allow 'Install unknown apps' if prompted, then press Install Now."
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Close"
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = "Install Now"
            visibility = View.VISIBLE
            setOnClickListener {
                if (ApkInstaller.installApk(context, apkFile)) {
                    d.dismiss()
                }
            }
            requestFocus()
        }
    }

    private fun showError(message: String) {
        val d = dialog ?: return
        if (!d.isShowing) return
        progressBar.visibility = View.GONE
        messageView.text = "Update error: $message"
        d.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "Close"
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            text = "Retry"
            visibility = View.VISIBLE
            setOnClickListener { startUpdateCheck() }
            requestFocus()
        }
    }
}

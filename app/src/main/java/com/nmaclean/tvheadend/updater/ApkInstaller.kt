package com.nmaclean.tvheadend.updater

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Triggers Android package installation for a downloaded APK via [FileProvider],
 * handling the unknown-sources permission check on Android 8.0+ (API 26+).
 */
object ApkInstaller {

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * Returns `true` if this app is permitted to request package installations.
     */
    fun canInstallPackages(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * Builds an [Intent] directing the user to grant "Install unknown apps" permission.
     */
    fun buildUnknownSourcesIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Builds an [Intent.ACTION_VIEW] install [Intent] backed by [FileProvider] for [apkFile].
     */
    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches the system APK installer if permitted, or opens the unknown-sources settings screen.
     *
     * @return `true` if the package installer was launched, `false` if permission settings were opened instead.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!canInstallPackages(context)) {
            try {
                context.startActivity(buildUnknownSourcesIntent(context))
            } catch (_: ActivityNotFoundException) {
                val appDetailsIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(appDetailsIntent)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            return false
        }
        Log.i("ApkInstaller", "Launching package installer for ${apkFile.absolutePath}")
        context.startActivity(buildInstallIntent(context, apkFile))
        return true
    }

    /**
     * Copies [apkFile] into the shared public Downloads directory so it survives app uninstallation.
     */
    fun saveToDownloads(context: Context, apkFile: File, fileName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, APK_MIME_TYPE)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    apkFile.inputStream().use { input -> input.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, fileName)
                apkFile.inputStream().use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                true
            }
        } catch (e: Exception) {
            Log.e("ApkInstaller", "Failed to save APK to Downloads", e)
            false
        }
    }

    /**
     * Launches the system uninstall prompt for this package.
     */
    fun launchUninstall(context: Context) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

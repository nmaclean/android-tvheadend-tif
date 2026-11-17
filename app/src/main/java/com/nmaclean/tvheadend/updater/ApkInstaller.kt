package com.nmaclean.tvheadend.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
            context.startActivity(buildUnknownSourcesIntent(context))
            return false
        }
        context.startActivity(buildInstallIntent(context, apkFile))
        return true
    }
}

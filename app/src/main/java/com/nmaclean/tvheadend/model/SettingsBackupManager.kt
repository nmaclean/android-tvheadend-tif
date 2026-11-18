package com.nmaclean.tvheadend.model

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.nmaclean.tvheadend.BuildConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Serializable snapshot of TIF server connection settings and General app preferences
 * used to preserve configuration across downgrade uninstall/reinstall cycles.
 */
data class SettingsBackupPayload(
    val version: Int = BACKUP_SCHEMA_VERSION,
    val exportedFromVersion: String = "",
    val exportedAtMs: Long = 0L,
    val host: String = "",
    val htspPort: Int = TvhConstants.DEFAULT_HTSP_PORT,
    val httpPort: Int = TvhConstants.DEFAULT_HTTP_PORT,
    val username: String = "",
    val password: String = "",
    val subtitleBottomPaddingFraction: Float = 0.08f,
    val isStreamingDebugEnabled: Boolean = false
) {
    companion object {
        const val BACKUP_SCHEMA_VERSION = 1
    }
}

/**
 * Automatically exports [TvhTifSettings] and [TvhGeneralSettings] to the public Downloads folder
 * before a downgrade uninstall, and automatically restores them on first launch if settings are blank.
 */
object SettingsBackupManager {

    private const val TAG = "SettingsBackupManager"
    const val BACKUP_FILE_NAME = "tvheadend-tif-settings.json"
    private const val BACKUP_MIME_TYPE = "application/json"

    private val gson = Gson()

    fun serialize(payload: SettingsBackupPayload): String = gson.toJson(payload)

    fun deserialize(json: String): SettingsBackupPayload? {
        if (json.isBlank()) return null
        return try {
            val parsed = gson.fromJson(json, SettingsBackupPayload::class.java) ?: return null
            if (parsed.host.isBlank()) return null
            parsed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exports current [TvhTifSettings] and [TvhGeneralSettings] into public Downloads so they survive uninstall.
     * Returns `true` if the backup was written (or `false` if no host is configured or write failed).
     */
    fun exportToDownloads(context: Context): Boolean {
        val tif = TvhTifSettings(context)
        val general = TvhGeneralSettings(context)
        if (tif.host.isBlank()) {
            Log.i(TAG, "Skipping settings backup export: host is blank")
            return false
        }

        val payload = SettingsBackupPayload(
            exportedFromVersion = BuildConfig.VERSION_NAME,
            exportedAtMs = System.currentTimeMillis(),
            host = tif.host,
            htspPort = tif.htspPort,
            httpPort = tif.httpPort,
            username = tif.username,
            password = tif.password,
            subtitleBottomPaddingFraction = general.subtitleBottomPaddingFraction,
            isStreamingDebugEnabled = general.isStreamingDebugEnabled
        )
        val jsonBytes = serialize(payload).toByteArray(StandardCharsets.UTF_8)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                // Replace any previously exported backup owned by this app to avoid "(1)" duplicates
                try {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                        arrayOf(BACKUP_FILE_NAME)
                    )
                } catch (_: Exception) {}

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, BACKUP_MIME_TYPE)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonBytes)
                } ?: return false
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Log.i(TAG, "Exported settings backup to MediaStore Downloads ($BACKUP_FILE_NAME)")
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                File(dir, BACKUP_FILE_NAME).writeBytes(jsonBytes)
                Log.i(TAG, "Exported settings backup to ${dir.absolutePath}/$BACKUP_FILE_NAME")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export settings backup to Downloads: ${e.message}")
            false
        }
    }

    /**
     * If TIF settings are currently unconfigured (`host.isBlank()`), attempts to read
     * [BACKUP_FILE_NAME] from public Downloads and restore all TIF and General settings.
     */
    fun tryAutoImportIfFresh(context: Context): Boolean {
        val tif = TvhTifSettings(context)
        if (tif.host.isNotBlank()) return false

        val json = readBackupFromDownloads(context) ?: return false
        val payload = deserialize(json) ?: return false
        applyPayload(context, payload)
        Log.i(TAG, "Auto-restored settings from backup (exported from ${payload.exportedFromVersion})")
        return true
    }

    fun importFromUri(context: Context, uri: Uri): Boolean {
        val json = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading settings backup from URI $uri: ${e.message}")
            null
        } ?: return false

        val payload = deserialize(json) ?: return false
        applyPayload(context, payload)
        return true
    }

    fun applyPayload(context: Context, payload: SettingsBackupPayload) {
        val tif = TvhTifSettings(context)
        tif.host = payload.host
        tif.htspPort = if (payload.htspPort > 0) payload.htspPort else TvhConstants.DEFAULT_HTSP_PORT
        tif.httpPort = if (payload.httpPort > 0) payload.httpPort else TvhConstants.DEFAULT_HTTP_PORT
        tif.username = payload.username
        tif.password = payload.password

        val general = TvhGeneralSettings(context)
        general.subtitleBottomPaddingFraction = payload.subtitleBottomPaddingFraction.coerceIn(0.02f, 0.25f)
        general.isStreamingDebugEnabled = payload.isStreamingDebugEnabled
    }

    private fun readBackupFromDownloads(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(BACKUP_FILE_NAME),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        resolver.openInputStream(uri)?.use { input ->
                            return input.readBytes().toString(StandardCharsets.UTF_8)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "MediaStore Downloads auto-import skipped: ${e.message}")
            }
        }

        return try {
            @Suppress("DEPRECATION")
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BACKUP_FILE_NAME
            )
            if (file.exists() && file.canRead()) {
                file.readText(StandardCharsets.UTF_8)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct Downloads auto-import skipped: ${e.message}")
            null
        }
    }
}

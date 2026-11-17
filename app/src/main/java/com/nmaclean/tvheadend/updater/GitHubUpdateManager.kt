package com.nmaclean.tvheadend.updater

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nmaclean.tvheadend.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Metadata for an installable APK release published on GitHub Releases.
 */
data class GitHubReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val assetName: String,
    val assetSizeBytes: Long,
    val isPreRelease: Boolean
)

/**
 * Result of checking GitHub Releases for a newer application version.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Checks `/repos/<owner>/<repo>/releases/latest` on GitHub and downloads APK assets with progress.
 */
class GitHubUpdateManager(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val apiBaseUrl: String = GITHUB_API_BASE_URL
) {
    companion object {
        const val DEFAULT_OWNER = "nmaclean"
        const val DEFAULT_REPO = "android-tvheadend-tif"
        const val GITHUB_API_BASE_URL = "https://api.github.com"
        const val GITHUB_WEB_BASE_URL = "https://github.com"
        private const val UPDATES_DIR = "updates"
        private const val UPDATE_APK_NAME = "update.apk"
    }

    /**
     * Checks GitHub Releases for a newer stable APK release compared to [currentVersion].
     * Falls back to GitHub's non-API `releases.atom` feed when `api.github.com` returns 403/429 rate limits.
     */
    suspend fun checkForUpdate(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val url = "$apiBaseUrl/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "android-tvheadend-tif/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val atomFallback = fetchReleasesViaAtom(limit = 5).getOrNull()?.firstOrNull()
                    if (atomFallback != null) {
                        return@withContext if (SemVerComparator.isNewer(atomFallback.tagName, currentVersion)) {
                            UpdateCheckResult.UpdateAvailable(atomFallback)
                        } else {
                            UpdateCheckResult.UpToDate(currentVersion)
                        }
                    }
                    return@withContext UpdateCheckResult.Error(
                        "GitHub API returned HTTP ${response.code}"
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext UpdateCheckResult.Error("Empty response from GitHub API")
                }

                val release = parseLatestReleaseJson(body)
                    ?: return@withContext UpdateCheckResult.UpToDate(currentVersion)

                if (release.isPreRelease) {
                    return@withContext UpdateCheckResult.UpToDate(currentVersion)
                }

                if (SemVerComparator.isNewer(release.tagName, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(release)
                } else {
                    UpdateCheckResult.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            val atomFallback = fetchReleasesViaAtom(limit = 5).getOrNull()?.firstOrNull()
            if (atomFallback != null) {
                if (SemVerComparator.isNewer(atomFallback.tagName, currentVersion)) {
                    UpdateCheckResult.UpdateAvailable(atomFallback)
                } else {
                    UpdateCheckResult.UpToDate(currentVersion)
                }
            } else {
                UpdateCheckResult.Error(e.message ?: "Failed to check for updates")
            }
        }
    }

    /**
     * Fetches recent stable APK releases from GitHub Releases (newest first) for version selection/rollback.
     * Falls back to GitHub's `releases.atom` feed when `api.github.com` is rate-limited (HTTP 403/429).
     */
    suspend fun fetchReleases(limit: Int = 15): Result<List<GitHubReleaseInfo>> = withContext(Dispatchers.IO) {
        val url = "$apiBaseUrl/repos/$owner/$repo/releases?per_page=$limit"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "android-tvheadend-tif/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val atomResult = fetchReleasesViaAtom(limit = limit)
                    if (atomResult.isSuccess) return@withContext atomResult
                    return@withContext Result.failure(IOException("GitHub API returned HTTP ${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    val atomResult = fetchReleasesViaAtom(limit = limit)
                    if (atomResult.isSuccess) return@withContext atomResult
                    return@withContext Result.failure(IOException("Empty response from GitHub API"))
                }
                Result.success(parseReleasesListJson(body))
            }
        } catch (e: Exception) {
            val atomResult = fetchReleasesViaAtom(limit = limit)
            if (atomResult.isSuccess) atomResult else Result.failure(e)
        }
    }

    private fun fetchReleasesViaAtom(limit: Int): Result<List<GitHubReleaseInfo>> {
        val atomUrl = "$GITHUB_WEB_BASE_URL/$owner/$repo/releases.atom"
        val request = Request.Builder()
            .url(atomUrl)
            .header("Accept", "application/atom+xml,application/xml,text/xml")
            .header("User-Agent", "android-tvheadend-tif/${BuildConfig.VERSION_NAME}")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("GitHub Atom feed returned HTTP ${response.code}"))
                }
                val xml = response.body?.string().orEmpty()
                val parsed = parseReleasesAtom(xml, limit)
                if (parsed.isEmpty()) {
                    Result.failure(IOException("No releases found in GitHub Atom feed"))
                } else {
                    Result.success(parsed)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parses GitHub's `releases.atom` XML feed into [GitHubReleaseInfo] entries with direct APK download URLs.
     */
    internal fun parseReleasesAtom(xml: String, limit: Int = 15): List<GitHubReleaseInfo> {
        if (xml.isBlank()) return emptyList()
        val entryRegex = Regex("<entry>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL))
        val linkRegex = Regex("""href="https?://[^"]*/releases/tag/([^"/\s]+)"""")
        val titleRegex = Regex("<title>([^<]+)</title>")

        val results = mutableListOf<GitHubReleaseInfo>()
        for (match in entryRegex.findAll(xml)) {
            if (results.size >= limit) break
            val entry = match.groupValues[1]
            val tagName = linkRegex.find(entry)?.groupValues?.getOrNull(1)?.trim()
                ?: titleRegex.find(entry)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            if (tagName.isBlank()) continue
            val isPre = tagName.contains("-", ignoreCase = true)
            if (isPre) continue

            val downloadUrl = "$GITHUB_WEB_BASE_URL/$owner/$repo/releases/download/$tagName/app-release.apk"
            results.add(
                GitHubReleaseInfo(
                    tagName = tagName,
                    versionName = tagName.removePrefix("v").removePrefix("V"),
                    releaseNotes = "Release $tagName",
                    apkDownloadUrl = downloadUrl,
                    assetName = "app-release.apk",
                    assetSizeBytes = 0L,
                    isPreRelease = false
                )
            )
        }
        return results
    }

    /**
     * Parses a GitHub Releases API JSON array into a list of installable [GitHubReleaseInfo] entries.
     */
    internal fun parseReleasesListJson(json: String): List<GitHubReleaseInfo> {
        val arr = try {
            JsonParser.parseString(json).asJsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        val list = mutableListOf<GitHubReleaseInfo>()
        for (el in arr) {
            if (!el.isJsonObject) continue
            val info = parseReleaseObject(el.asJsonObject) ?: continue
            if (!info.isPreRelease) {
                list.add(info)
            }
        }
        return list
    }

    /**
     * Parses a GitHub Releases API JSON payload into [GitHubReleaseInfo], returning `null`
     * if the release is a draft or has no downloadable `.apk` asset.
     */
    internal fun parseLatestReleaseJson(json: String): GitHubReleaseInfo? {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: Exception) {
            return null
        }
        return parseReleaseObject(root)
    }

    private fun parseReleaseObject(root: JsonObject): GitHubReleaseInfo? {

        if (root.get("draft")?.asBoolean == true) {
            return null
        }

        val tagName = root.get("tag_name")?.takeIf { !it.isJsonNull }?.asString?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val isPreRelease = root.get("prerelease")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        val body = root.get("body")?.takeIf { !it.isJsonNull }?.asString.orEmpty()

        val assets = root.getAsJsonArray("assets") ?: return null
        var apkAssetUrl: String? = null
        var apkAssetName: String? = null
        var apkAssetSize = 0L

        for (element in assets) {
            if (!element.isJsonObject) continue
            val assetObj = element.asJsonObject
            val name = assetObj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
            val downloadUrl = assetObj.get("browser_download_url")
                ?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue

            if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                apkAssetUrl = downloadUrl
                apkAssetName = name
                apkAssetSize = assetObj.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                break
            }
        }

        if (apkAssetUrl == null || apkAssetName == null) {
            return null
        }

        return GitHubReleaseInfo(
            tagName = tagName,
            versionName = tagName.removePrefix("v").removePrefix("V"),
            releaseNotes = body,
            apkDownloadUrl = apkAssetUrl,
            assetName = apkAssetName,
            assetSizeBytes = apkAssetSize,
            isPreRelease = isPreRelease
        )
    }

    /**
     * Downloads the APK from [downloadUrl] into `File(context.cacheDir, "updates/update.apk")`
     * and reports byte progress via [onProgress].
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, UPDATES_DIR).apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(updatesDir, UPDATE_APK_NAME)
        val tempFile = File(updatesDir, "$UPDATE_APK_NAME.tmp")
        if (tempFile.exists()) tempFile.delete()

        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "android-tvheadend-tif/${BuildConfig.VERSION_NAME}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("APK download failed with HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty APK download response")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress(downloadedBytes, totalBytes)
                    }
                    output.flush()
                }
            }
        }

        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
        targetFile
    }
}

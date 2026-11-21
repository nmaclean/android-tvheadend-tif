package com.nmaclean.tvheadend.iptv.logo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nmaclean.tvheadend.iptv.epg.EpgParseResult
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class IptvLogoManager(private val context: Context) {
    companion object {
        const val TAG = "IptvLogoManager"
    }

    private val logoDir = File(context.filesDir, "iptv_logos").apply { if (!exists()) mkdirs() }
    private val epgSettings = IptvEpgSettingsStore(context)
    private val ramCache = ConcurrentHashMap<String, String>()

    fun resolveAndCacheLogo(channel: AllowlistChannel, epgResult: EpgParseResult? = null): String? {
        if (!epgSettings.isLogosEnabled) return channel.logoUrl

        val cacheKey = "${channel.id}_${channel.logoUrl ?: ""}"
        ramCache[cacheKey]?.let { return it }

        var candidateUrl = channel.logoUrl
        if (candidateUrl.isNullOrEmpty() && epgSettings.useEpgIcon && epgResult != null) {
            val tvgId = channel.tvgId?.lowercase()?.trim()
            val nameNorm = channel.name.lowercase().replace(Regex("[^\\w]"), "")

            for ((chKey, programmes) in epgResult.programmesByChannel) {
                val keyNorm = chKey.lowercase().trim()
                if ((tvgId != null && keyNorm == tvgId) || keyNorm == nameNorm || keyNorm.replace(Regex("[^\\w]"), "") == nameNorm) {
                    val progWithIcon = programmes.find { !it.posterArtUri.isNullOrEmpty() }
                    if (progWithIcon != null) {
                        candidateUrl = progWithIcon.posterArtUri
                        break
                    }
                }
            }
        }

        if (candidateUrl.isNullOrEmpty() || !candidateUrl.startsWith("http")) {
            return channel.logoUrl
        }

        try {
            val fileName = "logo_${channel.id.hashCode()}_${candidateUrl.hashCode()}.jpg"
            val cachedFile = File(logoDir, fileName)
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                val resultUri = Uri.fromFile(cachedFile).toString()
                ramCache[cacheKey] = resultUri
                return resultUri
            }

            val url = URL(candidateUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.inputStream.use { input ->
                FileOutputStream(cachedFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            if (cachedFile.exists() && cachedFile.length() > 0L) {
                val resultUri = Uri.fromFile(cachedFile).toString()
                ramCache[cacheKey] = resultUri
                return resultUri
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download/cache logo for channel ${channel.name}: ${e.message}")
        }

        return channel.logoUrl
    }

    fun clearRamCache() {
        ramCache.clear()
    }
}

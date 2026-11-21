package com.nmaclean.tvheadend.iptv.scan

import android.content.Context
import android.util.Log
import com.nmaclean.tvheadend.iptv.epg.EpgParseResult
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.epg.StreamingXmltvParser
import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

enum class ChannelScanStatus {
    REACHABLE,
    UNREACHABLE,
    SKIPPED
}

data class ChannelScanResult(
    val channelId: String,
    val name: String,
    val status: ChannelScanStatus,
    val latencyMs: Long,
    val errorMessage: String? = null
)

data class IptvScanReport(
    val totalChannels: Int,
    val reachableCount: Int,
    val unreachableCount: Int,
    val skippedCount: Int,
    val results: List<ChannelScanResult>,
    val epgParseResult: EpgParseResult?
)

class IptvStreamScanner(private val context: Context) {
    companion object {
        const val TAG = "IptvStreamScanner"
    }

    private val allowlistRepository = AllowlistRepository(context.filesDir)
    private val epgSettings = IptvEpgSettingsStore(context)

    suspend fun scanAll(onProgress: ((current: Int, total: Int, currentName: String) -> Unit)? = null): IptvScanReport {
        return withContext(Dispatchers.IO) {
            val snapshot = allowlistRepository.load()
            val channels = snapshot.channels
            val results = mutableListOf<ChannelScanResult>()
            var reachableCount = 0
            var unreachableCount = 0
            var skippedCount = 0

            channels.forEachIndexed { index, channel ->
                onProgress?.invoke(index + 1, channels.size, channel.name)
                val res = probeChannel(channel)
                results.add(res)
                when (res.status) {
                    ChannelScanStatus.REACHABLE -> reachableCount++
                    ChannelScanStatus.UNREACHABLE -> unreachableCount++
                    ChannelScanStatus.SKIPPED -> skippedCount++
                }
            }

            // Load EPG if enabled
            var epgResult: EpgParseResult? = null
            if (epgSettings.isEpgEnabled) {
                epgResult = fetchAndParseEpg()
            }

            IptvScanReport(
                totalChannels = channels.size,
                reachableCount = reachableCount,
                unreachableCount = unreachableCount,
                skippedCount = skippedCount,
                results = results,
                epgParseResult = epgResult
            )
        }
    }

    private fun probeChannel(channel: AllowlistChannel): ChannelScanResult {
        val urlStr = channel.streamUrl.trim()
        if (urlStr.isEmpty() || (!urlStr.startsWith("http://") && !urlStr.startsWith("https://"))) {
            return ChannelScanResult(channel.id, channel.name, ChannelScanStatus.SKIPPED, 0L, "Invalid or unsupported stream scheme")
        }

        val startTime = System.currentTimeMillis()
        try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            connection.requestMethod = "HEAD"
            channel.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            val responseCode = try {
                connection.responseCode
            } catch (_: Exception) {
                connection.requestMethod = "GET"
                connection.connect()
                connection.responseCode
            }

            val latency = System.currentTimeMillis() - startTime
            connection.disconnect()

            if (responseCode in 200..399) {
                return ChannelScanResult(channel.id, channel.name, ChannelScanStatus.REACHABLE, latency)
            } else {
                return ChannelScanResult(channel.id, channel.name, ChannelScanStatus.UNREACHABLE, latency, "HTTP status $responseCode")
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            return ChannelScanResult(channel.id, channel.name, ChannelScanStatus.UNREACHABLE, latency, e.message ?: "Connection failed")
        }
    }

    private fun fetchAndParseEpg(): EpgParseResult? {
        try {
            val localFile = File(context.filesDir, "iptv_epg.xml")
            if (localFile.exists()) {
                localFile.inputStream().use { return StreamingXmltvParser.parse(it) }
            }

            val epgUrlStr = epgSettings.epgUrl
            if (epgUrlStr.isNotEmpty() && epgUrlStr.startsWith("http")) {
                val url = URL(epgUrlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.inputStream.use { stream ->
                    return StreamingXmltvParser.parse(stream)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch/parse EPG: ${e.message}")
        }
        return null
    }
}

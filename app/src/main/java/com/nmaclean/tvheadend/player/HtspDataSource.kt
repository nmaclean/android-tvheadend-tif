package com.nmaclean.tvheadend.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.nmaclean.tvheadend.htsp.HtspClient
import com.nmaclean.tvheadend.htsp.HtspConnectionPool
import com.nmaclean.tvheadend.model.TvhConstants
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.telemetry.TuneStage
import com.nmaclean.tvheadend.telemetry.TuneTelemetryTracker
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Custom Media3 ExoPlayer DataSource reading HTSP packet streams directly over TCP from a Tvheadend server.
 */
@OptIn(UnstableApi::class)
class HtspDataSource(private val context: Context) : BaseDataSource(true) {

    @OptIn(UnstableApi::class)
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return HtspDataSource(context)
        }
    }

    companion object {
        private const val TAG = "HtspDataSource"
    }

    private var client: HtspClient? = null
    private var subscriptionToken = 0L
    private var opened = false
    private var currentUri: Uri? = null
    private var totalBytesRead = 0L
    private var readCount = 0L
    private var lastStatTimeMs = 0L
    private var lastStatBytes = 0L
    private var hasSwitchedToStreamingTimeout = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        currentUri = uri
        totalBytesRead = 0L
        readCount = 0L
        lastStatTimeMs = 0L
        lastStatBytes = 0L

        val channelIdentifier = uri.lastPathSegment ?: "1"
        Log.i(TAG, "=== HTSP DATASOURCE OPENING (NATIVE): $uri ===")

        val settings = TvhSettings(context)
        val isDebug = settings.isStreamingDebugEnabled
        val host = uri.host ?: settings.host
        val port = if (uri.port != -1) uri.port else settings.htspPort
        val user = settings.username
        val pass = settings.password

        if (isDebug) {
            Log.d(TAG, "TVH_STREAM_DEBUG: HtspDataSource opening $uri (host=$host, port=$port, user='$user', channelIdentifier='$channelIdentifier')")
        }

        try {
            val activeClient = HtspConnectionPool.getFreshStreamingClient(context, host, port, user, pass)

            // Ensure any prior active stream subscription or lingering socket buffer bytes are cleanly flushed
            if (activeClient.hasActiveSubscription) {
                try {
                    activeClient.unsubscribe()
                } catch (e: Exception) {
                    Log.w(TAG, "Error unsubscribing prior stream: ${e.message}")
                }
            } else {
                try {
                    activeClient.flushInputStream()
                } catch (_: Exception) {}
            }

            Log.i(TAG, "HTSP connected. Subscribing to channel $channelIdentifier with default profile...")
            var subResult = activeClient.subscribe(channelIdentifier, null, 1500L)

            if (subResult == null || subResult["error"] != null) {
                Log.w(TAG, "Subscription with default profile failed. Retrying with 'htsp' profile...")
                subResult = activeClient.subscribe(channelIdentifier, "htsp", 1500L)
            }

            if (subResult == null || subResult["error"] != null) {
                val errorMsg = subResult?.get("error")?.toString() ?: "Unknown subscription error"
                HtspConnectionPool.releaseStreamingClient()
                throw IOException("HTSP subscription failed: $errorMsg")
            }

            client = activeClient
            subscriptionToken = activeClient.currentSubscriptionToken
            activeClient.setSoTimeout(TvhConstants.INITIAL_STREAM_TIMEOUT_MS)
            opened = true
            totalBytesRead = 0L
            readCount = 0L
            hasSwitchedToStreamingTimeout = false
            TuneTelemetryTracker.shared.recordStage(TuneStage.HTSP_SUBSCRIBED)

            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open HtspDataSource", e)
            opened = false
            client = null
            HtspConnectionPool.releaseStreamingClient()
            throw (e as? IOException) ?: IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT

        val stream = client?.inputStream ?: return C.RESULT_END_OF_INPUT
        val debugEnabled = TvhSettings.isStreamingDebugCached
        val readStartMs = if (debugEnabled) System.currentTimeMillis() else 0L
        val bytesRead: Int = try {
            stream.read(buffer, offset, length)
        } catch (e: SocketTimeoutException) {
            if (opened) {
                val mode = if (totalBytesRead == 0L) "Initial tuner lock / stream arrival" else "Mid-stream read"
                Log.w(TAG, "$mode timed out: ${e.message}")
                throw IOException("Stalled HTSP stream timeout: ${e.message}", e)
            }
            -1
        } catch (e: SocketException) {
            if (opened) {
                Log.w(TAG, "Socket closed or interrupted during read: ${e.message}")
            }
            -1
        } catch (e: InterruptedIOException) {
            if (opened) {
                Log.w(TAG, "IO interrupted during read: ${e.message}")
            }
            -1
        } catch (e: Exception) {
            if (opened) {
                Log.e(TAG, "Error reading from HTSP inputStream", e)
            }
            -1
        }

        if (debugEnabled) {
            val readDurationMs = System.currentTimeMillis() - readStartMs
            if (readDurationMs > 200) {
                if (totalBytesRead < 2048L) {
                    Log.i(TAG, "TVH_STREAM_DEBUG: [TUNER LOCK] Server tuner lock / initial stream arrival wait took ${readDurationMs}ms")
                } else if (readDurationMs > 3000) {
                    Log.w(TAG, "TVH_STREAM_DEBUG: [READ STALL] Mid-stream TCP socket read stall: took ${readDurationMs}ms to return $bytesRead bytes!")
                } else {
                    Log.d(TAG, "TVH_STREAM_DEBUG: [LIVE STREAM PACING] Socket read wait: took ${readDurationMs}ms to return $bytesRead bytes")
                }
            }
        }

        if (bytesRead == -1) {
            if (TvhSettings.isStreamingDebugCached) {
                Log.d(TAG, "TVH_STREAM_DEBUG: HtspDataSource read EOF reached after $totalBytesRead total bytes ($readCount reads)")
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRead > 0) {
            if (totalBytesRead == 0L && bytesRead >= 4) {
                val b0 = buffer[offset].toInt() and 0xFF
                val b1 = buffer[offset + 1].toInt() and 0xFF
                val b2 = buffer[offset + 2].toInt() and 0xFF
                val b3 = buffer[offset + 3].toInt() and 0xFF
                val payloadLen = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                Log.i(TAG, "=== FIRST HTSP STREAM PACKET RECEIVED ($bytesRead bytes, first HTSP msg length=$payloadLen bytes) ===")
                TuneTelemetryTracker.shared.recordStage(TuneStage.FIRST_PACKET)
            } else if (readCount < 25) {
                Log.i(TAG, "HTSP stream read #${readCount + 1}: received $bytesRead bytes (total=$totalBytesRead)")
            }
            if (!hasSwitchedToStreamingTimeout) {
                hasSwitchedToStreamingTimeout = true
                client?.setSoTimeout(TvhConstants.MID_STREAM_TIMEOUT_MS)
            }
            totalBytesRead += bytesRead
            readCount++
            if (TvhSettings.isStreamingDebugCached) {
                val now = System.currentTimeMillis()
                if (lastStatTimeMs == 0L) {
                    lastStatTimeMs = now
                    lastStatBytes = totalBytesRead
                } else if (now - lastStatTimeMs >= 5000L) {
                    val deltaBytes = totalBytesRead - lastStatBytes
                    val deltaTimeMs = now - lastStatTimeMs
                    val kbps = if (deltaTimeMs > 0) (deltaBytes * 8L) / deltaTimeMs else 0L
                    Log.d(TAG, "TVH_STREAM_DEBUG: [STREAM STATS] Total: ${totalBytesRead / 1024} KB ($readCount reads), Current Bitrate: ${kbps} kbps")
                    lastStatTimeMs = now
                    lastStatBytes = totalBytesRead
                }
            }
            bytesTransferred(bytesRead)
        }
        return bytesRead
    }

    override fun getUri(): Uri? {
        return currentUri
    }

    override fun close() {
        if (opened) {
            opened = false
            if (TvhSettings.isStreamingDebugCached) {
                Log.i(TAG, "TVH_STREAM_DEBUG: Closing HtspDataSource ($currentUri). Session stats: $totalBytesRead total bytes read in $readCount reads.")
            } else {
                Log.i(TAG, "=== Closing HtspDataSource ($currentUri) ===")
            }
            try {
                client?.unsubscribe(subscriptionToken)
            } catch (e: Exception) {
                Log.w(TAG, "Error unsubscribing streaming client", e)
            }
            subscriptionToken = 0L
            client = null
            currentUri = null
            transferEnded()
        }
    }
}

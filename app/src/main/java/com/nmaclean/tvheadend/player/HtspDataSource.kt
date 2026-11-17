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
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.telemetry.TuneStage
import com.nmaclean.tvheadend.telemetry.TuneTelemetryTracker
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException

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
    private var opened = false
    private var currentUri: Uri? = null
    private var totalBytesRead = 0L
    private var readCount = 0L
    private var lastStatTimeMs = 0L
    private var lastStatBytes = 0L

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
            val activeClient = HtspConnectionPool.getStreamingClient(context, host, port, user, pass)

            // Ensure any prior active stream subscription on this connection is cleanly stopped and buffer flushed
            try {
                activeClient.unsubscribe()
            } catch (e: Exception) {
                Log.w(TAG, "Error unsubscribing prior stream: ${e.message}")
            }

            Log.i(TAG, "HTSP connected. Subscribing to channel $channelIdentifier with profile 'htsp'...")
            var subResult = activeClient.subscribe(channelIdentifier, "htsp", 1500L)

            if (subResult == null || subResult["error"] != null) {
                Log.w(TAG, "Subscription with profile 'htsp' failed or timed out. Retrying with default profile...")
                subResult = activeClient.subscribe(channelIdentifier, null, 1500L)
            }

            if (subResult == null || subResult["error"] != null) {
                val errorMsg = subResult?.get("error")?.toString() ?: "Unknown subscription error"
                HtspConnectionPool.releaseStreamingClient()
                throw IOException("HTSP subscription failed: $errorMsg")
            }

            client = activeClient
            activeClient.setSoTimeout(0) // Infinite timeout for streaming to prevent SocketTimeoutException during quiet periods or lulls
            opened = true
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
        val readStartMs = System.currentTimeMillis()
        val bytesRead: Int = try {
            stream.read(buffer, offset, length)
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

        val readDurationMs = System.currentTimeMillis() - readStartMs
        if (TvhSettings.isStreamingDebugCached && readDurationMs > 200) {
            if (totalBytesRead < 2048L) {
                Log.i(TAG, "TVH_STREAM_DEBUG: [TUNER LOCK] Server tuner lock / initial stream arrival wait took ${readDurationMs}ms")
            } else if (readDurationMs > 3000) {
                Log.w(TAG, "TVH_STREAM_DEBUG: [READ STALL] Mid-stream TCP socket read stall: took ${readDurationMs}ms to return $bytesRead bytes!")
            } else {
                Log.d(TAG, "TVH_STREAM_DEBUG: [LIVE STREAM PACING] Socket read wait: took ${readDurationMs}ms to return $bytesRead bytes")
            }
        }

        if (bytesRead == -1) {
            if (TvhSettings.isStreamingDebugCached) {
                Log.d(TAG, "TVH_STREAM_DEBUG: HtspDataSource read EOF reached after $totalBytesRead total bytes ($readCount reads)")
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRead > 0) {
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
                client?.unsubscribe()
            } catch (e: Exception) {
                Log.w(TAG, "Error unsubscribing streaming client", e)
            }
            client = null
            currentUri = null
            transferEnded()
        }
    }
}

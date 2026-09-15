package com.nmaclean.tvheadend

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

class HtspDataSource(private val context: Context) : BaseDataSource(true) {
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return HtspDataSource(context)
        }
    }

    companion object {
        const val TAG = "HtspDataSource"
    }

    private var client: HtspClient? = null
    private var currentPayload: ByteArray? = null
    private var payloadOffset = 0
    private var opened = false
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        Log.d(TAG, "=== HTSP DATASOURCE OPEN ===")
        currentUri = dataSpec.uri
        val channelIdentifier = currentUri?.lastPathSegment ?: currentUri?.host ?: "1"
        Log.d(TAG, "Subscribing to channel identifier: $channelIdentifier")

        val settings = TvhSettings(context)
        val host = settings.host
        val port = settings.htspPort
        val user = settings.username
        val pass = settings.password

        try {
            val newClient = HtspClient(host, port)
            if (!newClient.connect(user, pass)) {
                throw IOException("Failed to connect to HTSP server at $host:$port")
            }

            newClient.subscribe(channelIdentifier)
            client = newClient
            opened = true
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open HtspDataSource", e)
            throw IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0

        val currentClient = client ?: return C.RESULT_END_OF_INPUT

        try {
            if (currentPayload != null && payloadOffset < currentPayload!!.size) {
                val bytesToCopy = minOf(length, currentPayload!!.size - payloadOffset)
                System.arraycopy(currentPayload!!, payloadOffset, buffer, offset, bytesToCopy)
                payloadOffset += bytesToCopy
                bytesTransferred(bytesToCopy)
                return bytesToCopy
            }

            while (opened) {
                val msg = currentClient.readMessage() ?: return C.RESULT_END_OF_INPUT
                val method = msg["method"] as? String

                if (msg["error"] != null) {
                    Log.e(TAG, "Stream subscription error from server: ${msg["error"]}")
                    throw IOException("Stream subscription error: ${msg["error"]}")
                }

                if (method == "muxpkt") {
                    val payload = msg["payload"] as? ByteArray
                    if (payload != null && payload.isNotEmpty()) {
                        // Inspect first 16 bytes to check for MPEG-TS sync byte (0x47)
                        val headerSize = minOf(16, payload.size)
                        val headerBytes = payload.copyOfRange(0, headerSize)
                        val hexHeader = headerBytes.joinToString(" ") { java.lang.String.format("%02X", it) }
                        Log.d(TAG, "MUXPKT payload size=${payload.size}, first bytes (hex): [$hexHeader]")

                        currentPayload = payload
                        payloadOffset = 0

                        val bytesToCopy = minOf(length, payload.size)
                        System.arraycopy(payload, 0, buffer, offset, bytesToCopy)
                        payloadOffset += bytesToCopy
                        bytesTransferred(bytesToCopy)
                        return bytesToCopy
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from HTSP stream", e)
            throw IOException(e)
        }

        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? {
        return currentUri
    }

    override fun close() {
        if (opened) {
            opened = false
            Log.d(TAG, "Closing HtspDataSource and unsubscribing...")
            try {
                client?.unsubscribe()
                client?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing HtspDataSource", e)
            }
            client = null
            currentPayload = null
            payloadOffset = 0
            currentUri = null
            transferEnded()
        }
    }
}

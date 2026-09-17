package com.nmaclean.tvheadend

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.UnstableApi
import java.io.IOException

@UnstableApi
class HtspDataSource(private val context: Context) : BaseDataSource(true) {
    @UnstableApi
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return HtspDataSource(context)
        }
    }

    companion object {
        const val TAG = "HtspDataSource"
    }

    private var client: HtspClient? = null
    private var opened = false
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        currentUri = uri
        
        val channelIdentifier = uri.lastPathSegment ?: "1"
        Log.d(TAG, "=== HTSP DATASOURCE OPENING (NATIVE): $uri ===")

        val settings = TvhSettings(context)
        val host = uri.host ?: settings.host
        val port = if (uri.port != -1) uri.port else settings.htspPort
        val user = settings.username
        val pass = settings.password

        try {
            val newClient = HtspClient(host, port)
            if (!newClient.connect(user, pass)) {
                throw IOException("Authentication failed for HTSP connection")
            }

            Log.d(TAG, "HTSP connected. Subscribing to channel $channelIdentifier...")
            var subResult = newClient.subscribe(channelIdentifier, null)
            if (subResult == null || subResult["error"] != null) {
                Log.w(TAG, "Subscription with default profile failed: ${subResult?.get("error")}. Retrying with profile 'htsp'...")
                newClient.disconnect()
                if (newClient.connect(user, pass)) {
                    subResult = newClient.subscribe(channelIdentifier, "htsp")
                }
            }
            if (subResult == null || subResult["error"] != null) {
                Log.w(TAG, "Subscription with profile 'htsp' failed: ${subResult?.get("error")}. Retrying with profile 'pass'...")
                newClient.disconnect()
                if (newClient.connect(user, pass)) {
                    subResult = newClient.subscribe(channelIdentifier, "pass")
                }
            }
            if (subResult == null || subResult["error"] != null) {
                val errorMsg = subResult?.get("error")?.toString() ?: "Unknown subscription error"
                newClient.disconnect()
                throw IOException("HTSP subscription failed: $errorMsg")
            }

            client = newClient
            opened = true
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open HtspDataSource", e)
            opened = false
            client?.disconnect()
            client = null
            throw if (e is IOException) e else IOException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT

        val stream = client?.inputStream ?: return C.RESULT_END_OF_INPUT
        try {
            val bytesRead = stream.read(buffer, offset, length)
            if (bytesRead == -1) return C.RESULT_END_OF_INPUT
            bytesTransferred(bytesRead)
            return bytesRead
        } catch (e: IOException) {
            throw e
        }
    }

    override fun getUri(): Uri? {
        return currentUri
    }

    override fun close() {
        if (opened) {
            opened = false
            Log.d(TAG, "Closing HtspDataSource...")
            try {
                client?.unsubscribe()
                client?.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing client", e)
            }
            client = null
            currentUri = null
            transferEnded()
        }
    }
}

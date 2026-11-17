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
import com.nmaclean.tvheadend.model.TvhSettings
import java.io.IOException

/**
 * Media3 [DataSource] that streams Tvheadend DVR recordings directly over HTSP (`fileOpen`/`fileRead`/`fileClose`)
 * with random-access seek support (`htspdvr://<host>:<port>/<dvrId>`).
 */
@OptIn(UnstableApi::class)
class HtspDvrDataSource(private val context: Context) : BaseDataSource(true) {

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = HtspDvrDataSource(context)
    }

    companion object {
        private const val TAG = "HtspDvrDataSource"
        private const val MAX_READ_CHUNK = 256 * 1024
    }

    private var client: HtspClient? = null
    private var fileId: Long = -1L
    private var fileSize: Long = -1L
    private var readPosition: Long = 0L
    private var currentUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val uri = dataSpec.uri
        currentUri = uri
        val dvrId = uri.lastPathSegment?.toLongOrNull()
            ?: throw IOException("Invalid DVR URI: $uri")

        val settings = TvhSettings(context)
        val host = uri.host ?: settings.host
        val port = if (uri.port != -1) uri.port else settings.htspPort

        val htsp = HtspClient(host, port)
        if (!htsp.connect(settings.username, settings.password)) {
            htsp.disconnect()
            throw IOException("Failed to connect to HTSP server for DVR playback at $host:$port")
        }

        val openedFile = htsp.fileOpen("dvr/$dvrId")
        if (openedFile == null) {
            htsp.disconnect()
            throw IOException("HTSP fileOpen failed for dvr/$dvrId")
        }

        client = htsp
        fileId = openedFile.first
        fileSize = openedFile.second
        readPosition = dataSpec.position
        opened = true

        Log.i(TAG, "Opened HTSP DVR file dvr/$dvrId (fileId=$fileId, size=$fileSize, startPos=$readPosition)")
        transferStarted(dataSpec)

        return if (fileSize > 0L) {
            val remaining = (fileSize - readPosition).coerceAtLeast(0L)
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, remaining) else remaining
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT
        if (fileSize > 0L && readPosition >= fileSize) return C.RESULT_END_OF_INPUT

        val htsp = client ?: return C.RESULT_END_OF_INPUT
        val toRead = minOf(length, MAX_READ_CHUNK)
        val chunk = htsp.fileRead(fileId, readPosition, toRead)
            ?: throw IOException("HTSP fileRead failed at offset $readPosition")

        if (chunk.isEmpty()) return C.RESULT_END_OF_INPUT

        val copyLen = minOf(chunk.size, length)
        System.arraycopy(chunk, 0, buffer, offset, copyLen)
        readPosition += copyLen
        bytesTransferred(copyLen)
        return copyLen
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        if (opened) {
            opened = false
            try {
                if (fileId >= 0L) {
                    client?.fileClose(fileId)
                }
            } catch (_: Exception) {}
            try {
                client?.disconnect()
            } catch (_: Exception) {}
            client = null
            fileId = -1L
            currentUri = null
            transferEnded()
        }
    }
}

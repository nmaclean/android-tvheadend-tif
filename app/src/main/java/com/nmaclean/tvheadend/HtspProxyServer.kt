package com.nmaclean.tvheadend

import android.content.Context
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

class HtspProxyServer(private val context: Context, private val port: Int = 8999) {
    companion object {
        const val TAG = "HtspProxy"
    }

    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    fun start() {
        if (isRunning) return
        executor.execute {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.d(TAG, "Native HTSP Loopback Proxy started on port $port")

                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(TAG, "Error accepting client connection", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy server failed to start", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun handleClient(client: Socket) {
        var tvhSocket: Socket? = null
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val reader = input.bufferedReader()
            val requestLine = reader.readLine() ?: return
            Log.d(TAG, "Received proxy request: $requestLine")

            val uuidMatch = Regex("uuid=([^&\\s]+)").find(requestLine)
            val channelUuid = uuidMatch?.groupValues?.get(1) ?: "1"

            val prefs = context.getSharedPreferences("tvh_settings", Context.MODE_PRIVATE)
            val host = prefs.getString("host", "192.168.4.100") ?: "192.168.4.100"
            val htspPort = 9982
            val user = prefs.getString("user", "admin") ?: "admin"
            val pass = prefs.getString("pass", "ab1903") ?: "ab1903"

            tvhSocket = Socket(host, htspPort)
            val tvhOut = tvhSocket.getOutputStream()
            val tvhIn = tvhSocket.getInputStream()

            // 1. Handshake & Auth
            performHtspHandshake(tvhOut, tvhIn, user, pass)
            
            // 2. Subscribe using channel UUID/ID
            subscribeChannel(tvhOut, tvhIn, channelUuid)

            // 3. Send HTTP 200 OK headers to ExoPlayer
            val httpHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: video/mp2t\r\n" +
                    "Connection: close\r\n\r\n"
            output.write(httpHeader.toByteArray(StandardCharsets.UTF_8))
            output.flush()

            Log.d(TAG, "HTSP handshake & subscription active. Piping stream to ExoPlayer...")

            // 4. Stream loop
            val lenBuf = ByteArray(4)
            while (isRunning && !client.isClosed && !tvhSocket.isClosed) {
                if (!readFully(tvhIn, lenBuf, 4)) break
                val frameLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                        ((lenBuf[1].toInt() and 0xFF) shl 16) or
                        ((lenBuf[2].toInt() and 0xFF) shl 8) or
                        (lenBuf[3].toInt() and 0xFF)

                if (frameLen <= 0 || frameLen > 2 * 1024 * 1024) break

                val frameBuf = ByteArray(frameLen)
                if (!readFully(tvhIn, frameBuf, frameLen)) break

                // HTSP multiplex stream packet tag check (Tag 3 = multiplex data)
                val tag = frameBuf[0].toInt() and 0xFF
                if (tag == 3 || frameLen > 188) {
                    output.write(frameBuf, 1, frameLen - 1)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling proxy stream client", e)
        } finally {
            try { tvhSocket?.close() } catch (ignored: Exception) {}
            try { client.close() } catch (ignored: Exception) {}
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var bytesRead = 0
        while (bytesRead < length) {
            val read = input.read(buffer, bytesRead, length - bytesRead)
            if (read == -1) return false
            bytesRead += read
        }
        return true
    }

    private fun readHtspMessage(input: InputStream): ByteArray? {
        val lenBuf = ByteArray(4)
        if (!readFully(input, lenBuf, 4)) return null
        val frameLen = ((lenBuf[0].toInt() and 0xFF) shl 24) or
                ((lenBuf[1].toInt() and 0xFF) shl 16) or
                ((lenBuf[2].toInt() and 0xFF) shl 8) or
                (lenBuf[3].toInt() and 0xFF)
        if (frameLen <= 0 || frameLen > 65536) return null
        val frameBuf = ByteArray(frameLen)
        if (!readFully(input, frameBuf, frameLen)) return null
        return frameBuf
    }

    private fun performHtspHandshake(output: OutputStream, input: InputStream, user: String, pass: String) {
        val helloMsg = mapOf(
            "method" to "hello",
            "htspversion" to 34,
            "clientname" to "TvhAndroidInput"
        )
        writeBencodedMessage(output, helloMsg)
        readHtspMessage(input)

        val authMsg = mapOf(
            "method" to "authenticate",
            "username" to user,
            "password" to pass
        )
        writeBencodedMessage(output, authMsg)
        readHtspMessage(input)
    }

    private fun subscribeChannel(output: OutputStream, input: InputStream, channelIdentifier: String) {
        // Tvheadend accepts either integer channelId or string uuid/name depending on version
        val subMsg = if (channelIdentifier.all { it.isDigit() }) {
            mapOf("method" to "subscribe", "subscriptionId" to 1, "channelId" to channelIdentifier.toInt())
        } else {
            mapOf("method" to "subscribe", "subscriptionId" to 1, "channelUuid" to channelIdentifier)
        }
        writeBencodedMessage(output, subMsg)
        readHtspMessage(input)
    }

    private fun writeBencodedMessage(output: OutputStream, map: Map<String, Any>) {
        val sb = StringBuilder("d")
        for ((key, value) in map) {
            sb.append("${key.length}:$key")
            when (value) {
                is String -> sb.append("${value.length}:$value")
                is Int -> sb.append("i${value}e")
            }
        }
        sb.append("e")
        val data = sb.toString().toByteArray(StandardCharsets.UTF_8)
        
        val lengthHeader = ByteArray(4)
        lengthHeader[0] = ((data.size shr 24) and 0xFF).toByte()
        lengthHeader[1] = ((data.size shr 16) and 0xFF).toByte()
        lengthHeader[2] = ((data.size shr 8) and 0xFF).toByte()
        lengthHeader[3] = (data.size and 0xFF).toByte()

        output.write(lengthHeader)
        output.write(data)
        output.flush()
    }
}

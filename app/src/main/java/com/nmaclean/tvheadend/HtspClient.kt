package com.nmaclean.tvheadend

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class HtspClient(private val host: String, private val port: Int) {
    companion object {
        const val TAG = "HtspClient"
    }

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var sequenceNumber = 1

    fun connect(user: String, pass: String): Boolean {
        try {
            socket = Socket(host, port)
            socket?.soTimeout = 30000
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()

            val helloArgs = mapOf(
                "method" to "hello",
                "seq" to sequenceNumber++,
                "username" to user,
                "clientname" to "AndroidTV-TIFClient",
                "htspversion" to 34
            )
            sendMessage(helloArgs)
            val helloResp = readMessage() ?: return false

            val challenge = helloResp["challenge"] as? ByteArray
            val authArgs = mutableMapOf<String, Any>(
                "method" to "authenticate",
                "seq" to sequenceNumber++,
                "username" to user
            )

            if (challenge != null && pass.isNotEmpty()) {
                val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                sha1.update(pass.toByteArray(StandardCharsets.UTF_8))
                sha1.update(challenge)
                authArgs["digest"] = sha1.digest()
            } else if (pass.isNotEmpty()) {
                authArgs["password"] = pass
            }

            sendMessage(authArgs)
            val authResp = readMessage() ?: return false
            val noAccess = authResp["noaccess"] as? Number
            val error = authResp["error"] as? String
            return (authResp["success"] as? Boolean == true) || (noAccess == null && error == null)
        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
        }
        return false
    }

    fun fetchChannels(): List<TvhChannel> {
        val channels = mutableListOf<TvhChannel>()
        try {
            val seq = sequenceNumber++
            val args = mapOf(
                "method" to "enableAsyncMetadata",
                "seq" to seq,
                "channels" to 1,
                "epg" to 1
            )
            sendMessage(args)
            socket?.soTimeout = 3000

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 8000) {
                try {
                    val msg = readMessage() ?: continue
                    val method = msg["method"] as? String

                    if (msg.containsKey("channelId") || method == "channelAdd" || method == "channelUpdate") {
                        val id = (msg["channelId"] as? Number)?.toLong() ?: 0L
                        
                        // Check all possible string/uuid representations sent by Tvheadend
                        val uuidObj = msg["uuid"] ?: msg["confUuid"] ?: msg["channelIdStr"]
                        val uuid = when (uuidObj) {
                            is String -> uuidObj
                            is ByteArray -> String(uuidObj, StandardCharsets.UTF_8)
                            is Number -> uuidObj.toString()
                            else -> ""
                        }

                        val name = msg["channelName"] as? String ?: msg["name"] as? String ?: "Channel"
                        val number = (msg["channelNumber"] as? Number)?.toInt() ?: 0

                        if (uuid.isNotEmpty() || name.isNotEmpty()) {
                            val channel = TvhChannel(id, uuid, name, number)
                            if (!channels.any { it.id == id || (it.uuid.isNotEmpty() && it.uuid == uuid) }) {
                                channels.add(channel)
                                Log.d(TAG, "Parsed Channel -> id=$id, uuid/str='$uuid', name='$name', number=$number")
                            }
                        }
                    }

                    if (msg["initialSyncCompleted"] == true) break
                } catch (e: java.net.SocketTimeoutException) {
                    if (channels.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels", e)
        } finally {
            try { socket?.soTimeout = 30000 } catch (ignored: Exception) {}
        }
        return channels
    }

    fun fetchEvents(): List<TvhProgram> = emptyList()
    fun subscribe(channelIdentifier: Any): Map<String, Any?>? = null
    fun unsubscribe() {}

    @Synchronized
    fun sendMessage(map: Map<String, Any>) {
        val payload = Htsmsg.encode(map)
        outputStream?.write(payload)
        outputStream?.flush()
    }

    fun readMessage(): Map<String, Any?>? {
        val lenBytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val r = inputStream?.read(lenBytes, read, 4 - read) ?: -1
            if (r == -1) return null
            read += r
        }
        val length = ByteBuffer.wrap(lenBytes).int
        if (length <= 0 || length > 4 * 1024 * 1024) return null

        val payload = ByteArray(length)
        read = 0
        while (read < length) {
            val r = inputStream?.read(payload, read, length - read) ?: -1
            if (r == -1) return null
            read += r
        }
        return Htsmsg.decode(payload) as Map<String, Any?>?
    }

    fun disconnect() {
        try { socket?.close() } catch (ignored: Exception) {}
    }
}

data class TvhProgram(val eventId: Long, val channelId: Long, val title: String, val summary: String?, val startTime: Long, val stopTime: Long)

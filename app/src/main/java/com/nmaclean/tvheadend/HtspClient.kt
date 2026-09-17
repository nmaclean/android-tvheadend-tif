package com.nmaclean.tvheadend

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class HtspClient(private val host: String, private val port: Int) {
    companion object {
        const val TAG = "HtspClient"
    }

    private var socket: Socket? = null
    var inputStream: InputStream? = null
        private set
    private var outputStream: OutputStream? = null
    private var sequenceNumber = 1

    fun connect(user: String, pass: String): Boolean {
        try {
            val sock = Socket()
            socket = sock
            Log.d(TAG, "Connecting to host: '$host', port: $port")
            val targetHost = if (host.lowercase() == "localhost") "127.0.0.1" else host

            sock.receiveBufferSize = 2 * 1024 * 1024
            sock.sendBufferSize = 512 * 1024
            sock.tcpNoDelay = true

            sock.connect(InetSocketAddress(targetHost, port), 10000)
            sock.soTimeout = 60000
            inputStream = sock.getInputStream()
            outputStream = sock.getOutputStream()

            val helloArgs = mutableMapOf<String, Any>(
                "method" to "hello",
                "seq" to sequenceNumber++,
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
            if (e is IOException) {
                throw e
            } else {
                throw IOException(e)
            }
        }
    }

    // Fast, lightweight channel sync (epg = 0)
    fun fetchChannels(): List<TvhChannel> {
        val channels = mutableListOf<TvhChannel>()
        try {
            val seq = sequenceNumber++
            val args = mapOf(
                "method" to "enableAsyncMetadata",
                "seq" to seq,
                "channels" to 1,
                "epg" to 0
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

                        val uuidObj = msg["uuid"] ?: msg["confUuid"] ?: msg["channelIdStr"]
                        val uuid = when (uuidObj) {
                            is String -> uuidObj
                            is ByteArray -> String(uuidObj, StandardCharsets.UTF_8)
                            is Number -> uuidObj.toString()
                            else -> ""
                        }

                        val name = msg["channelName"] as? String ?: msg["name"] as? String ?: "Channel"
                        val channelNumberObj = msg["channelNumber"]
                        val number = (channelNumberObj as? Number)?.toInt() ?: 1
                        val channelIcon = msg["channelIcon"] as? String ?: msg["icon"] as? String ?: ""

                        if (uuid.isNotEmpty() || name.isNotEmpty()) {
                            Log.d(TAG, "Fetched channel from HTSP: name='$name', number=$number, channelId=$id, uuid='$uuid'")
                            val channel = TvhChannel(id, uuid, name, number, channelIcon)
                            if (!channels.any { it.id == id || (it.uuid.isNotEmpty() && it.uuid == uuid) }) {
                                channels.add(channel)
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

    // Dedicated asynchronous EPG events fetcher (channels = 0, epg = 1)
    fun fetchEvents(timeoutMs: Long = 8000): List<TvhProgram> {
        val programs = mutableListOf<TvhProgram>()
        try {
            val seq = sequenceNumber++
            val args = mapOf(
                "method" to "enableAsyncMetadata",
                "seq" to seq,
                "channels" to 0,
                "epg" to 1
            )
            sendMessage(args)
            socket?.soTimeout = 3000

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    val msg = readMessage() ?: continue
                    val method = msg["method"] as? String

                    if (method == "eventAdd" || method == "eventUpdate" || msg.containsKey("eventId")) {
                        val eventId = (msg["eventId"] as? Number)?.toLong() ?: 0L
                        val chId = (msg["channelId"] as? Number)?.toLong() ?: 0L
                        val title = msg["title"] as? String ?: "Unknown Program"
                        val summary = msg["description"] as? String ?: msg["summary"] as? String

                        val startSec = (msg["start"] as? Number)?.toLong() ?: 0L
                        val stopSec = (msg["stop"] as? Number)?.toLong() ?: 0L

                        val startTimeMs = if (startSec > 0) startSec * 1000 else System.currentTimeMillis()
                        val stopTimeMs = if (stopSec > 0) stopSec * 1000 else startTimeMs + 3600000

                        if (eventId > 0 && chId > 0) {
                            val program = TvhProgram(eventId, chId, title, summary, startTimeMs, stopTimeMs)
                            if (!programs.any { it.eventId == eventId }) {
                                programs.add(program)
                            }
                        }
                    }

                    if (msg["initialSyncCompleted"] == true) break
                } catch (e: java.net.SocketTimeoutException) {
                    if (programs.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching EPG events", e)
        } finally {
            try { socket?.soTimeout = 30000 } catch (ignored: Exception) {}
        }
        return programs
    }

    fun subscribe(channelIdentifier: Any, profile: String? = null): Map<String, Any?>? {
        val identifier = channelIdentifier.toString()
        val subMsg = mutableMapOf<String, Any>(
            "method" to "subscribe",
            "seq" to sequenceNumber++,
            "subscriptionId" to 1
        )

        if (identifier.all { it.isDigit() }) {
            subMsg["channelId"] = identifier.toLong()
        } else {
            subMsg["channelUuid"] = identifier
        }

        if (!profile.isNullOrEmpty()) {
            subMsg["profile"] = profile
        }

        Log.d(TAG, "Sending HTSP subscribe message: $subMsg")
        sendMessage(subMsg)
        val response = readMessage()
        Log.d(TAG, "Received HTSP subscribe response: $response")
        if (response == null || response["error"] != null) {
            Log.e(TAG, "HTSP subscribe failed for $identifier. Message sent: $subMsg. Response: $response")
        }
        return response
    }

    fun unsubscribe() {
        val unsubMsg = mapOf(
            "method" to "unsubscribe",
            "seq" to sequenceNumber++,
            "subscriptionId" to 1
        )
        sendMessage(unsubMsg)
    }

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
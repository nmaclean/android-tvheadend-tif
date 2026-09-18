package com.nmaclean.tvheadend

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Lightweight client for interacting with a Tvheadend server via the HTSP (Home TV Streaming Protocol).
 *
 * Implements [Closeable] / [AutoCloseable] to support Kotlin's `.use { ... }` pattern for automatic
 * socket resource management and leak prevention.
 *
 * @property host Hostname or IP address of the Tvheadend server.
 * @property port HTSP port number (default `9984`).
 */
class HtspClient(private val host: String, private val port: Int) : Closeable {
    companion object {
        const val TAG = "HtspClient"
    }

    private var socket: Socket? = null

    /**
     * Raw socket input stream exposing HTSP binary frames from the Tvheadend server.
     */
    var inputStream: InputStream? = null
        private set

    private var outputStream: OutputStream? = null
    private var sequenceNumber = 1

    /**
     * Connects to the Tvheadend server over TCP and performs the HTSP handshake and authentication.
     *
     * Sends the `hello` method, receives challenge bytes (if required), and verifies credentials
     * via SHA-1 digest or plain text authentication.
     *
     * @param user Username for Tvheadend server authentication.
     * @param pass Password for Tvheadend server authentication.
     * @return `true` if authentication succeeded and session is active; `false` otherwise.
     * @throws IOException If network or I/O errors occur during connection setup.
     */
    fun connect(user: String, pass: String): Boolean {
        try {
            val sock = Socket()
            socket = sock
            Log.i(TAG, "=== Connecting to HTSP server: '$host:$port' ===")
            val targetHost = if (host.lowercase() == "localhost") "127.0.0.1" else host

            sock.receiveBufferSize = TvhConstants.SOCKET_RECEIVE_BUFFER_SIZE
            sock.sendBufferSize = TvhConstants.SOCKET_SEND_BUFFER_SIZE
            sock.tcpNoDelay = true

            sock.connect(InetSocketAddress(targetHost, port), TvhConstants.SOCKET_CONNECT_TIMEOUT_MS)
            sock.soTimeout = TvhConstants.SOCKET_SO_TIMEOUT_MS
            inputStream = sock.getInputStream()
            outputStream = sock.getOutputStream()
            Log.i(TAG, "TCP Socket connected to $targetHost:$port")

            val helloArgs = mutableMapOf<String, Any>(
                "method" to "hello",
                "seq" to sequenceNumber++,
                "clientname" to TvhConstants.CLIENT_NAME,
                "htspversion" to TvhConstants.HTSP_VERSION
            )
            sendMessage(helloArgs)
            val helloResp = readMessage() ?: return false
            Log.i(TAG, "HTSP 'hello' response received: serverName='${helloResp["servername"]}', version='${helloResp["serverversion"]}', htspversion=${helloResp["htspversion"]}")

            val challenge = helloResp["challenge"] as? ByteArray
            val authArgs = mutableMapOf<String, Any>(
                "method" to "authenticate",
                "seq" to sequenceNumber++,
                "username" to user
            )

            if (challenge != null && pass.isNotEmpty()) {
                val sha1 = MessageDigest.getInstance("SHA-1")
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
            val success = (authResp["success"] as? Boolean == true) || (noAccess == null && error == null)
            Log.i(TAG, "HTSP authentication result for user '$user': success=$success, error=$error")
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Connection error on $host:$port", e)
            if (e is IOException) {
                throw e
            } else {
                throw IOException(e)
            }
        }
    }

    /**
     * Synchronously fetches the channel list from the Tvheadend server.
     *
     * Enables asynchronous channel metadata and listens for `channelAdd` / `channelUpdate` events
     * until initial synchronization completes or the timeout expires.
     *
     * @return List of [TvhChannel] objects populated from the server.
     */
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
            socket?.soTimeout = TvhConstants.ASYNC_READ_TIMEOUT_MS

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < TvhConstants.ASYNC_METADATA_TIMEOUT_MS) {
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
                            Log.i(TAG, "Fetched channel from HTSP: name='$name', number=$number, channelId=$id, uuid='$uuid'")
                            val channel = TvhChannel(id, uuid, name, number, channelIcon)
                            if (!channels.any { it.id == id || (it.uuid.isNotEmpty() && it.uuid == uuid) }) {
                                channels.add(channel)
                            }
                        }
                    }

                    if (msg["initialSyncCompleted"] == true) break
                } catch (e: SocketTimeoutException) {
                    if (channels.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels", e)
        } finally {
            try { socket?.soTimeout = TvhConstants.SOCKET_SO_TIMEOUT_MS } catch (ignored: Exception) {}
        }
        return channels.sortedWith(compareBy({ it.number }, { it.name }))
    }

    /**
     * Synchronously fetches Electronic Program Guide (EPG) events from the Tvheadend server.
     *
     * @param timeoutMs Maximum duration in milliseconds to wait for EPG events.
     * @return List of [TvhProgram] events collected during the sync period.
     */
    fun fetchEvents(timeoutMs: Long = TvhConstants.ASYNC_METADATA_TIMEOUT_MS): List<TvhProgram> {
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
            socket?.soTimeout = TvhConstants.ASYNC_READ_TIMEOUT_MS

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
                } catch (e: SocketTimeoutException) {
                    if (programs.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching EPG events", e)
        } finally {
            try { socket?.soTimeout = TvhConstants.SOCKET_SO_TIMEOUT_MS } catch (ignored: Exception) {}
        }
        return programs
    }

    /**
     * Subscribes to a channel stream on the Tvheadend server.
     *
     * @param channelIdentifier Channel numeric ID or UUID string.
     * @param profile Optional streaming profile name (e.g. `"htsp"`, `"pass"`).
     * @return Map containing response fields from the server, or `null` if subscription failed.
     */
    fun subscribe(channelIdentifier: Any, profile: String? = null): Map<String, Any?>? {
        val identifier = channelIdentifier.toString()
        val subMsg = mutableMapOf<String, Any>(
            "method" to "subscribe",
            "seq" to sequenceNumber++,
            "subscriptionId" to 1,
            "weight" to 100
        )

        if (identifier.all { it.isDigit() }) {
            subMsg["channelId"] = identifier.toLong()
        } else {
            subMsg["channelUuid"] = identifier
        }

        if (!profile.isNullOrEmpty()) {
            subMsg["profile"] = profile
        }

        Log.i(TAG, "Sending HTSP subscribe message: $subMsg")
        sendMessage(subMsg)
        val response = readMessage()
        Log.i(TAG, "Received HTSP subscribe response: $response")
        if (response == null || response["error"] != null) {
            Log.e(TAG, "HTSP subscribe failed for $identifier. Message sent: $subMsg. Response: $response")
        }
        return response
    }

    /**
     * Unsubscribes from the current channel subscription.
     */
    fun unsubscribe() {
        val unsubMsg = mapOf(
            "method" to "unsubscribe",
            "seq" to sequenceNumber++,
            "subscriptionId" to 1
        )
        sendMessage(unsubMsg)
    }

    /**
     * Encodes and writes a map representation of an HTSP message to the output socket stream.
     *
     * @param map Key-value pairs comprising the HTSP message.
     */
    @Synchronized
    fun sendMessage(map: Map<String, Any>) {
        val payload = Htsmsg.encode(map)
        outputStream?.write(payload)
        outputStream?.flush()
    }

    /**
     * Reads a single length-prefixed HTSP message from the socket input stream and decodes it.
     *
     * @return Decoded key-value map representing the HTSP message, or `null` if end-of-stream occurs.
     */
    fun readMessage(): Map<String, Any?>? {
        val lenBytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val r = inputStream?.read(lenBytes, read, 4 - read) ?: -1
            if (r == -1) return null
            read += r
        }
        val length = ByteBuffer.wrap(lenBytes).int
        if (length <= 0 || length > TvhConstants.MAX_HTSP_MESSAGE_LENGTH) return null

        val payload = ByteArray(length)
        read = 0
        while (read < length) {
            val r = inputStream?.read(payload, read, length - read) ?: -1
            if (r == -1) return null
            read += r
        }
        return Htsmsg.decode(payload) as Map<String, Any?>?
    }

    /**
     * Closes the active socket connection and releases stream resources.
     */
    fun disconnect() {
        try { inputStream?.close() } catch (ignored: Exception) {}
        try { outputStream?.close() } catch (ignored: Exception) {}
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    /**
     * AutoCloseable implementation delegating to [disconnect] for use with `.use { ... }`.
     */
    override fun close() {
        disconnect()
    }
}

/**
 * Data class representing an Electronic Program Guide (EPG) event.
 *
 * @property eventId Unique event identifier on Tvheadend.
 * @property channelId Associated Tvheadend channel ID.
 * @property title Program title.
 * @property summary Short program description or summary.
 * @property startTime UTC start time in milliseconds.
 * @property stopTime UTC stop time in milliseconds.
 */
data class TvhProgram(val eventId: Long, val channelId: Long, val title: String, val summary: String?, val startTime: Long, val stopTime: Long)

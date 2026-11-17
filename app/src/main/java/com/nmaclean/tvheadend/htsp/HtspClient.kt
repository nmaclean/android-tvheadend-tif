package com.nmaclean.tvheadend.htsp

import android.util.Log
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhConstants
import java.io.BufferedInputStream
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
 */
class HtspClient(private val host: String, private val port: Int) : Closeable {

    companion object {
        const val TAG = "HtspClient"
    }

    private var socket: Socket? = null
    private val stateLogger = HtspStateLogger(host, port)

    val connectionState: HtspConnectionState
        get() = stateLogger.currentState

    /**
     * User name used for the current authenticated HTSP session, or null if not authenticated.
     */
    var authenticatedUser: String? = null
        private set

    val isConnected: Boolean
        get() {
            val s = socket ?: return false
            return s.isConnected && !s.isClosed && inputStream != null && authenticatedUser != null
        }

    /**
     * Raw socket input stream exposing HTSP binary frames from the Tvheadend server.
     */
    var inputStream: InputStream? = null
        private set

    private var outputStream: OutputStream? = null
    private var sequenceNumber = 1

    /**
     * Connects to the Tvheadend server over TCP and performs the HTSP handshake and authentication.
     */
    fun connect(user: String, pass: String): Boolean {
        try {
            stateLogger.transitionTo(HtspConnectionState.CONNECTING)
            val sock = Socket()
            socket = sock

            Log.i(TAG, "=== Connecting to HTSP server: '$host:$port' ===")
            val targetHost = if (host.lowercase() == "localhost") "127.0.0.1" else host

            sock.receiveBufferSize = TvhConstants.SOCKET_RECEIVE_BUFFER_SIZE
            sock.sendBufferSize = TvhConstants.SOCKET_SEND_BUFFER_SIZE
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(targetHost, port), TvhConstants.SOCKET_CONNECT_TIMEOUT_MS)
            sock.soTimeout = TvhConstants.SOCKET_SO_TIMEOUT_MS

            inputStream = BufferedInputStream(sock.getInputStream(), 128 * 1024)
            outputStream = sock.getOutputStream()

            Log.i(TAG, "TCP Socket connected to $targetHost:$port")

            val helloSeq = sequenceNumber++
            val helloArgs = mutableMapOf<String, Any>(
                "method" to "hello",
                "seq" to helloSeq,
                "clientname" to TvhConstants.CLIENT_NAME,
                "htspversion" to TvhConstants.HTSP_VERSION
            )
            Log.d(TAG, "Sending 'hello' request: $helloArgs")
            sendMessage(helloArgs)

            val helloResp = readResponse(helloSeq.toLong())
            if (helloResp == null) {
                stateLogger.transitionTo(HtspConnectionState.DISCONNECTED, reason = "hello response null")
                return false
            }
            Log.i(TAG, "HTSP 'hello' response received: serverName='${helloResp["servername"]}', version='${helloResp["serverversion"]}', htspversion=${helloResp["htspversion"]}")

            stateLogger.transitionTo(HtspConnectionState.AUTHENTICATING)
            val challenge = helloResp["challenge"] as? ByteArray
            val authSeq = sequenceNumber++
            val authArgs = mutableMapOf<String, Any>(
                "method" to "authenticate",
                "seq" to authSeq,
                "username" to user
            )

            if (challenge != null && pass.isNotEmpty()) {
                val sha1 = MessageDigest.getInstance("SHA-1")
                sha1.update(pass.toByteArray(StandardCharsets.UTF_8))
                sha1.update(challenge)
                authArgs["digest"] = sha1.digest() as Any
            } else if (pass.isNotEmpty()) {
                authArgs["password"] = pass
            }

            Log.d(TAG, "Sending 'authenticate' request for user '$user'")
            sendMessage(authArgs)
            val authResp = readResponse(authSeq.toLong())
            if (authResp == null) {
                stateLogger.transitionTo(HtspConnectionState.DISCONNECTED, reason = "authenticate response null")
                return false
            }

            val noAccess = authResp["noaccess"] as? Number
            val error = authResp["error"] as? String
            val success = (authResp["success"] as? Boolean == true) || (noAccess == null && error == null)

            if (success) {
                authenticatedUser = user
                stateLogger.transitionTo(HtspConnectionState.CONNECTED)
            } else {
                stateLogger.transitionTo(
                    HtspConnectionState.DISCONNECTED,
                    reason = error ?: "authentication rejected (noaccess=$noAccess)"
                )
            }

            Log.i(TAG, "HTSP authentication result for user '$user': success=$success, error=$error, authResp=$authResp")
            return success

        } catch (e: Exception) {
            stateLogger.transitionTo(
                HtspConnectionState.DISCONNECTED,
                reason = e.message ?: e::class.java.simpleName
            )
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
            Log.i(TAG, "Sending enableAsyncMetadata request: $args")
            sendMessage(args)

            socket?.soTimeout = TvhConstants.ASYNC_READ_TIMEOUT_MS
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < TvhConstants.ASYNC_METADATA_TIMEOUT_MS) {
                try {
                    val msg = readMessage()
                    if (msg == null) {
                        Log.w(TAG, "readMessage returned null frame")
                        continue
                    }

                    val method = msg["method"] as? String
                    Log.d(TAG, "HTSP fetchChannels frame received: method='$method', keys=${msg.keys}")

                    if (msg.containsKey("noaccess") || msg.containsKey("error")) {
                        Log.e(TAG, "HTSP server returned access error in fetchChannels: noaccess=${msg["noaccess"]}, error=${msg["error"]}")
                    }

                    if (msg.containsKey("channelId") || method == "channelAdd" || method == "channelUpdate") {
                        val id = (msg["channelId"] as? Number)?.toLong() ?: 0L
                        val uuidObj = msg["uuid"] ?: msg["confUuid"]
                        val uuid = when (uuidObj) {
                            is String -> uuidObj
                            is ByteArray -> String(uuidObj, StandardCharsets.UTF_8)
                            is Number -> uuidObj.toString()
                            else -> ""
                        }
                        val name = msg["channelName"] as? String ?: msg["name"] as? String ?: "Channel"
                        val channelNumberObj = msg["channelNumber"]
                        val rawNum = (channelNumberObj as? Number)?.toLong() ?: 1L
                        val number = if (rawNum < 0) (rawNum and 0xFFFFL).toInt() else rawNum.toInt()
                        val channelIcon = msg["channelIcon"] as? String ?: msg["icon"] as? String ?: ""

                        Log.i(TAG, "Parsing channel frame: name='$name', number=$number, channelId=$id, uuid='$uuid', icon='$channelIcon'")

                        if (uuid.isNotEmpty() || name.isNotEmpty() || id > 0) {
                            val finalUuid = if (uuid.isNotEmpty()) uuid else id.toString()
                            val channel = TvhChannel(id, finalUuid, name, number, channelIcon, "")
                            if (!channels.any { it.id == id || (it.uuid.isNotEmpty() && it.uuid == finalUuid) }) {
                                channels.add(channel)
                                Log.i(TAG, "Added channel to list: ${channel.name} (#${channel.number}, id=${channel.id})")
                            }
                        } else {
                            Log.w(TAG, "Skipped invalid channel frame (empty uuid, name, and id): $msg")
                        }
                    }

                    if (msg["initialSyncCompleted"] == true || msg["initialSyncCompleted"] == 1) {
                        Log.i(TAG, "HTSP initialSyncCompleted signal received!")
                        break
                    }
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "SocketTimeoutException reading channel stream. Current count: ${channels.size}")
                    if (channels.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching channels", e)
        } finally {
            try {
                socket?.soTimeout = TvhConstants.SOCKET_SO_TIMEOUT_MS
            } catch (ignored: Exception) {}
        }
        Log.i(TAG, "fetchChannels completed: returning ${channels.size} channels")
        return channels.sortedWith(compareBy({ it.number }, { it.name }))
    }

    /**
     * Synchronously fetches Electronic Program Guide (EPG) events from the Tvheadend server.
     */
    fun fetchEvents(timeoutMs: Long = TvhConstants.ASYNC_METADATA_TIMEOUT_MS, filterStartMs: Long = 0L, filterEndMs: Long = 0L): List<TvhProgram> {
        val programs = mutableListOf<TvhProgram>()
        try {
            val seq = sequenceNumber++
            val args = mapOf(
                "method" to "enableAsyncMetadata",
                "seq" to seq,
                "channels" to 0,
                "epg" to 1,
                "epgMaxDays" to 3
            )
            Log.i(TAG, "Sending enableAsyncMetadata for EPG: $args")
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

                        val inWindow = if (filterStartMs > 0 && filterEndMs > 0) {
                            stopTimeMs >= filterStartMs && startTimeMs <= filterEndMs
                        } else true

                        if (eventId > 0 && chId > 0 && inWindow) {
                            val program = TvhProgram(eventId, chId, title, summary, startTimeMs, stopTimeMs)
                            if (!programs.any { it.eventId == eventId }) {
                                programs.add(program)
                            }
                        }
                    }

                    if (msg["initialSyncCompleted"] == true || msg["initialSyncCompleted"] == 1) break
                } catch (e: SocketTimeoutException) {
                    if (programs.isNotEmpty()) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching EPG events", e)
        } finally {
            try {
                socket?.soTimeout = TvhConstants.SOCKET_SO_TIMEOUT_MS
            } catch (ignored: Exception) {}
        }
        Log.i(TAG, "fetchEvents completed: returning ${programs.size} EPG programs")
        return programs
    }

    /**
     * Subscribes to a channel stream on the Tvheadend server.
     */
    @Synchronized
    fun subscribe(channelIdentifier: Any, profile: String? = null, timeoutMs: Long = 1500L): Map<String, Any?>? {
        val identifier = channelIdentifier.toString()
        val seq = sequenceNumber++
        val subMsg = mutableMapOf<String, Any?>(
            "method" to "subscribe",
            "seq" to seq,
            "subscriptionId" to 1,
            "weight" to 100,
            "faststart" to 1,
            "timeshift" to 1,
            "normts" to 1
        )

        val chIdLong = identifier.toLongOrNull()
        if (chIdLong == null && ChannelRegistry.getAllChannels().isEmpty()) {
            try {
                val channels = fetchChannels()
                if (channels.isNotEmpty()) {
                    ChannelRegistry.updateChannels(channels)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to auto-populate ChannelRegistry during subscribe: ${e.message}")
            }
        }

        val resolvedChannel = if (chIdLong != null) {
            ChannelRegistry.getChannelById(chIdLong)
        } else {
            ChannelRegistry.getAllChannels().firstOrNull {
                it.uuid.equals(identifier, ignoreCase = true) || it.name.equals(identifier, ignoreCase = true)
            }
        }

        if (resolvedChannel != null) {
            subMsg["channelId"] = resolvedChannel.id
            Log.i(TAG, "Resolved subscription identifier '$channelIdentifier' to channelId=${resolvedChannel.id} ('${resolvedChannel.name}')")
        } else if (chIdLong != null) {
            subMsg["channelId"] = chIdLong
        } else {
            subMsg["channelName"] = identifier
        }

        if (!profile.isNullOrEmpty()) {
            subMsg["profile"] = profile
        }

        Log.i(TAG, "Sending HTSP subscribe message: $subMsg")
        sendMessage(subMsg)
        currentSubscriptionToken++
        hasActiveSubscription = true
        return mapOf("success" to true)
    }

    @Volatile
    var hasActiveSubscription: Boolean = false
        private set

    @Volatile
    var currentSubscriptionToken: Long = 0L
        private set

    /**
     * Unsubscribes from the current channel subscription.
     * Note: Tvheadend handles 'unsubscribe' by stopping stream transmission and sending an async
     * 'subscriptionStop' notification (which has no sequence number). 'unsubscribe' is therefore
     * fire-and-forget; the subsequent 'subscribe' call will read its own synchronous response.
     *
     * @param expectedToken When non-null, only unsubscribes if [currentSubscriptionToken] still matches,
     * preventing a delayed close() from an old DataSource from tearing down a newer subscription.
     */
    @Synchronized
    @JvmOverloads
    fun unsubscribe(expectedToken: Long? = null) {
        if (expectedToken != null && expectedToken != currentSubscriptionToken) return
        if (!hasActiveSubscription) return
        hasActiveSubscription = false
        val seq = sequenceNumber++
        val unsubMsg = mapOf(
            "method" to "unsubscribe",
            "seq" to seq,
            "subscriptionId" to 1
        )
        sendMessage(unsubMsg)
        val drainedCleanly = drainSocketBuffer(300)
        if (!drainedCleanly) {
            flushInputStream()
        }
    }

    /**
     * Completely flushes and discards any remaining bytes buffered in the socket input stream.
     */
    @Synchronized
    fun flushInputStream() {
        val sock = socket ?: return
        val stream = inputStream ?: return
        val origTimeout = try { sock.soTimeout } catch (_: Exception) { 0 }
        try {
            sock.soTimeout = 10
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
            }
        } catch (_: SocketTimeoutException) {
            // Expected when socket buffer is fully drained
        } catch (_: Exception) {
        } finally {
            try { sock.soTimeout = origTimeout } catch (_: Exception) {}
        }
    }

    private fun drainSocketBuffer(maxWaitMs: Long): Boolean {
        val sock = socket ?: return false
        val origTimeout = try { sock.soTimeout } catch (_: Exception) { 0 }
        try {
            sock.soTimeout = 50
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < maxWaitMs) {
                val msg = readMessage() ?: return false
                if (msg["method"] == "subscriptionStop" || (msg["seq"] as? Number)?.toLong() == (sequenceNumber - 1).toLong()) {
                    return true
                }
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: Exception) {
        } finally {
            try { sock.soTimeout = origTimeout } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Reads HTSP messages from the socket input stream until a message with matching [expectedSeq] is received,
     * or until [timeoutMs] elapses. Any asynchronous stream messages (such as stale 'muxpkt' or previous
     * 'unsubscribe' responses) encountered while waiting are safely discarded.
     */
    @Synchronized
    fun readResponse(expectedSeq: Long, timeoutMs: Long = TvhConstants.SOCKET_SO_TIMEOUT_MS.toLong()): Map<String, Any?>? {
        val startTime = System.currentTimeMillis()
        val origTimeout = try { socket?.soTimeout ?: 0 } catch (_: Exception) { 0 }

        try {
            socket?.soTimeout = timeoutMs.toInt()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                @Suppress("UNCHECKED_CAST")
                val msg = readMessage() as? Map<String, Any?> ?: continue
                val seq = (msg["seq"] as? Number)?.toLong()
                val method = msg["method"] as? String
                if (seq == expectedSeq) {
                    return msg
                } else if (method == "subscriptionStart" || method == "muxpkt" || method == "weight") {
                    Log.i(TAG, "readResponse: Received stream message '$method' while waiting for seq=$expectedSeq. Treating subscription as successful!")
                    return mapOf("success" to true, "inferred" to true, "method" to method)
                } else {
                    Log.d(TAG, "readResponse: Discarding async/stale HTSP message (seq=$seq, expected=$expectedSeq, method='$method')")
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Timeout waiting for HTSP response with seq=$expectedSeq")
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for HTSP response with seq=$expectedSeq", e)
        } finally {
            try { socket?.soTimeout = origTimeout } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Encodes and writes a map representation of an HTSP message to the output socket stream.
     */
    @Synchronized
    fun sendMessage(map: Map<String, Any?>) {
        val payload = Htsmsg.encode(map)
        outputStream?.write(payload)
        outputStream?.flush()
    }

    /**
     * Reads a single length-prefixed HTSP message from the socket input stream and decodes it.
     * Large media payload frames (> 8192 bytes, which are always 'muxpkt' streaming data)
     * are skipped instantly without allocating buffers or parsing binary HTSMSG structures.
     */
    @Synchronized
    fun readMessage(): Map<*, *>? {
        val lenBytes = ByteArray(4)
        var read = 0
        while (read < 4) {
            val r = inputStream?.read(lenBytes, read, 4 - read) ?: -1
            if (r == -1) return null
            read += r
        }

        val length = ByteBuffer.wrap(lenBytes).int
        if (length <= 0 || length > TvhConstants.MAX_HTSP_MESSAGE_LENGTH) return null

        if (length > 8192) {
            // Fast-skip large media stream packet (muxpkt) payload without decoding Htsmsg map
            skipBytes(length)
            return mapOf<String, Any?>("method" to "muxpkt", "skipped" to true)
        }

        val payload = ByteArray(length)
        read = 0
        while (read < length) {
            val r = inputStream?.read(payload, read, length - read) ?: -1
            if (r == -1) return null
            read += r
        }

        val decoded = Htsmsg.decode(payload) as Map<*, *>?
        Log.v(TAG, "readMessage frame decoded (${length}b): method='${decoded?.get("method")}', keys=${decoded?.keys}")
        return decoded
    }

    private fun skipBytes(length: Int) {
        val buffer = ByteArray(minOf(length, 8192))
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size)
            val r = inputStream?.read(buffer, 0, toRead) ?: -1
            if (r == -1) break
            remaining -= r
        }
    }

    /**
     * Sets the socket read timeout (soTimeout). Use 0 for infinite timeout during streaming.
     */
    fun setSoTimeout(timeoutMs: Int) {
        try {
            socket?.soTimeout = timeoutMs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set socket timeout to $timeoutMs", e)
        }
    }

    /**
     * Closes the active socket connection and releases stream resources.
     */
    fun disconnect() {
        currentSubscriptionToken++
        hasActiveSubscription = false
        authenticatedUser = null
        try { inputStream?.close() } catch (ignored: Exception) {}
        try { outputStream?.close() } catch (ignored: Exception) {}
        try { socket?.close() } catch (ignored: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
        if (stateLogger.currentState != HtspConnectionState.DISCONNECTED) {
            stateLogger.transitionTo(HtspConnectionState.DISCONNECTED, reason = "disconnect")
        }
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
 */
data class TvhProgram(
    val eventId: Long,
    val channelId: Long,
    val title: String,
    val summary: String?,
    val startTime: Long,
    val stopTime: Long
)

package com.nmaclean.tvheadend.htsp

import android.content.Context
import android.util.Log
import com.nmaclean.tvheadend.model.TvhSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Singleton connection pool that maintains a persistent, authenticated HTSP socket to the Tvheadend server.
 * Enables instant in-place channel switching without TCP handshake or authentication re-negotiation overhead.
 */
object HtspConnectionPool {
    private const val TAG = "HtspConnectionPool"
    private const val MAX_CONNECT_ATTEMPTS = 3
    private const val INITIAL_BACKOFF_MS = 250L

    private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamingClient: HtspClient? = null
    private var controlClient: HtspClient? = null

    fun setStreamingSpeedAsync(speed: Int) {
        val client = synchronized(this) { streamingClient } ?: return
        poolScope.launch {
            client.sendSubscriptionSpeed(speed)
        }
    }

    @Synchronized
    fun getFreshStreamingClient(
        context: Context,
        host: String,
        port: Int,
        user: String,
        pass: String
    ): HtspClient {
        Log.i(TAG, "Bypassing connection reuse: releasing existing socket and establishing fresh streaming connection")
        releaseStreamingClient()
        return getStreamingClient(context, host, port, user, pass)
    }

    @Synchronized
    fun getStreamingClient(
        context: Context,
        host: String,
        port: Int,
        user: String,
        pass: String
    ): HtspClient {
        val targetHost = if (host.equals("localhost", ignoreCase = true)) "127.0.0.1" else host
        val existing = streamingClient

        if (existing != null &&
            existing.isConnected &&
            existing.host == targetHost &&
            existing.port == port &&
            existing.authenticatedUser == user
        ) {
            Log.i(TAG, "Reusing existing authenticated HTSP streaming connection to $targetHost:$port for user '$user'")
            return existing
        }

        releaseStreamingClient()

        Log.i(TAG, "Establishing fresh HTSP streaming connection to $targetHost:$port")
        val stateLogger = HtspStateLogger(targetHost, port)
        val client = connectWithBackoff(
            host = targetHost,
            port = port,
            maxAttempts = MAX_CONNECT_ATTEMPTS,
            initialBackoffMs = INITIAL_BACKOFF_MS,
            stateLogger = stateLogger,
            sleeper = { delayMs -> Thread.sleep(delayMs) }
        ) {
            val candidate = HtspClient(targetHost, port)
            if (!candidate.connect(user, pass)) {
                candidate.disconnect()
                throw IOException("Failed to connect/authenticate to HTSP server at $targetHost:$port")
            }
            candidate
        }

        streamingClient = client
        return client
    }

    @Synchronized
    fun getControlClient(
        context: Context,
        host: String,
        port: Int,
        user: String,
        pass: String
    ): HtspClient {
        val targetHost = if (host.equals("localhost", ignoreCase = true)) "127.0.0.1" else host
        val existing = controlClient

        if (existing != null &&
            existing.isConnected &&
            existing.host == targetHost &&
            existing.port == port &&
            existing.authenticatedUser == user
        ) {
            Log.i(TAG, "Reusing existing authenticated HTSP control connection to $targetHost:$port for user '$user'")
            return existing
        }

        try {
            existing?.disconnect()
        } catch (_: Exception) {}

        Log.i(TAG, "Establishing fresh HTSP control connection to $targetHost:$port")
        val stateLogger = HtspStateLogger(targetHost, port)
        val client = connectWithBackoff(
            host = targetHost,
            port = port,
            maxAttempts = MAX_CONNECT_ATTEMPTS,
            initialBackoffMs = INITIAL_BACKOFF_MS,
            stateLogger = stateLogger,
            sleeper = { delayMs -> Thread.sleep(delayMs) }
        ) {
            val candidate = HtspClient(targetHost, port)
            if (!candidate.connect(user, pass)) {
                candidate.disconnect()
                throw IOException("Failed to connect/authenticate control client to HTSP server at $targetHost:$port")
            }
            candidate
        }

        controlClient = client
        return client
    }

    internal fun <T> connectWithBackoff(
        host: String,
        port: Int,
        maxAttempts: Int = MAX_CONNECT_ATTEMPTS,
        initialBackoffMs: Long = INITIAL_BACKOFF_MS,
        stateLogger: HtspStateLogger = HtspStateLogger(host, port),
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        attemptConnect: () -> T
    ): T {
        var lastError: IOException? = null
        for (attempt in 1..maxAttempts) {
            try {
                stateLogger.transitionTo(
                    HtspConnectionState.CONNECTING,
                    attempt = attempt,
                    maxAttempts = maxAttempts
                )
                val result = attemptConnect()
                stateLogger.transitionTo(
                    HtspConnectionState.CONNECTED,
                    attempt = attempt,
                    maxAttempts = maxAttempts
                )
                return result
            } catch (e: IOException) {
                lastError = e
                if (attempt < maxAttempts) {
                    val backoffMs = initialBackoffMs * (1L shl (attempt - 1))
                    stateLogger.transitionTo(
                        HtspConnectionState.BACKOFF_RETRY,
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        backoffMs = backoffMs,
                        reason = e.message ?: "connection failed"
                    )
                    try {
                        sleeper(backoffMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        stateLogger.transitionTo(
                            HtspConnectionState.DISCONNECTED,
                            attempt = attempt,
                            maxAttempts = maxAttempts,
                            reason = "retry interrupted"
                        )
                        throw IOException("HTSP reconnect interrupted", ie)
                    }
                } else {
                    stateLogger.transitionTo(
                        HtspConnectionState.DISCONNECTED,
                        attempt = attempt,
                        maxAttempts = maxAttempts,
                        reason = e.message ?: "max retries exhausted"
                    )
                }
            }
        }
        throw lastError ?: IOException("Failed to connect to HTSP server at $host:$port")
    }

    @Synchronized
    fun releaseStreamingClient() {
        val client = streamingClient
        streamingClient = null
        if (client != null) {
            poolScope.launch {
                try {
                    client.unsubscribe()
                    client.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing streaming client in background", e)
                }
            }
        }
    }

    /**
     * Runs [block] with a dedicated authenticated HTSP control socket [HtspClient] for DVR operations,
     * keeping the persistent live-streaming socket untouched.
     */
    inline fun <T> withClient(context: Context, tag: String = "HtspConnectionPool", block: (HtspClient) -> T): T? {
        val settings = TvhSettings(context)
        if (settings.host.isBlank()) return null
        val client = try {
            getControlClient(context, settings.host, settings.htspPort, settings.username, settings.password)
        } catch (e: Exception) {
            Log.w(tag, "withClient failed to acquire control client: ${e.message}")
            return null
        }
        return try {
            block(client)
        } catch (e: Exception) {
            Log.w(tag, "withClient execution error: ${e.message}")
            null
        }
    }

    /**
     * Asynchronously pre-connects and authenticates the persistent HTSP socket in the background.
     */
    fun preconnectAsync(context: Context) {
        poolScope.launch {
            try {
                val settings = TvhSettings(context)
                val host = settings.host
                val port = settings.htspPort
                val user = settings.username
                val pass = settings.password
                if (host.isNotEmpty()) {
                    getStreamingClient(context, host, port, user, pass)
                }
            } catch (e: Exception) {
                Log.w(TAG, "preconnectAsync failed (will connect on tune): ${e.message}")
            }
        }
    }
}

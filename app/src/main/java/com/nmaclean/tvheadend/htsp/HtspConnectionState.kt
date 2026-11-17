package com.nmaclean.tvheadend.htsp

import android.util.Log
import com.nmaclean.tvheadend.model.TvhSettings

/**
 * Lifecycle states for an HTSP socket connection and its reconnection backoff loop.
 */
enum class HtspConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    BACKOFF_RETRY
}

/**
 * Structured state-transition logger for the HTSP connection/reconnection lifecycle.
 */
class HtspStateLogger(
    private val host: String,
    private val port: Int,
    private val isDebugEnabled: () -> Boolean = { TvhSettings.isStreamingDebugCached },
    private val logger: (tag: String, message: String) -> Unit = { tag, msg -> Log.i(tag, msg) }
) {
    companion object {
        const val TAG = "HtspState"
    }

    @Volatile
    var currentState: HtspConnectionState = HtspConnectionState.DISCONNECTED
        private set

    @Synchronized
    fun transitionTo(
        nextState: HtspConnectionState,
        attempt: Int? = null,
        maxAttempts: Int? = null,
        backoffMs: Long? = null,
        reason: String? = null
    ) {
        val prevState = currentState
        currentState = nextState
        if (!isDebugEnabled()) return
        val details = buildString {
            append("TVH_STREAM_DEBUG: [HTSP_STATE] ")
            append(prevState)
            append(" -> ")
            append(nextState)
            append(" host=").append(host).append(':').append(port)
            if (attempt != null) {
                append(" attempt=").append(attempt)
                if (maxAttempts != null) {
                    append('/').append(maxAttempts)
                }
            }
            if (backoffMs != null) {
                append(" backoffMs=").append(backoffMs)
            }
            if (!reason.isNullOrBlank()) {
                append(" reason=\"").append(reason).append('"')
            }
        }
        logger(TAG, details)
    }
}

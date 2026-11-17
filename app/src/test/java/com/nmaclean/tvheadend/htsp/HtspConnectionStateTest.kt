package com.nmaclean.tvheadend.htsp

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HtspConnectionStateTest {

    @Test
    fun testStateLoggerTransitionsAndFormatting() {
        val logs = mutableListOf<String>()
        val stateLogger = HtspStateLogger("192.168.1.10", 9982, isDebugEnabled = { true }) { tag, msg ->
            logs.add("$tag: $msg")
        }

        assertEquals(HtspConnectionState.DISCONNECTED, stateLogger.currentState)
        stateLogger.transitionTo(HtspConnectionState.CONNECTING)
        stateLogger.transitionTo(HtspConnectionState.AUTHENTICATING)
        stateLogger.transitionTo(HtspConnectionState.CONNECTED)
        stateLogger.transitionTo(HtspConnectionState.DISCONNECTED, reason = "disconnect")

        assertEquals(HtspConnectionState.DISCONNECTED, stateLogger.currentState)
        assertEquals(4, logs.size)
        assertTrue(logs[0].contains("TVH_STREAM_DEBUG: [HTSP_STATE] DISCONNECTED -> CONNECTING host=192.168.1.10:9982"))
        assertTrue(logs[1].contains("TVH_STREAM_DEBUG: [HTSP_STATE] CONNECTING -> AUTHENTICATING"))
        assertTrue(logs[2].contains("TVH_STREAM_DEBUG: [HTSP_STATE] AUTHENTICATING -> CONNECTED"))
        assertTrue(logs[3].contains("TVH_STREAM_DEBUG: [HTSP_STATE] CONNECTED -> DISCONNECTED") && logs[3].contains("reason=\"disconnect\""))
    }

    @Test
    fun testStateLoggerSuppressedWhenStreamingDebugDisabled() {
        val logs = mutableListOf<String>()
        val stateLogger = HtspStateLogger("192.168.1.10", 9982, isDebugEnabled = { false }) { tag, msg ->
            logs.add("$tag: $msg")
        }

        stateLogger.transitionTo(HtspConnectionState.CONNECTING)
        stateLogger.transitionTo(HtspConnectionState.CONNECTED)

        assertEquals(HtspConnectionState.CONNECTED, stateLogger.currentState)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun testConnectWithBackoffLogsRetryAttemptsAndDelays() {
        val logs = mutableListOf<String>()
        val sleepDelays = mutableListOf<Long>()
        val stateLogger = HtspStateLogger("tvh.local", 9982, isDebugEnabled = { true }) { _, msg -> logs.add(msg) }
        var attempts = 0

        val result = HtspConnectionPool.connectWithBackoff(
            host = "tvh.local",
            port = 9982,
            maxAttempts = 3,
            initialBackoffMs = 250L,
            stateLogger = stateLogger,
            sleeper = { sleepDelays.add(it) }
        ) {
            attempts++
            if (attempts < 3) {
                throw IOException("Connection refused #$attempts")
            }
            "connected-client"
        }

        assertEquals("connected-client", result)
        assertEquals(3, attempts)
        assertEquals(listOf(250L, 500L), sleepDelays)
        assertEquals(HtspConnectionState.CONNECTED, stateLogger.currentState)
        assertTrue(logs.any { it.contains("BACKOFF_RETRY") && it.contains("attempt=1/3") && it.contains("backoffMs=250") })
        assertTrue(logs.any { it.contains("BACKOFF_RETRY") && it.contains("attempt=2/3") && it.contains("backoffMs=500") })
    }

    @Test
    fun testConnectWithBackoffExhaustedTransitionsToDisconnected() {
        val logs = mutableListOf<String>()
        val sleepDelays = mutableListOf<Long>()
        val stateLogger = HtspStateLogger("tvh.local", 9982, isDebugEnabled = { true }) { _, msg -> logs.add(msg) }

        try {
            HtspConnectionPool.connectWithBackoff<String>(
                host = "tvh.local",
                port = 9982,
                maxAttempts = 3,
                initialBackoffMs = 100L,
                stateLogger = stateLogger,
                sleeper = { sleepDelays.add(it) }
            ) {
                throw IOException("Network unreachable")
            }
            fail("Expected IOException when all attempts fail")
        } catch (expected: IOException) {
            assertEquals("Network unreachable", expected.message)
        }

        assertEquals(listOf(100L, 200L), sleepDelays)
        assertEquals(HtspConnectionState.DISCONNECTED, stateLogger.currentState)
        assertTrue(logs.last().contains("-> DISCONNECTED") && logs.last().contains("attempt=3/3"))
    }
}

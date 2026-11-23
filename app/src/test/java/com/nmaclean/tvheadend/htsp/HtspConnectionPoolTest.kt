package com.nmaclean.tvheadend.htsp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class HtspConnectionPoolTest {

    @Test
    fun testConnectWithBackoffSucceedsOnFirstAttempt() {
        val stateLogger = HtspStateLogger("127.0.0.1", 9982)
        var attempts = 0

        val result = HtspConnectionPool.connectWithBackoff(
            host = "127.0.0.1",
            port = 9982,
            maxAttempts = 3,
            initialBackoffMs = 10L,
            stateLogger = stateLogger,
            sleeper = {}
        ) {
            attempts++
            "connected"
        }

        assertEquals("connected", result)
        assertEquals(1, attempts)
        assertEquals(HtspConnectionState.CONNECTED, stateLogger.currentState)
    }

    @Test
    fun testConnectWithBackoffRetriesAndSucceedsOnThirdAttempt() {
        val stateLogger = HtspStateLogger("127.0.0.1", 9982)
        var attempts = 0

        val result = HtspConnectionPool.connectWithBackoff(
            host = "127.0.0.1",
            port = 9982,
            maxAttempts = 3,
            initialBackoffMs = 10L,
            stateLogger = stateLogger,
            sleeper = {}
        ) {
            attempts++
            if (attempts < 3) {
                throw IOException("Temporary connection failure $attempts")
            }
            "connected_after_retry"
        }

        assertEquals("connected_after_retry", result)
        assertEquals(3, attempts)
        assertEquals(HtspConnectionState.CONNECTED, stateLogger.currentState)
    }

    @Test(expected = IOException::class)
    fun testConnectWithBackoffFailsAfterMaxAttemptsExhausted() {
        val stateLogger = HtspStateLogger("127.0.0.1", 9982)
        HtspConnectionPool.connectWithBackoff(
            host = "127.0.0.1",
            port = 9982,
            maxAttempts = 3,
            initialBackoffMs = 10L,
            stateLogger = stateLogger,
            sleeper = {}
        ) {
            throw IOException("Persistent network outage")
        }
    }
}

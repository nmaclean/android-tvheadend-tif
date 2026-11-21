package com.nmaclean.tvheadend.iptv.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IptvAllowlistModelsTest {

    @Test
    fun testSerializationRoundTrip() {
        val channel = AllowlistChannel(
            id = "iptv-test-1",
            name = "Test Channel",
            group = "News",
            logoUrl = "http://example.com/logo.png",
            tvgId = "Test.us",
            streamUrl = "http://example.com/stream.m3u8",
            headers = mapOf("User-Agent" to "VLC")
        )

        val snapshot = AllowlistSnapshot(
            allowlistVersion = 1,
            updatedAtEpochMs = 1700000000000L,
            channels = listOf(channel)
        )

        val jsonString = Json.encodeToString(snapshot)
        assertNotNull(jsonString)

        val decoded = Json.decodeFromString<AllowlistSnapshot>(jsonString)
        assertEquals(snapshot.allowlistVersion, decoded.allowlistVersion)
        assertEquals(snapshot.updatedAtEpochMs, decoded.updatedAtEpochMs)
        assertEquals(1, decoded.channels.size)
        assertEquals(channel, decoded.channels[0])
    }
}

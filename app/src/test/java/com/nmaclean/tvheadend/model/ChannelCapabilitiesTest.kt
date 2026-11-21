package com.nmaclean.tvheadend.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCapabilitiesTest {

    @Test
    fun testTvheadendChannelCapabilities() {
        val tvhChannel = TvhChannel(
            id = 1L,
            uuid = "12345",
            name = "BBC One HD",
            number = 1,
            icon = "http://localhost/logo.png",
            inputId = "com.nmaclean.tvheadend/.TvheadendInputService"
        )
        val caps = ChannelCapabilitiesResolver.resolve(tvhChannel, isSeekable = false)
        assertTrue(caps.canPause)
        assertTrue(caps.canSeek)
        assertTrue(caps.canRecord)
        assertTrue(caps.supportsTimeshift)
        assertTrue(caps.showSignalMetrics)
    }

    @Test
    fun testLiveIptvChannelCapabilities() {
        val iptvChannel = TvhChannel(
            id = 2L,
            uuid = "iptv:sky_sports",
            name = "Sky Sports Main Event",
            number = 10,
            icon = "http://localhost/logo.png",
            inputId = "com.nmaclean.tvheadend/.iptv.tif.IptvTvInputService"
        )
        val caps = ChannelCapabilitiesResolver.resolve(iptvChannel, isSeekable = false)
        assertFalse(caps.canPause)
        assertFalse(caps.canSeek)
        assertFalse(caps.canRecord)
        assertFalse(caps.supportsTimeshift)
        assertFalse(caps.showSignalMetrics)
        assertTrue(caps.showEpgDetails)
    }

    @Test
    fun testSeekableIptvChannelCapabilities() {
        val iptvVodChannel = TvhChannel(
            id = 3L,
            uuid = "iptv:vod_movie",
            name = "Movie VOD Catchup",
            number = 100,
            icon = "http://localhost/logo.png",
            inputId = "com.nmaclean.tvheadend/.iptv.tif.IptvTvInputService"
        )
        val caps = ChannelCapabilitiesResolver.resolve(iptvVodChannel, isSeekable = true)
        assertTrue(caps.canPause)
        assertTrue(caps.canSeek)
        assertFalse(caps.canRecord)
        assertTrue(caps.supportsTimeshift)
        assertFalse(caps.showSignalMetrics)
        assertTrue(caps.showEpgDetails)
    }
}

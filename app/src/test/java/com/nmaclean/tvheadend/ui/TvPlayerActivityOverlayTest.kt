package com.nmaclean.tvheadend.ui

import com.nmaclean.tvheadend.model.ChannelCapabilitiesResolver
import com.nmaclean.tvheadend.model.TvhChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvPlayerActivityOverlayTest {

    @Test
    fun testOsdCapabilitiesGatingForTvheadend() {
        val tvhChannel = TvhChannel(10L, "uuid-tvh", "BBC One", 1, "http://logo.png", "com.nmaclean.tvheadend/.TvheadendInputService")
        val caps = ChannelCapabilitiesResolver.resolve(tvhChannel)
        assertTrue(caps.canPause)
        assertTrue(caps.canRecord)
        assertTrue(caps.supportsTimeshift)
    }

    @Test
    fun testOsdCapabilitiesGatingForIptv() {
        val iptvChannel = TvhChannel(20L, "iptv:stream_1", "Sky News", 200, "http://logo.png", "com.nmaclean.tvheadend/.iptv.tif.IptvTvInputService")
        val caps = ChannelCapabilitiesResolver.resolve(iptvChannel, isSeekable = false)
        assertFalse(caps.canPause)
        assertFalse(caps.canRecord)
        assertFalse(caps.supportsTimeshift)
    }
}

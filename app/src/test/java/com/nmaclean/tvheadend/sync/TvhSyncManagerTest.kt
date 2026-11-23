package com.nmaclean.tvheadend.sync

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TvhSyncManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testClearTvheadendDataResetsDatabase() {
        val result = TvhSyncManager.clearTvheadendData(context)
        assertEquals(0, result.clearedChannels)
    }

    @Test
    fun testIptvChannelPreservationInChannelRegistry() {
        val tvhChannel = com.nmaclean.tvheadend.model.TvhChannel(101L, "254650951", "BBC One", 1, "", "com.nmaclean.tvheadend/.TvheadendInputService")
        val iptvChannel = com.nmaclean.tvheadend.model.TvhChannel(202L, "iptv:stream_url", "IPTV Sport", 2, "", "com.nmaclean.tvheadend/.iptv.tif.IptvTvInputService")
        
        com.nmaclean.tvheadend.htsp.ChannelRegistry.updateChannels(listOf(tvhChannel, iptvChannel))
        val channels = com.nmaclean.tvheadend.htsp.ChannelRegistry.getAllChannels()
        
        assertEquals(2, channels.size)
        assertEquals("BBC One", channels[0].name)
    }
}

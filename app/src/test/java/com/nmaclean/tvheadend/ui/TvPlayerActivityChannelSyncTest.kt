package com.nmaclean.tvheadend.ui

import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.model.TvhChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TvPlayerActivityChannelSyncTest {

    @Before
    fun setUp() {
        ChannelRegistry.updateChannels(emptyList())
    }

    @Test
    fun testNonEmptyChannelListUpdatesChannelRegistry() {
        val testChannels = listOf(
            TvhChannel(1L, "uuid1", "BBC One", 1, "http://logo1.png", "com.nmaclean.tvheadend/.TvheadendInputService"),
            TvhChannel(2L, "uuid2", "BBC Two", 2, "http://logo2.png", "com.nmaclean.tvheadend/.TvheadendInputService")
        )

        ChannelRegistry.updateChannels(testChannels)

        val cached = ChannelRegistry.getAllChannels()
        assertEquals(2, cached.size)
        assertEquals("BBC One", cached[0].name)
        assertEquals("BBC Two", cached[1].name)
    }

    @Test
    fun testEmptyQueryResultClearsChannelRegistry() {
        val initialChannels = listOf(
            TvhChannel(1L, "uuid1", "BBC One", 1, "http://logo1.png", "com.nmaclean.tvheadend/.TvheadendInputService")
        )
        ChannelRegistry.updateChannels(initialChannels)
        assertEquals(1, ChannelRegistry.getAllChannels().size)

        // Simulate empty query result
        val emptyChannels = emptyList<TvhChannel>()
        ChannelRegistry.updateChannels(emptyChannels)

        val cached = ChannelRegistry.getAllChannels()
        assertTrue("ChannelRegistry should be cleared when empty query result completes", cached.isEmpty())
    }
}

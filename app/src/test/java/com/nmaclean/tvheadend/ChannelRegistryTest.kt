package com.nmaclean.tvheadend

import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.model.TvhChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelRegistryTest {

    @Before
    fun setUp() {
        ChannelRegistry.updateChannels(emptyList())
    }

    @Test
    fun testRegisterAndGetChannel() {
        assertNull(ChannelRegistry.getChannelById(1L))
        val channel = TvhChannel(1L, "uuid-1234", "BBC One", 101, "", "")
        ChannelRegistry.updateChannels(listOf(channel))
        assertEquals("BBC One", ChannelRegistry.getChannelById(1L)?.name)
    }

    @Test
    fun testChannelSortingByNumber() {
        val ch1 = TvhChannel(1L, "uuid1", "BBC One", 101, "", "")
        val ch2 = TvhChannel(2L, "uuid2", "Peacock", 490, "", "")
        val ch3 = TvhChannel(3L, "uuid3", "Sky Sports Main Event", 401, "", "")
        val ch4 = TvhChannel(4L, "uuid4", "BBC Two", 102, "", "")

        val unorderedList = listOf(ch2, ch3, ch1, ch4)
        val sortedList = unorderedList.sortedWith(compareBy({ it.number }, { it.name }))

        assertEquals(101, sortedList[0].number)
        assertEquals("BBC One", sortedList[0].name)
        assertEquals(102, sortedList[1].number)
        assertEquals("BBC Two", sortedList[1].name)
        assertEquals(401, sortedList[2].number)
        assertEquals("Sky Sports Main Event", sortedList[2].name)
        assertEquals(490, sortedList[3].number)
        assertEquals("Peacock", sortedList[3].name)
    }
}

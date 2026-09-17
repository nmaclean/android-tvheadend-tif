package com.nmaclean.tvheadend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ChannelRegistryTest {

    @Before
    fun setUp() {
        ChannelRegistry.clear()
    }

    @Test
    fun testRegisterAndGetUuid() {
        assertNull(ChannelRegistry.getUuid("1"))
        ChannelRegistry.register("1", "uuid-1234")
        assertEquals("uuid-1234", ChannelRegistry.getUuid("1"))
    }
}

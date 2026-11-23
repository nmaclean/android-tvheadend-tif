package com.nmaclean.tvheadend.player

import com.nmaclean.tvheadend.model.TvhConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class HtspDataSourceStallTest {

    @Test
    fun testInitialStreamTimeoutConstant() {
        assertEquals(10000, TvhConstants.INITIAL_STREAM_TIMEOUT_MS)
        assertEquals(12000, TvhConstants.MID_STREAM_TIMEOUT_MS)
    }
}

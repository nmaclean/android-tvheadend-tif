package com.nmaclean.tvheadend.iptv.server

import org.junit.Assert.assertTrue
import org.junit.Test

class LanNetworkUtilsTest {

    @Test
    fun testFindLanIpv4AddressReturnsValidOrNull() {
        val ip = LanNetworkUtils.findLanIpv4Address()
        // On test environment, IP might be null or valid ipv4 string
        if (ip != null) {
            assertTrue(ip.contains('.'))
        }
    }
}

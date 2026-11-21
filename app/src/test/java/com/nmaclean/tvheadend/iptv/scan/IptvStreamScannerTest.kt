package com.nmaclean.tvheadend.iptv.scan

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IptvStreamScannerTest {

    private lateinit var context: Context
    private lateinit var scanner: IptvStreamScanner

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scanner = IptvStreamScanner(context)
    }

    @Test
    fun testScannerInitialization() {
        assertEquals("IptvStreamScanner", IptvStreamScanner.TAG)
    }
}

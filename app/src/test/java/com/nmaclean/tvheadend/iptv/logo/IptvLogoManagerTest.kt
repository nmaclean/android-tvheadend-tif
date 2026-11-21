package com.nmaclean.tvheadend.iptv.logo

import android.content.Context
import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IptvLogoManagerTest {

    private lateinit var context: Context
    private lateinit var logoManager: IptvLogoManager

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(android.app.Activity::class.java).get()
        logoManager = IptvLogoManager(context)
    }

    @Test
    fun testResolveLogoFallback() {
        val channel = AllowlistChannel(
            id = "test-1",
            name = "Test Channel",
            group = "News",
            logoUrl = "https://example.com/logo.png",
            streamUrl = "http://example.com/stream"
        )
        val resolved = logoManager.resolveAndCacheLogo(channel, null)
        assertEquals("https://example.com/logo.png", resolved)
    }

    @Test
    fun testRamCacheAndClear() {
        val channel = AllowlistChannel(
            id = "test-2",
            name = "Test Channel 2",
            group = "Sports",
            logoUrl = "https://example.com/logo2.png",
            streamUrl = "http://example.com/stream2"
        )
        val resolved = logoManager.resolveAndCacheLogo(channel, null)
        assertEquals("https://example.com/logo2.png", resolved)

        logoManager.clearRamCache()
        val reResolved = logoManager.resolveAndCacheLogo(channel, null)
        assertEquals("https://example.com/logo2.png", reResolved)
    }
}

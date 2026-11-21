package com.nmaclean.tvheadend.iptv.server

import android.content.Context
import com.nmaclean.tvheadend.iptv.epg.IptvEpgConstants
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.parser.StreamingM3uParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LanCurationServerTest {

    private lateinit var context: Context
    private lateinit var settingsStore: IptvEpgSettingsStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        settingsStore = IptvEpgSettingsStore(context)
        settingsStore.epgUrl = IptvEpgConstants.DEFAULT_IPTV_EPG_URL
    }

    @Test
    fun testLanNetworkUtils() {
        val ip = LanNetworkUtils.findLanIpv4Address()
        if (ip != null) {
            assertTrue(ip.contains('.'))
        }
    }

    @Test
    fun testDefaultEpgUrlAppliedWhenEpgUrlIsDefault() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1,Channel 1
            http://example.com:8080/live/user1/pass1/1.ts
        """.trimIndent()
        val parseResult = StreamingM3uParser.parse(ByteArrayInputStream(m3uContent.toByteArray(StandardCharsets.UTF_8)))
        val suggested = parseResult.suggestedEpgUrl
        assertEquals("http://example.com:8080/xmltv.php?username=user1&password=pass1", suggested)

        if (settingsStore.epgUrl.isEmpty() || settingsStore.epgUrl == IptvEpgConstants.DEFAULT_IPTV_EPG_URL) {
            settingsStore.epgUrl = suggested!!
        }

        assertEquals("http://example.com:8080/xmltv.php?username=user1&password=pass1", settingsStore.epgUrl)
    }

    @Test
    fun testDefaultEpgUrlPreservesExplicitUserOverride() {
        settingsStore.epgUrl = "http://customepg.com/epg.xml"
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1,Channel 1
            http://example.com:8080/live/user1/pass1/1.ts
        """.trimIndent()
        val parseResult = StreamingM3uParser.parse(ByteArrayInputStream(m3uContent.toByteArray(StandardCharsets.UTF_8)))
        val suggested = parseResult.suggestedEpgUrl

        if (settingsStore.epgUrl.isEmpty() || settingsStore.epgUrl == IptvEpgConstants.DEFAULT_IPTV_EPG_URL) {
            settingsStore.epgUrl = suggested!!
        }

        // Must preserve custom override!
        assertEquals("http://customepg.com/epg.xml", settingsStore.epgUrl)
    }
}

package com.nmaclean.tvheadend.iptv.epg

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IptvEpgSettingsTest {

    private lateinit var context: Context
    private lateinit var settingsStore: IptvEpgSettingsStore

    @Before
    fun setUp() {
        context = Robolectric.buildActivity(android.app.Activity::class.java).get()
        settingsStore = IptvEpgSettingsStore(context)
    }

    @Test
    fun testDefaultSettings() {
        assertTrue(settingsStore.isEpgEnabled)
        assertEquals(IptvEpgConstants.DEFAULT_IPTV_EPG_URL, settingsStore.epgUrl)
        assertEquals(48, settingsStore.lookaheadHours)
        assertEquals(0, settingsStore.offsetMinutes)
        assertTrue(settingsStore.matchByName)
    }
}

package com.nmaclean.tvheadend.iptv.epg

import android.content.Context
import com.nmaclean.tvheadend.sync.IptvEpgSyncStrategy
import com.nmaclean.tvheadend.sync.TvheadendSyncStrategy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IptvEpgSyncWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testIptvEpgSyncStrategyEnabledState() {
        val strategy = IptvEpgSyncStrategy()
        val store = IptvEpgSettingsStore(context)

        assertEquals(12L, strategy.repeatIntervalHours)
        assertEquals("iptv_periodic_epg_sync_work", strategy.workName)

        store.isEpgEnabled = false
        assertFalse(strategy.isEnabled(context))

        store.isEpgEnabled = true
        store.epgUrl = "http://example.com/xmltv.xml"
        assertTrue(strategy.isEnabled(context))
    }

    @Test
    fun testTvheadendSyncStrategyProperties() {
        val strategy = TvheadendSyncStrategy()
        assertEquals(24L, strategy.repeatIntervalHours)
        assertEquals("tvh_periodic_sync_work", strategy.workName)
    }

    @Test
    fun testIptvEpgSyncWorkerPerformSyncWhenDisabled() = runBlocking {
        val strategy = IptvEpgSyncStrategy()
        val store = IptvEpgSettingsStore(context)
        store.isEpgEnabled = false

        val result = strategy.performSync(context)
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }
}

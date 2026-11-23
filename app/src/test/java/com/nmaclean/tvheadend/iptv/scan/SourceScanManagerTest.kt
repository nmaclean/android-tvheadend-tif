package com.nmaclean.tvheadend.iptv.scan

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SourceScanManagerTest {

    private lateinit var context: Context
    private lateinit var scanManager: SourceScanManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scanManager = SourceScanManager(context)
    }

    @Test
    fun testInitialScanStateIsIdle() {
        assertEquals(ScanState.Idle, scanManager.scanState.value)
    }

    @Test
    fun testClearTvInputDataIptv() = runBlocking {
        val result = scanManager.clearTvInputData(ClearTarget.IPTV)
        assertEquals(0, result.clearedChannels)
    }

    @Test
    fun testClearTvInputDataTvheadend() = runBlocking {
        val result = scanManager.clearTvInputData(ClearTarget.TVHEADEND)
        assertEquals(0, result.clearedChannels)
    }

    @Test
    fun testClearTvInputDataBoth() = runBlocking {
        val result = scanManager.clearTvInputData(ClearTarget.BOTH)
        org.junit.Assert.assertTrue(result.clearedChannels >= 0)
    }

    @Test
    fun testClearAllTifInputs() = runBlocking {
        val result = scanManager.clearAllTifInputs()
        org.junit.Assert.assertTrue(result.clearedChannels >= 0)
        org.junit.Assert.assertTrue(result.clearedPrograms >= 0)
        org.junit.Assert.assertTrue(result.clearedLogos >= 0)
    }

    @Test
    fun testResetAllDataAndSettings() = runBlocking {
        val result = scanManager.resetAllDataAndSettings()
        org.junit.Assert.assertTrue(result.clearedChannels >= 0)
        org.junit.Assert.assertTrue(result.clearedPrograms >= 0)
        org.junit.Assert.assertTrue(result.clearedLogos >= 0)

        val settings = com.nmaclean.tvheadend.model.TvhSettings(context)
        assertEquals("", settings.host)
        assertEquals("", settings.username)

        val cached = com.nmaclean.tvheadend.htsp.ChannelRegistry.getAllChannels()
        assertEquals(0, cached.size)
    }

    @Test
    fun testPolymorphicHandlersAndGetChannelCount() = runBlocking {
        val tvhHandler = TvheadendSourceHandler()
        val iptvHandler = IptvSourceHandler()

        assertEquals(ScanSource.TVHEADEND, tvhHandler.source)
        assertEquals(ScanSource.IPTV, iptvHandler.source)

        val tvhCount = tvhHandler.getChannelCount(context)
        val iptvCount = iptvHandler.getChannelCount(context)

        assertEquals(0, tvhCount)
        assertEquals(0, iptvCount)
    }
}

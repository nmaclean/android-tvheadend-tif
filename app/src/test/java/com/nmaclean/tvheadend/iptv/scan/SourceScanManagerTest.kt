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
        assertEquals(0, result.clearedChannels)
    }

    @Test
    fun testClearAllTifInputs() = runBlocking {
        val result = scanManager.clearAllTifInputs()
        assertEquals(0, result.clearedChannels)
        assertEquals(0, result.clearedPrograms)
        assertEquals(0, result.clearedLogos)
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

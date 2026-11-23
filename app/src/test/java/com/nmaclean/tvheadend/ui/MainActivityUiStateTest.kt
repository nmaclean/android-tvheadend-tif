package com.nmaclean.tvheadend.ui

import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.sync.EpgRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MainActivityUiStateTest {

    @Test
    fun testCategoryFilteringOffMainDispatcher() {
        val channels = listOf(
            TvhChannel(1L, "uuid1", "BBC One", 1, "http://logo.png", "inputId1"),
            TvhChannel(2L, "uuid2", "Sky Sports Main Event", 401, "http://logo.png", "inputId1")
        )

        val allFiltered = TvhUiRepository.filterChannelsByCategory(channels, "All Channels", emptyMap())
        assertEquals(2, allFiltered.size)

        val sportsFiltered = TvhUiRepository.filterChannelsByCategory(channels, "Sport", emptyMap())
        assertEquals(1, sportsFiltered.size)
        assertEquals("Sky Sports Main Event", sportsFiltered[0].name)
    }

    @Test
    fun testDeriveCurrentEpgFromWindow() {
        val channel = TvhChannel(1L, "uuid1", "BBC One", 1, "http://logo.png", "inputId1")
        val now = System.currentTimeMillis()
        val prog1 = EpgRepository.ProgramInfo(101L, 1L, "BBC News", "Live News", now - 600_000L, now + 600_000L)
        val prog2 = EpgRepository.ProgramInfo(102L, 1L, "Weather", "Forecast", now + 600_000L, now + 1200_000L)

        val guideMap = mapOf(1L to listOf(prog1, prog2))
        val currentEpg = TvhUiRepository.deriveCurrentEpgFromWindow(listOf(channel), guideMap, now)

        val chEpg = currentEpg[1L]
        assertNotNull(chEpg)
        assertEquals("BBC News", chEpg?.currentProgram?.title)
        assertEquals("Weather", chEpg?.nextProgram?.title)
    }
}

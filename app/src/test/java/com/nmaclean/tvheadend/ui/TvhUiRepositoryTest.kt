package com.nmaclean.tvheadend.ui

import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.computeWatchedPercent
import com.nmaclean.tvheadend.sync.EpgRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvhUiRepositoryTest {

    @Test
    fun testComputeWatchedPercent() {
        assertEquals(65, computeWatchedPercent(playCount = 0, playPositionSec = 650, durationSec = 1000))
        assertEquals(100, computeWatchedPercent(playCount = 1, playPositionSec = 0, durationSec = 1000))
        assertEquals(0, computeWatchedPercent(playCount = 0, playPositionSec = 0, durationSec = 1000))
    }

    @Test
    fun testComputeNowOffsetDpAndProgramWidthDp() {
        val windowStart = 1_000_000L
        val thirtyMinMs = 30 * 60_000L
        val dpPerMin = 4.0f

        assertEquals(120.0f, TvhUiRepository.computeNowOffsetDp(windowStart + thirtyMinMs, windowStart, dpPerMin), 0.01f)
        assertEquals(0.0f, TvhUiRepository.computeNowOffsetDp(windowStart - 10_000L, windowStart, dpPerMin), 0.01f)

        val width = TvhUiRepository.computeProgramWidthDp(
            startMs = windowStart,
            endMs = windowStart + thirtyMinMs,
            windowStartMs = windowStart,
            windowEndMs = windowStart + 4 * thirtyMinMs,
            dpPerMinute = dpPerMin
        )
        assertEquals(120.0f, width, 0.01f)

        val minClamped = TvhUiRepository.computeProgramWidthDp(
            startMs = windowStart,
            endMs = windowStart + 5 * 60_000L,
            windowStartMs = windowStart,
            windowEndMs = windowStart + 4 * thirtyMinMs,
            dpPerMinute = dpPerMin,
            minWidthDp = 84f
        )
        assertEquals(84.0f, minClamped, 0.01f)
    }

    @Test
    fun testFilterChannelsByCategory() {
        val bbcOne = TvhChannel(1L, "u1", "BBC One HD", 101)
        val bbcNews = TvhChannel(2L, "u2", "BBC News", 231)
        val skySports = TvhChannel(3L, "u3", "Sky Sports Main Event", 401)
        val cbeebies = TvhChannel(4L, "u4", "CBeebies", 202)
        val channels = listOf(bbcOne, bbcNews, skySports, cbeebies)

        val epg = mapOf(
            1L to EpgRepository.ChannelEpg(
                EpgRepository.ProgramInfo(10L, 1L, "BBC News at Six", "Evening news", 1000L, 2000L),
                null
            )
        )

        assertEquals(4, TvhUiRepository.filterChannelsByCategory(channels, "All Channels", epg).size)
        val news = TvhUiRepository.filterChannelsByCategory(channels, "News", epg)
        assertTrue(news.contains(bbcNews))
        assertTrue(news.contains(bbcOne))
        assertEquals(listOf(skySports), TvhUiRepository.filterChannelsByCategory(channels, "Sport", epg))
        assertEquals(listOf(cbeebies), TvhUiRepository.filterChannelsByCategory(channels, "Kids", epg))
        assertEquals(listOf(bbcOne), TvhUiRepository.filterChannelsByCategory(channels, "Favourites", epg, setOf(1L)))
    }

    @Test
    fun testClassifyEpgGenre() {
        assertEquals(TvhUiRepository.EpgGenre.SPORT, TvhUiRepository.classifyEpgGenre("Match of the Day", null, "BBC One"))
        assertEquals(TvhUiRepository.EpgGenre.NEWS, TvhUiRepository.classifyEpgGenre("BBC News at Six", null, "BBC One"))
        assertEquals(TvhUiRepository.EpgGenre.MOVIES, TvhUiRepository.classifyEpgGenre("Evening Film: Skyfall", null, "ITV1"))
        assertEquals(TvhUiRepository.EpgGenre.KIDS, TvhUiRepository.classifyEpgGenre("Bluey", null, "CBeebies"))
        assertEquals(TvhUiRepository.EpgGenre.DOCUMENTARY, TvhUiRepository.classifyEpgGenre("Grand Designs", null, "Channel 4"))
        assertEquals(TvhUiRepository.EpgGenre.DRAMA, TvhUiRepository.classifyEpgGenre("University Challenge", null, "BBC Two"))
        assertEquals(TvhUiRepository.EpgGenre.SPORT, TvhUiRepository.classifyEpgGenre("Live Match", null, "Sports TV", "Sports"))
        assertEquals(TvhUiRepository.EpgGenre.NEWS, TvhUiRepository.classifyEpgGenre("Daily Bulletin", null, "News TV", "News / Current Affairs"))
    }

    @Test
    fun testDeriveCurrentEpgFromWindow() {
        val ch1 = TvhChannel(1L, "u1", "BBC One", 1)
        val ch2 = TvhChannel(2L, "u2", "Film4", 14)
        val now = 1_000_000L
        val curr = EpgRepository.ProgramInfo(10L, 1L, "Current Show", null, now - 10_000L, now + 10_000L)
        val next = EpgRepository.ProgramInfo(11L, 1L, "Next Show", null, now + 10_000L, now + 40_000L)
        val futureOnly = EpgRepository.ProgramInfo(12L, 2L, "Later Film", null, now + 20_000L, now + 80_000L)

        val derived = TvhUiRepository.deriveCurrentEpgFromWindow(
            channels = listOf(ch1, ch2),
            guideMap = mapOf(1L to listOf(curr, next), 2L to listOf(futureOnly)),
            nowMs = now
        )

        assertEquals(curr, derived[1L]?.currentProgram)
        assertEquals(next, derived[1L]?.nextProgram)
        assertEquals(null, derived[2L]?.currentProgram)
        assertEquals(futureOnly, derived[2L]?.nextProgram)
    }
}

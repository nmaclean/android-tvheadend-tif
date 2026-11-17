package com.nmaclean.tvheadend.sync

import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.model.TvhRecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvrRepositoryTest {

    @Test
    fun testDvrEntryAddUpdateDelete() {
        val reducer = DvrStateReducer()
        val channels = mapOf(
            10L to TvhChannel(10L, "u10", "BBC One HD", 101, "http://picon/bbc1.png")
        )

        val added = reducer.applyMessage(
            mapOf(
                "method" to "dvrEntryAdd",
                "id" to 501L,
                "eventId" to 9001L,
                "channel" to 10L,
                "title" to "Grand Designs",
                "start" to 1000L,
                "stop" to 2000L,
                "state" to "scheduled"
            ),
            channels
        )
        assertTrue(added)
        var recs = reducer.snapshotRecordings()
        assertEquals(1, recs.size)
        assertEquals(TvhRecordingStatus.SCHEDULED, recs[0].status)
        assertEquals("BBC One HD", recs[0].channelName)
        assertEquals(9001L, recs[0].eventId)

        val updated = reducer.applyMessage(
            mapOf(
                "method" to "dvrEntryUpdate",
                "id" to 501L,
                "state" to "completed",
                "playposition" to 650L
            ),
            channels
        )
        assertTrue(updated)
        recs = reducer.snapshotRecordings()
        assertEquals(TvhRecordingStatus.RECORDED, recs[0].status)
        assertEquals(65, recs[0].watchedPercent)
        assertEquals(650_000L, recs[0].resumePositionMs)

        val deleted = reducer.applyMessage(mapOf("method" to "dvrEntryDelete", "id" to 501L), channels)
        assertTrue(deleted)
        assertTrue(reducer.snapshotRecordings().isEmpty())
    }

    @Test
    fun testAutorecEntryAddUpdateDelete() {
        val reducer = DvrStateReducer()
        val channels = mapOf(
            20L to TvhChannel(20L, "u20", "Channel 4", 104)
        )

        assertTrue(
            reducer.applyMessage(
                mapOf(
                    "method" to "autorecEntryAdd",
                    "id" to "rule-1",
                    "title" to "Taskmaster",
                    "channel" to 20L,
                    "enabled" to 1,
                    "fulltext" to 1
                ),
                channels
            )
        )
        var rules = reducer.snapshotRules()
        assertEquals(1, rules.size)
        assertTrue(rules[0].enabled)
        assertTrue(rules[0].fulltext)
        assertEquals("Channel 4", rules[0].channelName)

        assertTrue(
            reducer.applyMessage(
                mapOf(
                    "method" to "autorecEntryUpdate",
                    "id" to "rule-1",
                    "enabled" to 0
                ),
                channels
            )
        )
        rules = reducer.snapshotRules()
        assertFalse(rules[0].enabled)

        assertTrue(reducer.applyMessage(mapOf("method" to "autorecEntryDelete", "id" to "rule-1"), channels))
        assertTrue(reducer.snapshotRules().isEmpty())
    }
}

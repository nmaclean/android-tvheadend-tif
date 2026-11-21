package com.nmaclean.tvheadend.iptv.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class StreamingXmltvParserTest {

    @Test
    fun testParseXmltvStream() {
        val xmlData = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
                <programme start="20231120120000 +0000" stop="20231120130000 +0000" channel="bbconehd.uk">
                    <title>BBC News at Noon</title>
                    <sub-title>Hourly News Bulletin</sub-title>
                    <desc>Latest national and international news.</desc>
                    <category>News</category>
                    <icon src="https://example.com/icon.png"/>
                </programme>
            </tv>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(xmlData.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingXmltvParser.parse(inputStream)

        assertEquals(0, result.skippedCount)
        assertTrue(result.programmesByChannel.containsKey("bbconehd.uk"))
        val programmes = result.programmesByChannel["bbconehd.uk"]
        assertEquals(1, programmes?.size)
        val prog = programmes!![0]
        assertEquals("BBC News at Noon", prog.title)
        assertEquals("Hourly News Bulletin", prog.subTitle)
        assertEquals("Latest national and international news.", prog.description)
        assertEquals("News", prog.category)
        assertEquals("https://example.com/icon.png", prog.posterArtUri)
        assertTrue(prog.endTimeEpochMs > prog.startTimeEpochMs)
    }

    @Test
    fun testParseMalformedAndMissingAttributeXmltv() {
        val malformedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
                <programme start="20231120120000 +0000" stop="20231120130000 +0000">
                    <title>Missing Channel Attribute</title>
                </programme>
                <programme channel="ch1" start="invalid_date" stop="20231120130000 +0000">
                    <title>Invalid Start Date</title>
                </programme>
            </tv>
        """.trimIndent()

        val inputStream = ByteArrayInputStream(malformedXml.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingXmltvParser.parse(inputStream)
        assertEquals(0, result.programmesByChannel.size)
        assertTrue(result.skippedCount > 0)
    }

    @Test
    fun testMinEpochFiltering() {
        val xmlData = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
                <programme start="20200101120000 +0000" stop="20200101130000 +0000" channel="ch1">
                    <title>Old Event</title>
                </programme>
                <programme start="20231120120000 +0000" stop="20231120130000 +0000" channel="ch1">
                    <title>Current Event</title>
                </programme>
            </tv>
        """.trimIndent()

        val minEpoch = 1700481600000L // 2023-11-20 12:00:00 UTC
        val inputStream = ByteArrayInputStream(xmlData.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingXmltvParser.parse(inputStream, minEpochMs = minEpoch)

        val progs = result.programmesByChannel["ch1"]
        assertEquals(1, progs?.size)
        assertEquals("Current Event", progs!![0].title)
    }

    @Test
    fun testParseXmltvTimestampFast() {
        val t1 = StreamingXmltvParser.parseXmltvTimestampFast("20231120120000 +0000")
        val t2 = StreamingXmltvParser.parseXmltvTimestampFast("20231120120000 -0500")
        val t3 = StreamingXmltvParser.parseXmltvTimestampFast("20231120120000 +02:00")
        val t4 = StreamingXmltvParser.parseXmltvTimestampFast("20231120120000")
        val t5 = StreamingXmltvParser.parseXmltvTimestampFast("20231120120000 Z")
        val tErr = StreamingXmltvParser.parseXmltvTimestampFast("not_a_date")

        assertTrue(t1 != null)
        assertTrue(t2 != null)
        assertTrue(t3 != null)
        assertTrue(t4 != null)
        assertTrue(t5 != null)
        assertNull(tErr)

        // UTC: 2023-11-20 12:00:00 UTC = 1700481600000 ms
        assertEquals(1700481600000L, t1)
        assertEquals(1700481600000L, t4)
        assertEquals(1700481600000L, t5)

        // -0500 means UTC time is 5 hours later: 12:00 -0500 = 17:00 UTC = 1700499600000 ms
        assertEquals(1700499600000L, t2)

        // +02:00 means UTC time is 2 hours earlier: 12:00 +0200 = 10:00 UTC = 1700474400000 ms
        assertEquals(1700474400000L, t3)
    }
}

package com.nmaclean.tvheadend.iptv.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class StreamingM3uParserTest {

    @Test
    fun testParseBasicM3u() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 tvg-id="BBC.uk" tvg-logo="http://logo.png" group-title="News",BBC One
            #EXTVLCOPT:http-user-agent=VLC/3.0.18
            http://example.com/stream1.m3u8
            #EXTINF:-1 group-title="Sports",ESPN HD
            http://example.com/stream2.m3u8
            # MALFORMED ORPHAN LINE
            not_a_stream_line
        """.trimIndent()

        val inputStream = ByteArrayInputStream(m3uContent.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingM3uParser.parse(inputStream)

        assertEquals(2, result.candidates.size)
        assertEquals(1, result.skippedCount)
        assertEquals(listOf("News", "Sports"), result.groups)

        val c1 = result.candidates[0]
        assertEquals("BBC One", c1.name)
        assertEquals("News", c1.group)
        assertEquals("BBC.uk", c1.tvgId)
        assertEquals("http://logo.png", c1.logoUrl)
        assertEquals("http://example.com/stream1.m3u8", c1.streamUrl)
        assertEquals("VLC/3.0.18", c1.headers["User-Agent"])
    }

    @Test
    fun testParseEmptyAndMalformedM3u() {
        val emptyStream = ByteArrayInputStream("".toByteArray(StandardCharsets.UTF_8))
        val emptyResult = StreamingM3uParser.parse(emptyStream)
        assertEquals(0, emptyResult.candidates.size)
        assertEquals(0, emptyResult.skippedCount)

        val commentsOnly = """
            #EXTM3U
            # This is a comment
            # Another comment
        """.trimIndent()
        val commentResult = StreamingM3uParser.parse(ByteArrayInputStream(commentsOnly.toByteArray(StandardCharsets.UTF_8)))
        assertEquals(0, commentResult.candidates.size)
        assertEquals(0, commentResult.skippedCount)
    }

    @Test
    fun test50kLineStressTest() {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        val totalEntries = 10000
        for (i in 1..totalEntries) {
            sb.append("#EXTINF:-1 group-title=\"Group ${i % 5}\",Channel $i\n")
            sb.append("http://example.com/stream$i.m3u8\n")
            if (i % 10 == 0) {
                sb.append("malformed_line_$i\n")
            }
        }

        val inputStream = ByteArrayInputStream(sb.toString().toByteArray(StandardCharsets.UTF_8))
        val startTime = System.currentTimeMillis()
        val result = StreamingM3uParser.parse(inputStream)
        val duration = System.currentTimeMillis() - startTime

        assertEquals(totalEntries, result.candidates.size)
        assertEquals(totalEntries / 10, result.skippedCount)
        assertTrue("Parsing 10k entries should be fast (< 1000ms), took ${duration}ms", duration < 1000)
    }

    @Test
    fun testExtractEpgUrlFromUrlTvgHeader() {
        val m3uContent = """
            #EXTM3U url-tvg="http://provider.org:8080/xmltv.php?username=foo&password=bar"
            #EXTINF:-1 group-title="News",BBC One
            http://provider.org:8080/live/foo/bar/100.ts
        """.trimIndent()

        val inputStream = ByteArrayInputStream(m3uContent.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingM3uParser.parse(inputStream)

        assertEquals("http://provider.org:8080/xmltv.php?username=foo&password=bar", result.suggestedEpgUrl)
    }

    @Test
    fun testExtractEpgUrlWithXtreamCodesPathCredentials() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 group-title="News",BBC One
            http://provider.com:8080/live/testuser/testpass/100.ts
        """.trimIndent()

        val inputStream = ByteArrayInputStream(m3uContent.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingM3uParser.parse(inputStream)

        assertEquals("http://provider.com:8080/xmltv.php?username=testuser&password=testpass", result.suggestedEpgUrl)
    }

    @Test
    fun testExtractEpgUrlWithQueryCredentials() {
        val m3uContent = """
            #EXTM3U
            #EXTINF:-1 group-title="Sports",ESPN
            http://server.net/get.php?username=demo&password=secret&type=m3u_plus
        """.trimIndent()

        val inputStream = ByteArrayInputStream(m3uContent.toByteArray(StandardCharsets.UTF_8))
        val result = StreamingM3uParser.parse(inputStream)

        assertEquals("http://server.net/xmltv.php?username=demo&password=secret", result.suggestedEpgUrl)
    }
}

package com.nmaclean.tvheadend

import com.nmaclean.tvheadend.htsp.Htsmsg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HtsmsgTest {

    @Test
    fun testEncodeDecodeStringAndNumber() {
        val original = mapOf<String, Any>(
            "method" to "hello",
            "seq" to 1L,
            "clientname" to "TestClient"
        )

        val encoded = Htsmsg.encode(original)
        assertNotNull(encoded)

        val length = ByteBuffer.wrap(encoded, 0, 4).int
        val bodyBytes = ByteArray(length)
        System.arraycopy(encoded, 4, bodyBytes, 0, length)

        val decoded = Htsmsg.decode(bodyBytes)
        assertEquals("hello", decoded["method"])
        assertEquals(1L, decoded["seq"])
        assertEquals("TestClient", decoded["clientname"])
    }

    @Test
    fun testUnsignedChannelNumberDecoding() {
        val original = mapOf<String, Any>(
            "channelNumber" to 34305L,
            "highBitChannel" to 65511L
        )

        val encoded = Htsmsg.encode(original)
        val length = ByteBuffer.wrap(encoded, 0, 4).int
        val bodyBytes = ByteArray(length)
        System.arraycopy(encoded, 4, bodyBytes, 0, length)

        val decoded = Htsmsg.decode(bodyBytes)
        assertEquals(34305L, decoded["channelNumber"])
        assertEquals(65511L, decoded["highBitChannel"])
    }

    @Test
    fun testTryDecodeMuxPkt() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22)
        val original = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "pts" to 123456L,
            "dts" to 120000L,
            "frametype" to "I",
            "flags" to 1L,
            "payload" to payload
        )

        val encoded = Htsmsg.encode(original)
        val bodyLen = ByteBuffer.wrap(encoded, 0, 4).int
        val view = Htsmsg.MuxPktView()

        assertTrue(Htsmsg.tryDecodeMuxPkt(encoded, 4, bodyLen, view))
        assertEquals(1L, view.stream)
        assertTrue(view.hasPts)
        assertEquals(123456L, view.pts)
        assertTrue(view.hasDts)
        assertEquals(120000L, view.dts)
        assertTrue(view.frameTypeIsKey)
        assertEquals(1, view.flags)
        assertEquals(payload.size, view.payloadLength)
        assertArrayEquals(payload, encoded.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength))
    }

    @Test
    fun testTryDecodeMuxPktRejectsNonMuxPkt() {
        val encoded = Htsmsg.encode(mapOf("method" to "queueStatus", "delay" to 100L))
        val bodyLen = ByteBuffer.wrap(encoded, 0, 4).int
        val view = Htsmsg.MuxPktView()

        assertFalse(Htsmsg.tryDecodeMuxPkt(encoded, 4, bodyLen, view))
    }

    @Test
    fun testTryDecodeMuxPktSupportsNumericFrameType() {
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x11)
        val iFrameMsg = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "pts" to 40_000L,
            "dts" to 0L,
            "frametype" to 'I'.code.toLong(),
            "payload" to payload
        )
        val encodedI = Htsmsg.encode(iFrameMsg)
        val viewI = Htsmsg.MuxPktView()
        assertTrue(Htsmsg.tryDecodeMuxPkt(encodedI, 4, ByteBuffer.wrap(encodedI, 0, 4).int, viewI))
        assertTrue(viewI.hasFrameType)
        assertTrue(viewI.frameTypeIsKey)

        val pFrameMsg = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "pts" to 80_000L,
            "dts" to 40_000L,
            "frametype" to 'P'.code.toLong(),
            "payload" to payload
        )
        val encodedP = Htsmsg.encode(pFrameMsg)
        val viewP = Htsmsg.MuxPktView()
        assertTrue(Htsmsg.tryDecodeMuxPkt(encodedP, 4, ByteBuffer.wrap(encodedP, 0, 4).int, viewP))
        assertTrue(viewP.hasFrameType)
        assertFalse(viewP.frameTypeIsKey)
    }
}

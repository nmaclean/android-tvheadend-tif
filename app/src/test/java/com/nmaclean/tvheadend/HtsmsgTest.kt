package com.nmaclean.tvheadend

import com.nmaclean.tvheadend.htsp.Htsmsg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}

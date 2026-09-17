package com.nmaclean.tvheadend

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
}

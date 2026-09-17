package com.nmaclean.tvheadend

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer

@UnstableApi
@RunWith(RobolectricTestRunner::class)
class HtspExtractorTest {

    @Test
    fun testExtractorInitializationAndSniff() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        extractor.init(output)

        val input = mock<ExtractorInput>()
        val sniffResult = extractor.sniff(input)
        assertEquals(true, sniffResult)
    }

    @Test
    fun testReadStreamAddMessage() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

        // Construct an HTSP streamAdd message
        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "H264",
            "width" to 1920,
            "height" to 1080
        )

        val encodedMsg = Htsmsg.encode(msg)

        val input = mock<ExtractorInput>()
        val lenBytes = ByteBuffer.allocate(4).putInt(encodedMsg.size - 4).array()
        
        doAnswer { invocation ->
            val dest = invocation.getArgument<ByteArray>(0)
            val offset = invocation.getArgument<Int>(1)
            val length = invocation.getArgument<Int>(2)
            System.arraycopy(lenBytes, offset, dest, offset, length)
            true
        }.whenever(input).readFully(any(), eq(0), eq(4), eq(true))

        doAnswer { invocation ->
            val dest = invocation.getArgument<ByteArray>(0)
            val offset = invocation.getArgument<Int>(1)
            val length = invocation.getArgument<Int>(2)
            System.arraycopy(encodedMsg, 4 + offset, dest, offset, length)
            true
        }.whenever(input).readFully(any(), eq(0), eq(encodedMsg.size - 4))

        val posHolder = PositionHolder()
        val result = extractor.read(input, posHolder)

        assertEquals(Extractor.RESULT_CONTINUE, result)
    }
}

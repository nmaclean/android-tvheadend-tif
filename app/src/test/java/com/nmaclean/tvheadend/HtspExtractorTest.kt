package com.nmaclean.tvheadend

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import org.junit.Assert
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

    @Test
    fun testH264AnnexBMetaSplit() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

        // Concatenated Annex-B SPS (NAL type 7) and PPS (NAL type 8)
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x80.toByte(), 0x1F)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x06, 0x82.toByte())
        val meta = sps + pps

        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "H264",
            "width" to 1280,
            "height" to 720,
            "meta" to meta
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

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        assertEquals(2, format.initializationData.size)
        Assert.assertArrayEquals(sps, format.initializationData[0])
        Assert.assertArrayEquals(pps, format.initializationData[1])
    }

    @Test
    fun testCorruptedMetaDoesNotProduceSingleBufferCsd() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

        // Arbitrary garbage bytes that are not Annex-B and not valid avcC
        val invalidMeta = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "H264",
            "width" to 1280,
            "height" to 720,
            "meta" to invalidMeta
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

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        // Verify initializationData is empty, so MediaCodec is not configured with invalid CSD
        assertEquals(0, format.initializationData.size)
    }

    @Test
    fun testAacStreamAddAndMuxPkt() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 2L,
            "type" to "audio",
            "handler" to "AAC",
            "rate" to 3, // 48000 Hz
            "channels" to 2
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

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        assertEquals("audio/mp4a-latm", format.sampleMimeType)
        assertEquals(48000, format.sampleRate)
        assertEquals(2, format.channelCount)
        assertEquals(1, format.initializationData.size)
    }

    @Test
    fun testH264SpsCodecStringParsing() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

        // Real H.264 SPS (profile 66 = 0x42 Main/Baseline, constraints = 0xE0, level = 30 = 0x1E) and PPS
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xE0.toByte(), 0x1E, 0xDA.toByte(), 0x02, 0x80.toByte(), 0x2D, 0x10.toByte())
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x06, 0x82.toByte())
        val meta = sps + pps

        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "H264",
            "width" to 1280,
            "height" to 720,
            "meta" to meta
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

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        assertEquals(2, format.initializationData.size)
        assertEquals("avc1.42E01E", format.codecs)
    }
}

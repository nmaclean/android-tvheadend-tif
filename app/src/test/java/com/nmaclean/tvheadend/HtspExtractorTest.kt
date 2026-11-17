package com.nmaclean.tvheadend

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import com.nmaclean.tvheadend.htsp.Htsmsg
import com.nmaclean.tvheadend.player.HtspExtractor
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.invocation.InvocationOnMock
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

    private fun setupMockInput(input: ExtractorInput, encodedMsg: ByteArray) {
        val lenBytes = ByteBuffer.allocate(4).putInt(encodedMsg.size - 4).array()

        val answer = { invocation: InvocationOnMock ->
            val dest = invocation.getArgument<ByteArray>(0)
            val offset = invocation.getArgument<Int>(1)
            val length = invocation.getArgument<Int>(2)
            if (length == 4) {
                System.arraycopy(lenBytes, 0, dest, offset, length)
            } else {
                System.arraycopy(encodedMsg, 4, dest, offset, length)
            }
            null
        }

        doAnswer(answer).whenever(input).readFully(any(), any(), any())
        doAnswer(answer).whenever(input).readFully(any(), any(), any(), any())
    }

    @Test
    fun testReadStreamAddMessage() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

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
        setupMockInput(input, encodedMsg)

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
        setupMockInput(input, encodedMsg)

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
    fun testCorruptedMetaDefersFormatUntilMuxPkt() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

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
        setupMockInput(input, encodedMsg)

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        // Verify format WAS called during streamAdd so SampleQueue has upstreamFormat
        verify(trackOutput).format(any())
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
        setupMockInput(input, encodedMsg)

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
    fun testAacStreamAddPreservesNonEmptyMeta() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

        val latmMeta = byteArrayOf(0x13, 0x08, 0x56, 0xE5.toByte(), 0x98.toByte()) // 5-byte LATM StreamMuxConfig

        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 2L,
            "type" to "audio",
            "handler" to "AAC",
            "rate" to 3, // 48000 Hz
            "channels" to 2,
            "meta" to latmMeta
        )

        val encodedMsg = Htsmsg.encode(msg)
        val input = mock<ExtractorInput>()
        setupMockInput(input, encodedMsg)

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        assertEquals("audio/mp4a-latm", format.sampleMimeType)
        assertEquals(1, format.initializationData.size)
        // Non-empty meta should be preserved directly for LATM/AAC CSD
        assertEquals(5, format.initializationData[0].size)
    }

    @Test
    fun testH264SpsCodecStringParsing() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(any(), any())).thenReturn(trackOutput)

        extractor.init(output)

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
        setupMockInput(input, encodedMsg)

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        assertEquals(2, format.initializationData.size)
        assertEquals("avc1.42E01E", format.codecs)
    }

    @Test
    fun testBFrameReorderingDoesNotShiftMasterOffsetOrDesyncAudio() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val videoTrackOutput = mock<TrackOutput>()
        val audioTrackOutput = mock<TrackOutput>()
        whenever(output.track(eq(1), any())).thenReturn(videoTrackOutput)
        whenever(output.track(eq(2), any())).thenReturn(audioTrackOutput)

        extractor.init(output)

        // Stream 1 (Video) Add
        val videoAdd = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "H264"
        )
        val input1 = mock<ExtractorInput>()
        setupMockInput(input1, Htsmsg.encode(videoAdd))
        extractor.read(input1, PositionHolder())

        // Stream 2 (Audio) Add
        val audioAdd = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 2L,
            "index" to 2L,
            "type" to "audio",
            "handler" to "AAC",
            "rate" to 48000,
            "channels" to 2
        )
        val input2 = mock<ExtractorInput>()
        setupMockInput(input2, Htsmsg.encode(audioAdd))
        extractor.read(input2, PositionHolder())

        val videoPayload = byteArrayOf(0, 0, 0, 1, 0x65, 0x01) // Keyframe
        val bFramePayload = byteArrayOf(0, 0, 0, 1, 0x41, 0x01) // Non-keyframe
        val audioPayload = byteArrayOf(0x00, 0x11, 0x22, 0x33)

        // Packet 1: Video I-frame (DTS=1_000_000, PTS=1_080_000)
        val pkt1 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "dts" to 1_000_000L,
            "pts" to 1_080_000L,
            "frametype" to "I",
            "payload" to videoPayload
        )
        val inputPkt1 = mock<ExtractorInput>()
        setupMockInput(inputPkt1, Htsmsg.encode(pkt1))
        extractor.read(inputPkt1, PositionHolder())

        val videoTimeCaptor = argumentCaptor<Long>()
        verify(videoTrackOutput).sampleMetadata(videoTimeCaptor.capture(), any(), any(), any(), anyOrNull())
        // masterFirstPts = min(1080000, 1000000) = 1000000. masterOffset = -1000000.
        // I-frame sampleTimeUs = 1_080_000 - 1_000_000 = 80_000 us (80ms)
        assertEquals(80_000L, videoTimeCaptor.firstValue)

        // Packet 2: Video B-frame (DTS=1_040_000, PTS=1_000_000) -> PTS < initial I-frame PTS!
        val pkt2 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "dts" to 1_040_000L,
            "pts" to 1_000_000L,
            "frametype" to "P",
            "payload" to bFramePayload
        )
        val inputPkt2 = mock<ExtractorInput>()
        setupMockInput(inputPkt2, Htsmsg.encode(pkt2))
        extractor.read(inputPkt2, PositionHolder())

        val videoTimeCaptor2 = argumentCaptor<Long>()
        verify(videoTrackOutput, times(2)).sampleMetadata(videoTimeCaptor2.capture(), any(), any(), any(), anyOrNull())
        // B-frame sampleTimeUs = 1_000_000 - 1_000_000 = 0 us (0ms)
        assertEquals(0L, videoTimeCaptor2.secondValue)

        // Packet 3: Audio Frame 0 (DTS=1_000_000, PTS=1_000_000)
        val pkt3 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 2L,
            "dts" to 1_000_000L,
            "pts" to 1_000_000L,
            "payload" to audioPayload
        )
        val inputPkt3 = mock<ExtractorInput>()
        setupMockInput(inputPkt3, Htsmsg.encode(pkt3))
        extractor.read(inputPkt3, PositionHolder())

        val audioTimeCaptor = argumentCaptor<Long>()
        verify(audioTrackOutput).sampleMetadata(audioTimeCaptor.capture(), any(), any(), any(), anyOrNull())
        // Audio Frame 0 sampleTimeUs = 1_000_000 - 1_000_000 = 0 us (0ms) -> PERFECT A/V SYNC WITH B-FRAME
        assertEquals(0L, audioTimeCaptor.firstValue)

        // Packet 4: Audio Frame 1 (DTS=1_021_333, PTS=1_021_333)
        val pkt4 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 2L,
            "dts" to 1_021_333L,
            "pts" to 1_021_333L,
            "payload" to audioPayload
        )
        val inputPkt4 = mock<ExtractorInput>()
        setupMockInput(inputPkt4, Htsmsg.encode(pkt4))
        extractor.read(inputPkt4, PositionHolder())

        val audioTimeCaptor2 = argumentCaptor<Long>()
        verify(audioTrackOutput, times(2)).sampleMetadata(audioTimeCaptor2.capture(), any(), any(), any(), anyOrNull())
        // Audio Frame 1 sampleTimeUs = 1_021_333 - 1_000_000 = 21_333 us -> MONOTONIC AUDIO ADVANCEMENT
        assertEquals(21_333L, audioTimeCaptor2.secondValue)
    }

    @Test
    fun testAudioArrivingAfterVideoDoesNotShiftMasterOffsetOrCorruptTimeline() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val videoTrackOutput = mock<TrackOutput>()
        val audioTrackOutput = mock<TrackOutput>()
        whenever(output.track(eq(1), any())).thenReturn(videoTrackOutput)
        whenever(output.track(eq(2), any())).thenReturn(audioTrackOutput)

        extractor.init(output)

        // Stream 1 (Video) Add
        val videoAdd = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "H264"
        )
        val input1 = mock<ExtractorInput>()
        setupMockInput(input1, Htsmsg.encode(videoAdd))
        extractor.read(input1, PositionHolder())

        // Stream 2 (Audio) Add
        val audioAdd = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 2L,
            "index" to 2L,
            "type" to "audio",
            "handler" to "AAC",
            "rate" to 48000,
            "channels" to 2
        )
        val input2 = mock<ExtractorInput>()
        setupMockInput(input2, Htsmsg.encode(audioAdd))
        extractor.read(input2, PositionHolder())

        val videoPayload = byteArrayOf(0, 0, 0, 1, 0x65, 0x01)
        val audioPayload = byteArrayOf(0x00, 0x11, 0x22, 0x33)

        // Packet 1: Video I-frame arrives first (DTS=9_600_000, PTS=9_720_000)
        // masterFirstPts = 9_600_000, masterOffset = -9_600_000
        val pkt1 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "dts" to 9_600_000L,
            "pts" to 9_720_000L,
            "frametype" to "I",
            "payload" to videoPayload
        )
        val inputPkt1 = mock<ExtractorInput>()
        setupMockInput(inputPkt1, Htsmsg.encode(pkt1))
        extractor.read(inputPkt1, PositionHolder())

        val videoTimeCaptor = argumentCaptor<Long>()
        verify(videoTrackOutput).sampleMetadata(videoTimeCaptor.capture(), any(), any(), any(), anyOrNull())
        // Video Frame 1 sampleTimeUs = 9_720_000 - 9_600_000 = 120_000 us (120ms)
        assertEquals(120_000L, videoTimeCaptor.firstValue)

        // Packet 2: Audio Frame arrives second with smaller PTS (DTS=9_160_000, PTS=9_160_000)
        // Ensure masterOffset is NOT mutated mid-stream!
        val pkt2 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 2L,
            "dts" to 9_160_000L,
            "pts" to 9_160_000L,
            "payload" to audioPayload
        )
        val inputPkt2 = mock<ExtractorInput>()
        setupMockInput(inputPkt2, Htsmsg.encode(pkt2))
        extractor.read(inputPkt2, PositionHolder())

        val audioTimeCaptor = argumentCaptor<Long>()
        verify(audioTrackOutput).sampleMetadata(audioTimeCaptor.capture(), any(), any(), any(), anyOrNull())
        // Audio Frame 1 sampleTimeUs = 9_160_000 - 9_600_000 = -440_000 us (-440ms pre-roll)
        assertEquals(-440_000L, audioTimeCaptor.firstValue)

        // Packet 3: Video Frame 2 arrives (DTS=9_640_000, PTS=9_760_000)
        // Verify Video Frame 2 sampleTimeUs = 9_760_000 - 9_600_000 = 160_000 us (160ms) (NO 400ms mid-stream jump!)
        val pkt3 = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "dts" to 9_640_000L,
            "pts" to 9_760_000L,
            "frametype" to "P",
            "payload" to videoPayload
        )
        val inputPkt3 = mock<ExtractorInput>()
        setupMockInput(inputPkt3, Htsmsg.encode(pkt3))
        extractor.read(inputPkt3, PositionHolder())

        val videoTimeCaptor2 = argumentCaptor<Long>()
        verify(videoTrackOutput, times(2)).sampleMetadata(videoTimeCaptor2.capture(), any(), any(), any(), anyOrNull())
        assertEquals(160_000L, videoTimeCaptor2.secondValue)
    }

    @Test
    fun testMpeg2StreamAddAndMuxPkt() {
        val extractor = HtspExtractor()
        val output = mock<ExtractorOutput>()
        val trackOutput = mock<TrackOutput>()
        whenever(output.track(eq(1), any())).thenReturn(trackOutput)

        extractor.init(output)

        val mpeg2Meta = byteArrayOf(0x00, 0x00, 0x01, 0xB3.toByte(), 0x78, 0x04, 0x38)

        val msg = mapOf<String, Any>(
            "method" to "streamAdd",
            "seq" to 1L,
            "index" to 1L,
            "type" to "video",
            "handler" to "MPEG2VIDEO",
            "width" to 1920,
            "height" to 1080,
            "meta" to mpeg2Meta
        )

        val encodedMsg = Htsmsg.encode(msg)
        val input = mock<ExtractorInput>()
        setupMockInput(input, encodedMsg)

        val result = extractor.read(input, PositionHolder())
        assertEquals(Extractor.RESULT_CONTINUE, result)

        val formatCaptor = argumentCaptor<Format>()
        verify(trackOutput).format(formatCaptor.capture())

        val format = formatCaptor.firstValue
        assertEquals("video/mpeg2", format.sampleMimeType)

        // MPEG-2 Keyframe with Picture Start Code (coding type 1 = I-frame)
        val keyframePayload = byteArrayOf(0x00, 0x00, 0x01, 0xB3.toByte(), 0x78, 0x04, 0x38, 0x00, 0x00, 0x01, 0x00, 0x00, 0x08)
        val pkt = mapOf<String, Any>(
            "method" to "muxpkt",
            "stream" to 1L,
            "dts" to 1_000_000L,
            "pts" to 1_000_000L,
            "payload" to keyframePayload
        )

        val inputPkt = mock<ExtractorInput>()
        setupMockInput(inputPkt, Htsmsg.encode(pkt))
        extractor.read(inputPkt, PositionHolder())

        verify(trackOutput).sampleMetadata(any(), eq(1), eq(keyframePayload.size), eq(0), anyOrNull())
    }
}

package com.nmaclean.tvheadend.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Mp2DecoderTest {

    @Test
    fun `decodes MPEG1 Layer2 48kHz 192kbps joint stereo frame with CRC`() {
        // Header: 0xFFF = sync, 11 = MPEG1, 10 = Layer II, 0 = CRC present (protection_bit=0)
        // bitrateIndex=11 (192kbps? No, index 10 = 192kbps -> 0xA), sampleRateIndex=1 (48kHz), padding=0, private=0
        // mode=1 (Joint Stereo), modeExt=1 (jsBound=8), copyright=0, original=1, emphasis=0
        // Byte 0: 0xFF
        // Byte 1: 0xFC (1111 1100: sync + MPEG1 + Layer2 + protection_bit=0)
        // Byte 2: 0xA4 (1010 0100: bitrateIdx=10 (192kbps), srIdx=1 (48kHz), pad=0, priv=0)
        // Byte 3: 0x54 (0101 0100: mode=1 (Joint Stereo), modeExt=1, copy=0, orig=1, emph=0)
        // Frame size = 144 * 192000 / 48000 = 576 bytes
        val frame = ByteArray(576)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFC.toByte()
        frame[2] = 0xA4.toByte()
        frame[3] = 0x54.toByte()

        val decoder = Mp2Decoder()
        val pcm = decoder.decodeFrame(frame, 0, frame.size)

        assertNotNull(pcm)
        assertEquals(1152 * 2 * 2, pcm!!.size)
    }

    @Test
    fun `returns null when input is shorter than computed MP2 frameSize`() {
        val truncated = ByteArray(100)
        truncated[0] = 0xFF.toByte()
        truncated[1] = 0xFD.toByte() // protection_bit=1 (no CRC)
        truncated[2] = 0xA4.toByte() // 192kbps, 48kHz -> frameSize = 576
        truncated[3] = 0x00.toByte()

        val decoder = Mp2Decoder()
        assertNull(decoder.decodeFrame(truncated, 0, truncated.size))
    }
}

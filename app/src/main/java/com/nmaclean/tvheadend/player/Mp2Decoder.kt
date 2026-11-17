package com.nmaclean.tvheadend.player

import android.util.Log
import com.nmaclean.tvheadend.model.TvhSettings
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * ISO/IEC 11172-3 MPEG-1/2 Audio Layer II (MP2) decoder.
 *
 * Decodes 576-byte MP2 audio frames (1152 samples at 48kHz stereo) received
 * from TVHeadend HTSP streams into 16-bit PCM audio (4608 bytes per frame)
 * for direct output to ExoPlayer / AudioTrack without native library dependencies.
 */
class Mp2Decoder {

    private val vBufferLeft = FloatArray(1024)
    private val vBufferRight = FloatArray(1024)
    private var vOffsetLeft = 0
    private var vOffsetRight = 0
    private var decodedFrameCount = 0

    // Reusable per-frame scratch buffers to avoid allocations on the 24ms audio hot path
    private val bitAlloc = Array(2) { IntArray(32) }
    private val steps = Array(2) { IntArray(32) }
    private val scfsi = Array(2) { IntArray(32) }
    private val scaleFactors = Array(2) { Array(32) { FloatArray(3) } }
    private val pcmOutput = ByteArray(1152 * 2 * 2) // 1152 samples x 2 channels x 2 bytes
    private val sampleBuffer = Array(2) { FloatArray(32) }
    private val groupedSamples = Array(2) { Array(32) { IntArray(3) } }
    private val leftPcmScratch = ShortArray(32)
    private val rightPcmScratch = ShortArray(32)
    private val bitReader = BitReader()

    /**
     * Decodes a single MP2 frame into 16-bit stereo PCM.
     * Note: returns a reused internal buffer valid until the next [decodeFrame] call.
     */
    fun decodeFrame(input: ByteArray, offset: Int, length: Int): ByteArray? {
        if (length < 4) return null

        val headerData = ((input[offset].toInt() and 0xFF) shl 24) or
                ((input[offset + 1].toInt() and 0xFF) shl 16) or
                ((input[offset + 2].toInt() and 0xFF) shl 8) or
                (input[offset + 3].toInt() and 0xFF)

        // Verify MPEG Audio Sync Word (11 bits = 0x7FF)
        if ((headerData ushr 21) != 0x7FF) return null

        val version = (headerData ushr 19) and 3 // 3 = MPEG-1, 2 = MPEG-2
        val layer = (headerData ushr 17) and 3   // 2 = Layer II
        if (layer != 2) return null              // Only Layer II supported

        val bitrateIndex = (headerData ushr 12) and 15
        val samplingRateIndex = (headerData ushr 10) and 3
        val padding = (headerData ushr 9) and 1
        val mode = (headerData ushr 6) and 3     // 0=Stereo, 1=Joint Stereo, 2=Dual, 3=Mono
        val channels = if (mode == 3) 1 else 2

        val sampleRate = when (samplingRateIndex) {
            0 -> 44100
            1 -> 48000
            2 -> 32000
            else -> return null
        } * (if (version == 3) 1 else 2)

        val bitrate = if (version == 3) BITRATE_V1_L2[bitrateIndex] else BITRATE_V2_L2[bitrateIndex]
        if (bitrate == 0) return null

        val frameSize = 144 * bitrate / sampleRate + padding
        if (length < frameSize) return null

        bitReader.reset(input, offset + 4)

        // Read bit allocation table for 32 subbands
        val sblimit = if (sampleRate == 48000) 27 else 30
        bitAlloc[0].fill(0)
        bitAlloc[1].fill(0)
        steps[0].fill(0)
        steps[1].fill(0)
        scfsi[0].fill(0)
        scfsi[1].fill(0)

        val bitratePerChannel = bitrate / channels
        val allocTable = if (bitratePerChannel >= 56) ALLOC_TABLE_A else ALLOC_TABLE_B

        for (sb in 0 until sblimit) {
            val nbits = allocTable[sb].nbits
            val allocIndex = bitReader.readBits(nbits)
            bitAlloc[0][sb] = allocIndex
            steps[0][sb] = allocTable[sb].steps[allocIndex]
            if (channels == 2) {
                if (mode == 1 && sb >= allocTable[sb].jsbound) {
                    bitAlloc[1][sb] = allocIndex
                    steps[1][sb] = steps[0][sb]
                } else {
                    val allocIndexRight = bitReader.readBits(nbits)
                    bitAlloc[1][sb] = allocIndexRight
                    steps[1][sb] = allocTable[sb].steps[allocIndexRight]
                }
            }
        }

        // Read scale factor selection information (SCFSI)
        for (sb in 0 until sblimit) {
            if (bitAlloc[0][sb] != 0) scfsi[0][sb] = bitReader.readBits(2)
            if (channels == 2 && bitAlloc[1][sb] != 0) scfsi[1][sb] = bitReader.readBits(2)
        }

        // Read scale factors
        for (sb in 0 until sblimit) {
            for (ch in 0 until channels) {
                if (bitAlloc[ch][sb] != 0) {
                    when (scfsi[ch][sb]) {
                        0 -> {
                            scaleFactors[ch][sb][0] = SCALEFACTORS[bitReader.readBits(6)]
                            scaleFactors[ch][sb][1] = SCALEFACTORS[bitReader.readBits(6)]
                            scaleFactors[ch][sb][2] = SCALEFACTORS[bitReader.readBits(6)]
                        }
                        1 -> {
                            val sf0 = SCALEFACTORS[bitReader.readBits(6)]
                            val sf2 = SCALEFACTORS[bitReader.readBits(6)]
                            scaleFactors[ch][sb][0] = sf0
                            scaleFactors[ch][sb][1] = sf0
                            scaleFactors[ch][sb][2] = sf2
                        }
                        2 -> {
                            val sf = SCALEFACTORS[bitReader.readBits(6)]
                            scaleFactors[ch][sb][0] = sf
                            scaleFactors[ch][sb][1] = sf
                            scaleFactors[ch][sb][2] = sf
                        }
                        3 -> {
                            val sf0 = SCALEFACTORS[bitReader.readBits(6)]
                            val sf1 = SCALEFACTORS[bitReader.readBits(6)]
                            scaleFactors[ch][sb][0] = sf0
                            scaleFactors[ch][sb][1] = sf1
                            scaleFactors[ch][sb][2] = sf1
                        }
                    }
                }
            }
        }

        // De-quantize subband samples & apply synthesis filterbank (12 parts of 3 triplets = 36 x 32 samples = 1152)
        var pcmPos = 0

        for (gr in 0 until 12) {
            val part = gr / 4
            for (s in 0 until 3) {
                if (s == 0) {
                    for (sb in 0 until sblimit) {
                        for (ch in 0 until channels) {
                            if (bitAlloc[ch][sb] != 0) {
                                val step = steps[ch][sb]
                                when (step) {
                                    3 -> {
                                        val code = bitReader.readBits(5)
                                        groupedSamples[ch][sb][0] = code % 3
                                        groupedSamples[ch][sb][1] = (code / 3) % 3
                                        groupedSamples[ch][sb][2] = code / 9
                                    }
                                    5 -> {
                                        val code = bitReader.readBits(7)
                                        groupedSamples[ch][sb][0] = code % 5
                                        groupedSamples[ch][sb][1] = (code / 5) % 5
                                        groupedSamples[ch][sb][2] = code / 25
                                    }
                                    9 -> {
                                        val code = bitReader.readBits(10)
                                        groupedSamples[ch][sb][0] = code % 9
                                        groupedSamples[ch][sb][1] = (code / 9) % 9
                                        groupedSamples[ch][sb][2] = code / 81
                                    }
                                    else -> {
                                        val nbits = stepBits(step)
                                        groupedSamples[ch][sb][0] = bitReader.readBits(nbits)
                                        groupedSamples[ch][sb][1] = bitReader.readBits(nbits)
                                        groupedSamples[ch][sb][2] = bitReader.readBits(nbits)
                                    }
                                }
                            }
                        }
                    }
                }

                for (sb in 0 until sblimit) {
                    for (ch in 0 until channels) {
                        if (bitAlloc[ch][sb] != 0) {
                            val step = steps[ch][sb]
                            val rawSample = groupedSamples[ch][sb][s]
                            val dequantized = dequantize(rawSample, step)
                            sampleBuffer[ch][sb] = dequantized * scaleFactors[ch][sb][part]
                        } else {
                            sampleBuffer[ch][sb] = 0f
                        }
                    }
                }

                // Synthesis polyphase filterbank for left channel
                val leftPCM = synthesize(sampleBuffer[0], vBufferLeft, vOffsetLeft, leftPcmScratch)
                vOffsetLeft = (vOffsetLeft - 64) and 1023

                // Synthesis polyphase filterbank for right channel
                val rightPCM = if (channels == 2) {
                    val r = synthesize(sampleBuffer[1], vBufferRight, vOffsetRight, rightPcmScratch)
                    vOffsetRight = (vOffsetRight - 64) and 1023
                    r
                } else leftPCM

                for (i in 0 until 32) {
                    val lSample = leftPCM[i].toInt().coerceIn(-32768, 32767)
                    val rSample = rightPCM[i].toInt().coerceIn(-32768, 32767)

                    pcmOutput[pcmPos++] = (lSample and 0xFF).toByte()
                    pcmOutput[pcmPos++] = ((lSample ushr 8) and 0xFF).toByte()
                    pcmOutput[pcmPos++] = (rSample and 0xFF).toByte()
                    pcmOutput[pcmPos++] = ((rSample ushr 8) and 0xFF).toByte()
                }
            }
        }

        decodedFrameCount++
        if (TvhSettings.isStreamingDebugCached && (decodedFrameCount <= 5 || decodedFrameCount % 100 == 0)) {
            var maxPeak = 0
            var j = 0
            while (j + 1 < pcmOutput.size) {
                val s = (pcmOutput[j].toInt() and 0xFF) or (pcmOutput[j + 1].toInt() shl 8)
                val abs = Math.abs(s)
                if (abs > maxPeak) maxPeak = abs
                j += 2
            }
            Log.d("Mp2Decoder", "TVH_STREAM_DEBUG: [MP2 DECODER DIAG] frame=$decodedFrameCount, bitrate=${bitrate}kbps, rate=${sampleRate}Hz, ch=$channels, mode=$mode, maxAmplitude=$maxPeak/32767")
        }

        return pcmOutput
    }

    private fun stepBits(step: Int): Int = when (step) {
        7 -> 3
        15 -> 4
        31 -> 5
        63 -> 6
        127 -> 7
        255 -> 8
        511 -> 9
        1023 -> 10
        2047 -> 11
        4095 -> 12
        8191 -> 13
        16383 -> 14
        32767 -> 15
        else -> 0
    }

    private fun dequantize(rawSample: Int, step: Int): Float {
        if (step <= 0) return 0f
        return when (step) {
            3 -> (rawSample - 1).toFloat() * (2f / 3f)
            5 -> (rawSample - 2).toFloat() * (2f / 5f)
            9 -> (rawSample - 4).toFloat() * (2f / 9f)
            else -> {
                val nbits = stepBits(step)
                if (nbits == 0) return 0f
                val msbMask = 1 shl (nbits - 1)
                val valSigned = if ((rawSample and msbMask) != 0) rawSample - (1 shl nbits) else rawSample
                val c = (1 shl nbits).toFloat() / ((1 shl nbits) - 1).toFloat()
                (valSigned.toFloat() / msbMask.toFloat()) * c
            }
        }
    }

    private fun synthesize(subbands: FloatArray, vBuffer: FloatArray, vOffset: Int, pcm: ShortArray): ShortArray {
        // Matrixing
        for (k in 0 until 64) {
            var sum = 0f
            for (i in 0 until 32) {
                sum += subbands[i] * N_MATRIX[k][i]
            }
            vBuffer[(vOffset + k) and 1023] = sum
        }

        // Windowing
        for (j in 0 until 32) {
            var sum = 0f
            for (i in 0 until 16) {
                val vIdx = (vOffset + (i shl 6) + j) and 1023
                sum += vBuffer[vIdx] * D_WINDOW[j + (i shl 5)]
            }
            pcm[j] = (sum * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    private class BitReader {
        private var data: ByteArray = EMPTY_BYTES
        private var bytePos = 0
        private var bitPos = 0

        fun reset(data: ByteArray, startOffset: Int) {
            this.data = data
            this.bytePos = startOffset
            this.bitPos = 0
        }

        fun readBits(count: Int): Int {
            if (count == 0) return 0
            var result = 0
            var bitsNeeded = count
            while (bitsNeeded > 0) {
                if (bytePos >= data.size) return 0
                val bitsAvailable = 8 - bitPos
                val bitsToTake = minOf(bitsNeeded, bitsAvailable)
                val mask = (1 shl bitsToTake) - 1
                val shift = bitsAvailable - bitsToTake
                val chunk = (data[bytePos].toInt() ushr shift) and mask
                result = (result shl bitsToTake) or chunk
                bitsNeeded -= bitsToTake
                bitPos += bitsToTake
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
            return result
        }
    }

    companion object {
        private val EMPTY_BYTES = ByteArray(0)
        private val BITRATE_V1_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
        private val BITRATE_V2_L2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

        private val SCALEFACTORS = FloatArray(64) { i ->
            2.0.pow(-i / 3.0).toFloat()
        }

        private class AllocEntry(val nbits: Int, val steps: IntArray, val jsbound: Int)

        private val ALLOC_TABLE_A = Array(32) { sb ->
            when (sb) {
                in 0..2 -> AllocEntry(4, intArrayOf(0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767), 4)
                in 3..10 -> AllocEntry(4, intArrayOf(0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767), 8)
                in 11..22 -> AllocEntry(3, intArrayOf(0, 3, 5, 7, 9, 15, 31, 63), 12)
                else -> AllocEntry(2, intArrayOf(0, 3, 5, 7), 16)
            }
        }

        private val ALLOC_TABLE_B = Array(32) { sb ->
            when (sb) {
                in 0..2 -> AllocEntry(4, intArrayOf(0, 3, 5, 7, 9, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767), 4)
                in 3..10 -> AllocEntry(3, intArrayOf(0, 3, 5, 7, 9, 15, 31, 63), 8)
                in 11..22 -> AllocEntry(2, intArrayOf(0, 3, 5, 7), 12)
                else -> AllocEntry(2, intArrayOf(0, 3, 5, 7), 16)
            }
        }

        private val N_MATRIX = Array(64) { k ->
            FloatArray(32) { i ->
                cos((2 * k + 1) * (i + 16) * Math.PI / 64.0).toFloat()
            }
        }

        private val D_WINDOW = FloatArray(512) { i ->
            sin((i + 0.5) * Math.PI / 512.0).toFloat()
        }
    }
}

package com.nmaclean.tvheadend

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.IOException
import java.nio.ByteBuffer

@UnstableApi
class HtspExtractor : Extractor {
    companion object {
        private const val TAG = "HtspExtractor"
    }

    private var extractorOutput: ExtractorOutput? = null
    private val tracks = mutableMapOf<Long, TrackOutput>()
    private val trackTypes = mutableMapOf<Long, Int>()
    private val trackMimeTypes = mutableMapOf<Long, String>()
    private val trackHasCsd = mutableMapOf<Long, Boolean>()
    private val trackWidths = mutableMapOf<Long, Int>()
    private val trackHeights = mutableMapOf<Long, Int>()
    private val ignoredStreams = mutableSetOf<Long>()

    private var globalFirstPts = C.TIME_UNSET
    private val lastSampleTimeUsMap = mutableMapOf<Long, Long>()
    private val lastPtsMap = mutableMapOf<Long, Long>()
    private val hasSeenKeyframe = mutableMapOf<Long, Boolean>()
    private var tracksEnded = false
    private var released = false
    private var muxPktCount = 0

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        Log.d(TAG, "HtspExtractor initialized")
    }

    override fun sniff(input: ExtractorInput): Boolean {
        return true
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (released) return Extractor.RESULT_END_OF_INPUT

        val lenBytes = ByteArray(4)
        if (!input.readFully(lenBytes, 0, 4, true)) {
            return Extractor.RESULT_END_OF_INPUT
        }

        val length = ByteBuffer.wrap(lenBytes).int
        if (length <= 0 || length > 10 * 1024 * 1024) {
            Log.e(TAG, "Invalid HTSP message length: $length")
            throw IOException("Invalid HTSP message length: $length")
        }

        val payload = ByteArray(length)
        input.readFully(payload, 0, length)

        val msg = Htsmsg.decode(payload)
        val method = msg["method"] as? String

        if (method != "muxpkt") {
            Log.d(TAG, "HTSP message received: $method -> keys=${msg.keys}")
        }

        when (method) {
            "subscriptionStart" -> handleSubscriptionStart(msg)
            "streamAdd" -> handleStreamAdd(msg)
            "muxpkt" -> handleMuxPkt(msg)
            "streamStart" -> Log.d(TAG, "HTSP Stream started.")
            "streamStop" -> Log.d(TAG, "HTSP Stream stopped")
            "queueStatus" -> {
                Log.d(TAG, "HTSP queueStatus: packets=${msg["packets"]}, bytes=${msg["bytes"]}, delay=${msg["delay"]}, drops(B/P/I)=${msg["Bdrops"]}/${msg["Pdrops"]}/${msg["Idrops"]}")
            }
            else -> {
                if (msg.containsKey("stream") && msg.containsKey("payload")) {
                    handleMuxPkt(msg)
                } else {
                    Log.d(TAG, "Unhandled HTSP message method=$method keys=${msg.keys}")
                }
            }
        }

        return Extractor.RESULT_CONTINUE
    }

    private fun handleStreamAdd(msg: Map<String, Any>, globalMeta: ByteArray? = null) {
        val index = (msg["index"] as? Number)?.toLong() ?: return
        val type = msg["type"] as? String
        val codec = msg["handler"] as? String ?: msg["type"] as? String
        val meta = msg["meta"] as? ByteArray ?: globalMeta

        Log.d(TAG, "Adding stream index=$index type=$type codec=$codec metaSize=${meta?.size}")

        val codecUpper = codec?.uppercase() ?: ""
        val isSubtitleOrText = codecUpper.contains("DVBSUB") || codecUpper.contains("TELETEXT") ||
                codecUpper.contains("SUBTITLE") || codecUpper.contains("TEXT") ||
                type == "subtitle" || type == "teletext"

        if (isSubtitleOrText) {
            Log.d(TAG, "Ignoring subtitle/teletext stream index=$index type=$type codec=$codec")
            ignoredStreams.add(index)
            return
        }

        val mimeType = when {
            type == "audio" -> when {
                codecUpper.contains("AAC") || codecUpper.contains("LATM") -> MimeTypes.AUDIO_AAC
                codecUpper.contains("EAC3") || codecUpper.contains("E-AC-3") || codecUpper.contains("AC3+") -> MimeTypes.AUDIO_E_AC3
                codecUpper.contains("AC3") || codecUpper.contains("AC-3") || codecUpper.contains("DOLBY") -> MimeTypes.AUDIO_AC3
                codecUpper.contains("MPEG") || codecUpper.contains("MP2") || codecUpper.contains("MP1") || codecUpper.contains("MP3") -> MimeTypes.AUDIO_MPEG
                else -> MimeTypes.AUDIO_AAC
            }
            type == "video" -> when {
                codecUpper.contains("H264") || codecUpper.contains("AVC") -> MimeTypes.VIDEO_H264
                codecUpper.contains("HEVC") || codecUpper.contains("H265") -> MimeTypes.VIDEO_H265
                codecUpper.contains("MPEG2") -> MimeTypes.VIDEO_MPEG2
                else -> MimeTypes.VIDEO_H264
            }
            codecUpper.contains("MPEG2AUDIO") || codecUpper.contains("MP2") || codecUpper.contains("MP1") || codecUpper.contains("MP3") -> MimeTypes.AUDIO_MPEG
            codecUpper.contains("AAC") -> MimeTypes.AUDIO_AAC
            codecUpper.contains("AC3") || codecUpper.contains("AC-3") -> MimeTypes.AUDIO_AC3
            codecUpper.contains("EAC3") || codecUpper.contains("E-AC-3") -> MimeTypes.AUDIO_E_AC3
            codecUpper.contains("H264") || codecUpper.contains("AVC") -> MimeTypes.VIDEO_H264
            codecUpper.contains("HEVC") || codecUpper.contains("H265") -> MimeTypes.VIDEO_H265
            codecUpper.contains("MPEG2") -> MimeTypes.VIDEO_MPEG2
            else -> {
                Log.w(TAG, "Unknown codec: $codec, type: $type. Adding to ignored streams.")
                ignoredStreams.add(index)
                null
            }
        } ?: return

        val trackType = if (type == "video" || mimeType.startsWith("video/")) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val trackOutput = extractorOutput?.track(index.toInt(), trackType) ?: return

        trackTypes[index] = trackType
        trackMimeTypes[index] = mimeType

        val builder = Format.Builder()
            .setId(index.toString())
            .setSampleMimeType(mimeType)

        if (trackType == C.TRACK_TYPE_VIDEO) {
            val width = (msg["width"] as? Number)?.toInt() ?: 0
            val height = (msg["height"] as? Number)?.toInt() ?: 0

            if (width in 1..4096 && height in 1..2160) {
                builder.setWidth(width)
                builder.setHeight(height)
                trackWidths[index] = width
                trackHeights[index] = height
            } else {
                Log.d(TAG, "Missing explicit resolution (${width}x${height}). Allowing MediaCodec to parse dimensions dynamically from bitstream SPS.")
            }
        } else {
            val rawRate = (msg["rate"] as? Number)?.toInt() ?: 0
            val sampleRate = if (rawRate in 0..15) {
                when (rawRate) {
                    0 -> 96000
                    1 -> 88200
                    2 -> 64000
                    3 -> 48000
                    4 -> 44100
                    5 -> 32000
                    6 -> 24000
                    7 -> 22050
                    8 -> 16000
                    9 -> 12000
                    10 -> 11025
                    11 -> 8000
                    12 -> 7350
                    else -> 48000
                }
            } else {
                rawRate
            }

            val channelCount = (msg["channels"] as? Number)?.toInt() ?: 0
            if (sampleRate > 0) builder.setSampleRate(sampleRate)
            if (channelCount > 0) builder.setChannelCount(channelCount)
            Log.d(TAG, "Audio stream index=$index: rawRate=$rawRate -> sampleRate=$sampleRate Hz, channels=$channelCount")
        }

        var hasCsd = false
        if (mimeType == MimeTypes.AUDIO_AAC) {
            val sampleRate = builder.build().sampleRate.let { if (it > 0) it else 48000 }
            val channels = builder.build().channelCount.let { if (it > 0) it else 2 }
            val asc = buildAacAudioSpecificConfig(sampleRate, channels)
            builder.setInitializationData(listOf(asc))
            hasCsd = true
            Log.d(TAG, "Configured AAC AudioSpecificConfig CSD: [${asc.joinToString { "0x%02X".format(it) }}] for $sampleRate Hz, $channels ch")
        } else if (mimeType == MimeTypes.VIDEO_H264 && meta != null && meta.isNotEmpty()) {
            val initData = if (meta.size >= 4 && meta[0] == 0.toByte() && meta[1] == 0.toByte() && (meta[2] == 1.toByte() || (meta[2] == 0.toByte() && meta[3] == 1.toByte()))) {
                listOf(meta)
            } else {
                try {
                    val avcConfig = AvcConfig.parse(ParsableByteArray(meta))
                    avcConfig.initializationData
                } catch (e: Exception) {
                    listOf(meta)
                }
            }
            if (initData.isNotEmpty()) {
                builder.setInitializationData(initData)
                hasCsd = true
            }
        }

        trackHasCsd[index] = hasCsd

        val format = builder.build()
        Log.d(TAG, "Configured track $index format: $format, hasCsd=$hasCsd")
        if (hasCsd || trackType != C.TRACK_TYPE_VIDEO) {
            trackOutput.format(format)
        } else {
            Log.d(TAG, "Deferring trackOutput.format() for video stream $index until SPS/PPS CSD is parsed from keyframe.")
        }
        tracks[index] = trackOutput
    }

    private fun handleSubscriptionStart(msg: Map<String, Any>) {
        Log.d(TAG, "Handling subscriptionStart message")
        val globalMeta = msg["meta"] as? ByteArray
        val streamsList = msg["streams"] as? List<*>
        if (streamsList != null) {
            for (item in streamsList) {
                val streamMap = item as? Map<String, Any> ?: continue
                handleStreamAdd(streamMap, globalMeta)
            }
        }
        if (!tracksEnded && tracks.isNotEmpty()) {
            extractorOutput?.endTracks()
            extractorOutput?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            tracksEnded = true
        }
    }

    private fun handleMuxPkt(msg: Map<String, Any>) {
        val streamIndex = (msg["stream"] as? Number)?.toLong() ?: return
        if (ignoredStreams.contains(streamIndex)) {
            return
        }

        val trackOutput = tracks[streamIndex]
        if (trackOutput == null) {
            Log.d(TAG, "Discarding muxpkt for unregistered stream index $streamIndex")
            return
        }

        val trackType = trackTypes[streamIndex] ?: C.TRACK_TYPE_UNKNOWN
        val mimeType = trackMimeTypes[streamIndex] ?: ""

        val payload = msg["payload"] as? ByteArray ?: return
        val isAudio = (trackType == C.TRACK_TYPE_AUDIO)

        if (!tracksEnded && tracks.isNotEmpty()) {
            extractorOutput?.endTracks()
            tracksEnded = true
        }

        val ptsUs = (msg["pts"] as? Number)?.toLong() ?: C.TIME_UNSET
        val dtsUs = (msg["dts"] as? Number)?.toLong() ?: C.TIME_UNSET

        val rawTimeUs = when {
            ptsUs != C.TIME_UNSET -> ptsUs
            dtsUs != C.TIME_UNSET -> dtsUs
            else -> C.TIME_UNSET
        }

        if (rawTimeUs != C.TIME_UNSET && globalFirstPts == C.TIME_UNSET) {
            globalFirstPts = rawTimeUs
            Log.d(TAG, "Locked global PTS baseline: $globalFirstPts us (from stream $streamIndex)")
        }

        val basePts = if (globalFirstPts != C.TIME_UNSET) globalFirstPts else rawTimeUs
        val defaultStepUs = if (trackType == C.TRACK_TYPE_VIDEO) 40000L else 21333L
        val lastSampleTime = lastSampleTimeUsMap[streamIndex] ?: 0L

        val sampleTimeUs = if (rawTimeUs != C.TIME_UNSET && basePts != C.TIME_UNSET) {
            maxOf(0L, rawTimeUs - basePts)
        } else {
            if (lastSampleTimeUsMap.containsKey(streamIndex)) {
                lastSampleTime + defaultStepUs
            } else {
                0L
            }
        }

        lastSampleTimeUsMap[streamIndex] = sampleTimeUs

        val frameType = msg["frametype"] as? String
        var isKeyframe = isAudio || frameType == "I" || frameType == "key" ||
                ((msg["flags"] as? Number)?.toInt()?.let { (it and 0x01) != 0 } ?: false)

        if (!isKeyframe && !isAudio) {
            isKeyframe = containsKeyframe(payload, mimeType)
        }

        if (isKeyframe) {
            hasSeenKeyframe[streamIndex] = true
        }

        muxPktCount++
        if (muxPktCount <= 25 || muxPktCount % 100 == 0) {
            Log.d(TAG, "muxpkt #$muxPktCount: stream=$streamIndex (${if (isAudio) "audio" else "video"}), size=${payload.size}, pts=$ptsUs us, sampleTimeUs=$sampleTimeUs us, key=$isKeyframe")
        }

        var sampleDataBytes = payload
        val hasCsd = trackHasCsd[streamIndex] ?: false

        if (isAudio && mimeType == MimeTypes.AUDIO_AAC && payload.size > 7 &&
            payload[0] == 0xFF.toByte() && (payload[1].toInt() and 0xF6) == 0xF0) {
            val protectionAbsent = (payload[1].toInt() and 0x01) != 0
            val headerSize = if (protectionAbsent) 7 else 9
            if (payload.size > headerSize) {
                sampleDataBytes = payload.copyOfRange(headerSize, payload.size)
            }
        }

        if (!hasCsd && !isAudio && mimeType == MimeTypes.VIDEO_H264 && isKeyframe) {
            val csdList = parseAnnexBCsd(payload)
            if (csdList.isNotEmpty()) {
                val currentTrack = tracks[streamIndex]
                val width = trackWidths[streamIndex] ?: 0
                val height = trackHeights[streamIndex] ?: 0

                val builder = Format.Builder()
                    .setId(streamIndex.toString())
                    .setSampleMimeType(mimeType)
                    .setInitializationData(csdList)

                if (width in 1..4096 && height in 1..2160) {
                    builder.setWidth(width)
                    builder.setHeight(height)
                }

                currentTrack?.format(builder.build())
                trackHasCsd[streamIndex] = true
                Log.d(TAG, "Extracted inline SPS/PPS CSD from keyframe for video stream $streamIndex (${if (width > 0) "${width}x${height}" else "dynamic resolution"}): ${csdList.size} NAL units")
            }
        }

        val parsableByteArray = ParsableByteArray(sampleDataBytes)
        trackOutput.sampleData(parsableByteArray, sampleDataBytes.size)

        trackOutput.sampleMetadata(
            sampleTimeUs,
            if (isKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0,
            sampleDataBytes.size,
            0,
            null
        )
    }

    private fun containsKeyframe(payload: ByteArray, mimeType: String): Boolean {
        if (mimeType == MimeTypes.VIDEO_MPEG2) {
            var i = 0
            while (i < payload.size - 5) {
                if (payload[i] == 0.toByte() && payload[i+1] == 0.toByte() && payload[i+2] == 1.toByte() && payload[i+3] == 0.toByte()) {
                    val pictureCodingType = (payload[i+5].toInt() shr 3) and 0x07
                    if (pictureCodingType == 1) return true
                }
                i++
            }
            return false
        }

        var i = 0
        while (i < payload.size - 3) {
            val startCodeLen = if (payload[i] == 0.toByte() && payload[i+1] == 0.toByte() && payload[i+2] == 0.toByte() && payload[i+3] == 1.toByte()) {
                4
            } else if (i < payload.size - 2 && payload[i] == 0.toByte() && payload[i+1] == 0.toByte() && payload[i+2] == 1.toByte()) {
                3
            } else {
                0
            }

            if (startCodeLen > 0) {
                val nalStart = i + startCodeLen
                if (nalStart < payload.size) {
                    if (mimeType == MimeTypes.VIDEO_H265) {
                        val nalUnitType = (payload[nalStart].toInt() shr 1) and 0x3F
                        if (nalUnitType in 16..21 || nalUnitType in 32..34) {
                            return true
                        }
                    } else {
                        val nalUnitType = payload[nalStart].toInt() and 0x1F
                        if (nalUnitType == 5 || nalUnitType == 7 || nalUnitType == 8) {
                            return true
                        }
                    }
                }
                i += startCodeLen
            } else {
                i++
            }
        }
        return false
    }

    private fun parseAnnexBCsd(payload: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        var i = 0
        val offsets = mutableListOf<Int>()
        while (i < payload.size - 3) {
            val startCodeLen = if (payload[i] == 0.toByte() && payload[i+1] == 0.toByte() && payload[i+2] == 0.toByte() && payload[i+3] == 1.toByte()) {
                4
            } else if (i < payload.size - 2 && payload[i] == 0.toByte() && payload[i+1] == 0.toByte() && payload[i+2] == 1.toByte()) {
                3
            } else {
                0
            }
            if (startCodeLen > 0) {
                offsets.add(i)
                i += startCodeLen
            } else {
                i++
            }
        }

        for (idx in offsets.indices) {
            val start = offsets[idx]
            val end = if (idx + 1 < offsets.size) offsets[idx + 1] else payload.size
            val nal = payload.copyOfRange(start, end)
            val nalStart = if (nal.size > 3 && nal[2] == 1.toByte()) 3 else 4
            if (nalStart < nal.size) {
                val nalType = nal[nalStart].toInt() and 0x1F
                if (nalType == 7) sps = nal
                if (nalType == 8) pps = nal
            }
        }

        if (sps != null) nalUnits.add(sps)
        if (pps != null) nalUnits.add(pps)
        return nalUnits
    }

    private fun buildAacAudioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
        val freqIdx = when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000 -> 11
            7350 -> 12
            else -> 3
        }
        val channels = if (channelCount in 1..6) channelCount else 2
        val aot = 2
        val ascVal = (aot shl 11) or (freqIdx shl 7) or (channels shl 3)
        return byteArrayOf(
            ((ascVal shr 8) and 0xFF).toByte(),
            (ascVal and 0xFF).toByte()
        )
    }

    override fun seek(position: Long, timeUs: Long) {
        globalFirstPts = C.TIME_UNSET
        lastSampleTimeUsMap.clear()
        lastPtsMap.clear()
        hasSeenKeyframe.clear()
    }

    override fun release() {
        released = true
        globalFirstPts = C.TIME_UNSET
        lastSampleTimeUsMap.clear()
        lastPtsMap.clear()
        hasSeenKeyframe.clear()
        tracks.clear()
        trackTypes.clear()
        trackMimeTypes.clear()
        trackHasCsd.clear()
        trackWidths.clear()
        trackHeights.clear()
        ignoredStreams.clear()
    }
}
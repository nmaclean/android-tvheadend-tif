package com.nmaclean.tvheadend

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
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
    private val trackSampleRates = mutableMapOf<Long, Int>()
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
        Log.i(TAG, "=== HtspExtractor initialized ===")
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
            Log.i(TAG, "=== HTSP message received: $method -> keys=${msg.keys} ===")
        }

        when (method) {
            "subscriptionStart" -> handleSubscriptionStart(msg)
            "streamAdd" -> handleStreamAdd(msg)
            "muxpkt" -> handleMuxPkt(msg)
            "streamStart" -> Log.i(TAG, "=== HTSP Stream started ===")
            "streamStop" -> Log.i(TAG, "=== HTSP Stream stopped ===")
            "queueStatus" -> {
                Log.i(TAG, "HTSP queueStatus: packets=${msg["packets"]}, bytes=${msg["bytes"]}, delay=${msg["delay"]}")
            }
            else -> {
                if (msg.containsKey("stream") && msg.containsKey("payload")) {
                    handleMuxPkt(msg)
                } else {
                    Log.i(TAG, "Unhandled HTSP message method=$method keys=${msg.keys}")
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

        Log.i(TAG, "=== Adding stream index=$index type=$type codec=$codec metaSize=${meta?.size} ===")

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

            builder.setMaxInputSize(2 * 1024 * 1024)

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
            if (sampleRate > 0) {
                builder.setSampleRate(sampleRate)
                trackSampleRates[index] = sampleRate
            }
            if (channelCount > 0) builder.setChannelCount(channelCount)
            Log.d(TAG, "Audio stream index=$index: rawRate=$rawRate -> sampleRate=$sampleRate Hz, channels=$channelCount")
        }

        var hasCsd = false
        if (mimeType == MimeTypes.AUDIO_AAC) {
            val sampleRate = builder.build().sampleRate.let { if (it > 0) it else 48000 }
            val channels = builder.build().channelCount.let { if (it > 0) it else 2 }
            val asc = if (meta != null && meta.isNotEmpty()) meta else buildAacAudioSpecificConfig(sampleRate, channels)
            builder.setInitializationData(listOf(asc))
            hasCsd = true
            Log.i(TAG, "Configured AAC AudioSpecificConfig CSD for stream $index: $sampleRate Hz, $channels ch, metaHex=[${meta?.joinToString { "0x%02X".format(it) }}], ascHex=[${asc.joinToString { "0x%02X".format(it) }}]")
        } else if (mimeType == MimeTypes.VIDEO_H264 && meta != null && meta.isNotEmpty()) {
            val csdList = parseAnnexBCsd(meta, MimeTypes.VIDEO_H264)
            val initData = if (csdList.isNotEmpty()) {
                Log.d(TAG, "Parsed ${csdList.size} Annex-B NAL units (SPS/PPS) from H264 meta")
                if (csdList[0].size >= 5) {
                    parseSpsMetadata(csdList[0], builder)
                }
                csdList
            } else {
                try {
                    val avcConfig = AvcConfig.parse(ParsableByteArray(meta))
                    if (avcConfig.width > 0 && avcConfig.height > 0 && (trackWidths[index] ?: 0) == 0) {
                        builder.setWidth(avcConfig.width)
                        builder.setHeight(avcConfig.height)
                        trackWidths[index] = avcConfig.width
                        trackHeights[index] = avcConfig.height
                    }
                    Log.d(TAG, "Parsed avcC config from H264 meta: ${avcConfig.initializationData.size} CSD items, ${avcConfig.width}x${avcConfig.height}")
                    avcConfig.initializationData
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse H264 meta as Annex-B or avcC: ${e.message}")
                    emptyList()
                }
            }
            if (initData.isNotEmpty()) {
                builder.setInitializationData(initData)
                hasCsd = true
            }
        } else if (mimeType == MimeTypes.VIDEO_H265 && meta != null && meta.isNotEmpty()) {
            val csdList = parseAnnexBCsd(meta, MimeTypes.VIDEO_H265)
            if (csdList.isNotEmpty()) {
                builder.setInitializationData(csdList)
                hasCsd = true
                Log.d(TAG, "Parsed ${csdList.size} Annex-B NAL units (VPS/SPS/PPS) from HEVC meta")
            }
        }

        trackHasCsd[index] = hasCsd

        val format = builder.build()
        Log.i(TAG, "Configured track $index format: $format, hasCsd=$hasCsd")
        trackOutput.format(format)
        tracks[index] = trackOutput
    }

    private fun handleSubscriptionStart(msg: Map<String, Any>) {
        Log.i(TAG, "Handling subscriptionStart message")
        val globalMeta = msg["meta"] as? ByteArray
        val streamsList = msg["streams"] as? List<*>
        if (streamsList != null) {
            var hasVideo = false
            for (item in streamsList) {
                val streamMap = item as? Map<String, Any> ?: continue
                val type = streamMap["type"] as? String
                val handler = streamMap["handler"] as? String
                if (type == "video" || type == "H264" || type == "HEVC" || type == "MPEG2VIDEO" ||
                    handler == "H264" || handler == "HEVC" || handler == "MPEG2VIDEO") {
                    hasVideo = true
                }
                handleStreamAdd(streamMap, globalMeta)
            }

            // If Tvheadend omitted video stream 1 from subscriptionStart, pre-register stream 1 as video
            // BEFORE calling endTracks() so ExoPlayer allocates a video sample queue.
            if (!hasVideo && tracks[1L] == null) {
                Log.i(TAG, "Video stream 1 omitted from subscriptionStart. Pre-registering video track 1 before endTracks().")
                val syntheticVideoMap = mapOf<String, Any>(
                    "index" to 1L,
                    "type" to "video",
                    "handler" to "H264"
                )
                handleStreamAdd(syntheticVideoMap, globalMeta)
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

        var trackOutput = tracks[streamIndex]
        if (trackOutput == null) {
            if (tracksEnded) {
                // Cannot call extractorOutput.track() after endTracks() without causing ExoPlayer ArrayIndexOutOfBoundsException
                Log.w(TAG, "Discarding muxpkt for stream index $streamIndex received after endTracks()")
                return
            }

            val payload = msg["payload"] as? ByteArray
            val frameType = msg["frametype"] as? String
            val flags = (msg["flags"] as? Number)?.toInt() ?: 0

            val inferredType = if (streamIndex == 1L || frameType == "I" || frameType == "key" || (flags and 0x01) != 0 ||
                (payload != null && (containsKeyframe(payload, MimeTypes.VIDEO_H264) || containsKeyframe(payload, MimeTypes.VIDEO_H265) || containsKeyframe(payload, MimeTypes.VIDEO_MPEG2)))) {
                "video"
            } else {
                "audio"
            }

            val inferredCodec = if (inferredType == "video") "H264" else "AAC"
            Log.w(TAG, "Unregistered stream index $streamIndex received in muxpkt. Dynamically registering as type='$inferredType', codec='$inferredCodec'...")
            val syntheticStreamMap = mapOf<String, Any>(
                "index" to streamIndex,
                "type" to inferredType,
                "handler" to inferredCodec
            )
            handleStreamAdd(syntheticStreamMap)
            trackOutput = tracks[streamIndex]
            if (trackOutput == null) {
                Log.w(TAG, "Failed to dynamically register stream index $streamIndex")
                return
            }
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
            Log.i(TAG, "Locked global PTS baseline: $globalFirstPts us (from stream $streamIndex)")
        }

        val defaultStepUs = if (trackType == C.TRACK_TYPE_VIDEO) 40000L else 21333L
        val lastSampleTime = lastSampleTimeUsMap[streamIndex] ?: -1L

        val sampleTimeUs = if (rawTimeUs != C.TIME_UNSET && globalFirstPts != C.TIME_UNSET) {
            val relPtsUs = rawTimeUs - globalFirstPts + 1_000_000L // 1 second offset baseline
            if (relPtsUs < 0) 0L else relPtsUs
        } else {
            if (lastSampleTime >= 0L) {
                lastSampleTime + defaultStepUs
            } else {
                1_000_000L
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
        if (isAudio && muxPktCount <= 10) {
            Log.i(TAG, "Audio muxpkt #$muxPktCount stream $streamIndex size=${payload.size} payloadHex=[${payload.take(16).joinToString { "0x%02X".format(it) }}]")
        }

        val hasCsd = trackHasCsd[streamIndex] ?: false

        if (isAudio && mimeType == MimeTypes.AUDIO_AAC && payload.size >= 7 &&
            payload[0] == 0xFF.toByte() && (payload[1].toInt() and 0xF6) == 0xF0) {

            var offset = 0
            var sampleCount = 0

            while (offset + 7 <= payload.size) {
                if (payload[offset] != 0xFF.toByte() || (payload[offset + 1].toInt() and 0xF6) != 0xF0) {
                    break
                }

                val protectionAbsent = (payload[offset + 1].toInt() and 0x01) != 0
                val headerSize = if (protectionAbsent) 7 else 9

                if (offset + 6 >= payload.size) break

                val frameLength = ((payload[offset + 3].toInt() and 0x03) shl 11) or
                        ((payload[offset + 4].toInt() and 0xFF) shl 3) or
                        ((payload[offset + 5].toInt() and 0xFF) shr 5)

                if (frameLength <= headerSize || offset + frameLength > payload.size) {
                    val remainingSize = payload.size - offset
                    if (remainingSize > headerSize) {
                        val sampleSize = remainingSize - headerSize
                        val parsable = ParsableByteArray(payload, offset + remainingSize)
                        parsable.setPosition(offset + headerSize)
                        trackOutput.sampleData(parsable, sampleSize)
                        trackOutput.sampleMetadata(
                            sampleTimeUs,
                            C.BUFFER_FLAG_KEY_FRAME,
                            sampleSize,
                            0,
                            null
                        )
                    }
                    break
                }

                val sampleSize = frameLength - headerSize
                val parsable = ParsableByteArray(payload, offset + frameLength)
                parsable.setPosition(offset + headerSize)
                trackOutput.sampleData(parsable, sampleSize)

                val freqIdx = if (offset + 2 < payload.size) (payload[offset + 2].toInt() and 0x3C) shr 2 else -1
                val sampleRate = when (freqIdx) {
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
                    else -> trackSampleRates[streamIndex] ?: 48000
                }
                val frameDurationUs = (1024L * 1_000_000L) / sampleRate

                val frameTimeUs = sampleTimeUs + (sampleCount * frameDurationUs)
                trackOutput.sampleMetadata(
                    frameTimeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    sampleSize,
                    0,
                    null
                )

                offset += frameLength
                sampleCount++
            }
            return
        }

        if (!hasCsd && !isAudio && (mimeType == MimeTypes.VIDEO_H264 || mimeType == MimeTypes.VIDEO_H265) && isKeyframe) {
            val csdList = parseAnnexBCsd(payload, mimeType)
            if (csdList.isNotEmpty()) {
                val currentTrack = tracks[streamIndex]
                val width = trackWidths[streamIndex] ?: 0
                val height = trackHeights[streamIndex] ?: 0

                val builder = Format.Builder()
                    .setId(streamIndex.toString())
                    .setSampleMimeType(mimeType)
                    .setMaxInputSize(2 * 1024 * 1024)
                    .setInitializationData(csdList)

                if (width in 1..4096 && height in 1..2160) {
                    builder.setWidth(width)
                    builder.setHeight(height)
                }

                if (mimeType == MimeTypes.VIDEO_H264 && csdList[0].size >= 5) {
                    parseSpsMetadata(csdList[0], builder)
                }

                val format = builder.build()
                currentTrack?.format(format)
                trackHasCsd[streamIndex] = true
                Log.d(TAG, "Extracted inline CSD from keyframe for video stream $streamIndex (${if (width > 0) "${width}x${height}" else "dynamic resolution"}): ${csdList.size} NAL units, format=$format")
            }
        }

        val parsableByteArray = ParsableByteArray(payload)
        trackOutput.sampleData(parsableByteArray, payload.size)

        trackOutput.sampleMetadata(
            sampleTimeUs,
            if (isKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0,
            payload.size,
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

    private fun parseSpsMetadata(spsNalUnit: ByteArray, builder: Format.Builder) {
        try {
            if (spsNalUnit.size >= 5) {
                val spsData = NalUnitUtil.parseSpsNalUnit(spsNalUnit, 4, spsNalUnit.size)
                if (spsData.width > 0 && spsData.height > 0) {
                    builder.setWidth(spsData.width)
                    builder.setHeight(spsData.height)
                }
                if (spsData.pixelWidthHeightRatio != 1.0f && spsData.pixelWidthHeightRatio > 0f) {
                    builder.setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
                }
                val codecString = CodecSpecificDataUtil.buildAvcCodecString(
                    spsData.profileIdc,
                    spsData.constraintsFlagsAndReservedZero2Bits,
                    spsData.levelIdc
                )
                builder.setCodecs(codecString)
                Log.d(TAG, "Parsed H.264 SPS metadata: ${spsData.width}x${spsData.height}, sar=${spsData.pixelWidthHeightRatio}, codecs=$codecString")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SPS metadata: ${e.message}")
        }
    }

    private fun parseAnnexBCsd(payload: ByteArray, mimeType: String = MimeTypes.VIDEO_H264): List<ByteArray> {
        val offsets = mutableListOf<Int>()
        var i = 0
        while (i <= payload.size - 3) {
            val startCodeLen = if (i <= payload.size - 4 && payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()) {
                4
            } else if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 1.toByte()) {
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

        if (offsets.isEmpty()) return emptyList()

        val nalUnits = mutableListOf<ByteArray>()
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        for (idx in offsets.indices) {
            val start = offsets[idx]
            val end = if (idx + 1 < offsets.size) offsets[idx + 1] else payload.size
            val rawNal = payload.copyOfRange(start, end)

            val nal = if (rawNal.size >= 4 && rawNal[0] == 0.toByte() && rawNal[1] == 0.toByte() && rawNal[2] == 0.toByte() && rawNal[3] == 1.toByte()) {
                rawNal
            } else if (rawNal.size >= 3 && rawNal[0] == 0.toByte() && rawNal[1] == 0.toByte() && rawNal[2] == 1.toByte()) {
                byteArrayOf(0, 0, 0, 1) + rawNal.copyOfRange(3, rawNal.size)
            } else {
                continue
            }

            val nalHeaderIndex = 4
            if (nalHeaderIndex < nal.size) {
                if (mimeType == MimeTypes.VIDEO_H265) {
                    val nalType = (nal[nalHeaderIndex].toInt() shr 1) and 0x3F
                    if (nalType == 32) vps = nal
                    if (nalType == 33) sps = nal
                    if (nalType == 34) pps = nal
                } else {
                    val nalType = nal[nalHeaderIndex].toInt() and 0x1F
                    if (nalType == 7) sps = nal
                    if (nalType == 8) pps = nal
                }
            }
        }

        if (mimeType == MimeTypes.VIDEO_H265) {
            if (vps != null) nalUnits.add(vps)
            if (sps != null) nalUnits.add(sps)
            if (pps != null) nalUnits.add(pps)
        } else {
            if (sps != null) nalUnits.add(sps)
            if (pps != null) nalUnits.add(pps)
        }

        return nalUnits
    }



    private fun buildAacAudioSpecificConfig(sampleRate: Int, channelCount: Int, audioObjectType: Int = 2): ByteArray {
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
        val ascVal = (audioObjectType shl 11) or (freqIdx shl 7) or (channels shl 3)
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
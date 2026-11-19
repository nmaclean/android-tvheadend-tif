package com.nmaclean.tvheadend.player

import android.content.Context
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
import com.nmaclean.tvheadend.htsp.Htsmsg
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.telemetry.TuneStage
import com.nmaclean.tvheadend.telemetry.TuneTelemetryTracker
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer

@UnstableApi
class HtspExtractor(private val context: Context? = null) : Extractor {
    companion object {
        private const val TAG = "HtspExtractor"
    }

    private val isDebug: Boolean
        get() = TvhSettings.isStreamingDebugCached

    private var extractorOutput: ExtractorOutput? = null
    private val tracks = mutableMapOf<Long, TrackOutput>()
    private val trackTypes = mutableMapOf<Long, Int>()
    private val trackMimeTypes = mutableMapOf<Long, String>()
    private val trackSampleRates = mutableMapOf<Long, Int>()
    private val trackHasCsd = mutableMapOf<Long, Boolean>()
    private val trackWidths = mutableMapOf<Long, Int>()
    private val trackHeights = mutableMapOf<Long, Int>()
    private val trackFormats = mutableMapOf<Long, Format>()
    private val ignoredStreams = mutableSetOf<Long>()
    private val mp2Decoders = mutableMapOf<Long, Mp2Decoder>()
    private val isMp2Stream = mutableMapOf<Long, Boolean>()
    private var masterFirstPts: Long = C.TIME_UNSET
    private var masterFirstPtsIsVideo: Boolean = false
    private var masterOffset: Long = 0L
    private val lastRawTimeUsMap = mutableMapOf<Long, Long>()
    private val streamPtsOffsetMap = mutableMapOf<Long, Long>()
    private val lastSampleTimeUsMap = mutableMapOf<Long, Long>()
    private val lastPtsMap = mutableMapOf<Long, Long>()
    private val hasSeenKeyframe = mutableMapOf<Long, Boolean>()
    private var tracksEnded = false
    private var released = false
    private var muxPktCount = 0
    private var videoPktCount = 0
    private var audioPktCount = 0
    private var droppedPreRollCount = 0
    private var lastVideoSampleTimeUs: Long = C.TIME_UNSET
    private var lastAudioSampleTimeUs: Long = C.TIME_UNSET
    private var waitingForSubscriptionStart = true
    private var firstPacketReported = false
    private val lenBytes = ByteArray(4)
    private var msgBuffer = ByteArray(64 * 1024)
    private val sampleParsable = ParsableByteArray()
    private val muxPktView = Htsmsg.MuxPktView()

    override fun init(output: ExtractorOutput) {
        this.extractorOutput = output
        context?.let { TvhSettings.refreshStreamingDebug(it) }
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
        Log.i(TAG, "=== HtspExtractor initialized ===")
    }

    override fun sniff(input: ExtractorInput): Boolean {
        return true
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (released) return Extractor.RESULT_END_OF_INPUT

        try {
            input.readFully(lenBytes, 0, 4)
        } catch (e: EOFException) {
            return Extractor.RESULT_END_OF_INPUT
        }

        val length = ((lenBytes[0].toInt() and 0xFF) shl 24) or
            ((lenBytes[1].toInt() and 0xFF) shl 16) or
            ((lenBytes[2].toInt() and 0xFF) shl 8) or
            (lenBytes[3].toInt() and 0xFF)
        if (length <= 0 || length > 10 * 1024 * 1024) {
            Log.e(TAG, "Invalid HTSP message length: $length")
            throw IOException("Invalid HTSP message length: $length")
        }

        if (length > msgBuffer.size) {
            msgBuffer = ByteArray(length)
        }
        try {
            input.readFully(msgBuffer, 0, length)
        } catch (e: EOFException) {
            return Extractor.RESULT_END_OF_INPUT
        }

        if (Htsmsg.tryDecodeMuxPkt(msgBuffer, 0, length, muxPktView)) {
            if (!waitingForSubscriptionStart) {
                handleMuxPktView(msgBuffer, muxPktView)
            }
            return Extractor.RESULT_CONTINUE
        }

        @Suppress("UNCHECKED_CAST")
        val msg = Htsmsg.decode(msgBuffer, 0, length) as? Map<String, Any> ?: return Extractor.RESULT_CONTINUE
        val method = msg["method"] as? String

        when (method) {
            "subscriptionStart" -> {
                Log.i(TAG, "=== HTSP message received: subscriptionStart ===")
                handleSubscriptionStart(msg)
            }
            "streamAdd" -> {
                Log.i(TAG, "=== HTSP message received: streamAdd ===")
                handleStreamAdd(msg)
            }
            "muxpkt" -> {
                if (!waitingForSubscriptionStart) {
                    handleMuxPkt(msg)
                }
            }
            "streamStart" -> Log.d(TAG, "=== HTSP Stream started ===")
            "streamStop" -> Log.d(TAG, "=== HTSP Stream stopped ===")
            "queueStatus" -> {
                if (isDebug) {
                    val delay = msg["delay"] as? Number
                    val bDrops = msg["Bdrops"] as? Number
                    val pDrops = msg["Pdrops"] as? Number
                    val iDrops = msg["Idrops"] as? Number
                    Log.w(TAG, "TVH_STREAM_DEBUG: [SERVER QUEUE STATUS] delay=${delay}us, Bdrops=$bDrops, Pdrops=$pDrops, Idrops=$iDrops, packets=${msg["packets"]}, bytes=${msg["bytes"]}")
                }
            }
            "signalStatus" -> {
                if (isDebug && muxPktCount % 500 == 0) {
                    Log.d(TAG, "TVH_STREAM_DEBUG: [SERVER SIGNAL STATUS] SNR=${msg["feAbsoluteSNR"]}, Signal=${msg["feAbsoluteSignal"]}, BER=${msg["feBER"]}, UNC=${msg["feUNC"]}")
                }
            }
            "subscriptionGrace" -> {}
            "subscriptionStatus" -> {}
            else -> {
                if (!waitingForSubscriptionStart && msg.containsKey("stream") && msg.containsKey("payload")) {
                    handleMuxPkt(msg)
                }
            }
        }

        return Extractor.RESULT_CONTINUE
    }

    private fun handleStreamAdd(msg: Map<*, *>, globalMeta: ByteArray? = null) {
        waitingForSubscriptionStart = false
        val index = (msg["index"] as? Number)?.toLong() ?: return
        val type = msg["type"] as? String
        val codec = msg["handler"] as? String ?: msg["type"] as? String
        val meta = msg["meta"] as? ByteArray ?: globalMeta

        Log.i(TAG, "=== Adding stream index=$index type=$type codec=$codec metaSize=${meta?.size} ===")

        val codecUpper = codec?.uppercase() ?: ""

        val mimeType = when {
            type == "audio" -> when {
                codecUpper.contains("AAC") || codecUpper.contains("LATM") || codecUpper.contains("LOAS") || codecUpper.contains("MP4A") -> MimeTypes.AUDIO_AAC
                codecUpper.contains("EAC3") || codecUpper.contains("E-AC-3") || codecUpper.contains("AC3+") -> MimeTypes.AUDIO_E_AC3
                codecUpper.contains("AC3") || codecUpper.contains("AC-3") || codecUpper.contains("A52") || codecUpper.contains("DOLBY") -> MimeTypes.AUDIO_AC3
                codecUpper.contains("MP3") -> MimeTypes.AUDIO_MPEG
                codecUpper.contains("MP2") || codecUpper.contains("MPA") || codecUpper.contains("MPEG2AUDIO") || codecUpper.contains("MPEG1AUDIO") || codecUpper.contains("MPEG") || codecUpper.contains("MPEG2") -> {
                    isMp2Stream[index] = true
                    MimeTypes.AUDIO_RAW
                }
                codecUpper.contains("DTS") || codecUpper.contains("DCA") -> MimeTypes.AUDIO_DTS
                codecUpper.contains("OPUS") -> MimeTypes.AUDIO_OPUS
                codecUpper.contains("PCM") || codecUpper.contains("LPCM") -> MimeTypes.AUDIO_RAW
                else -> MimeTypes.AUDIO_AAC
            }
            type == "video" -> when {
                codecUpper.contains("H264") || codecUpper.contains("AVC") -> MimeTypes.VIDEO_H264
                codecUpper.contains("HEVC") || codecUpper.contains("H265") -> MimeTypes.VIDEO_H265
                codecUpper.contains("MPEG2") || codecUpper.contains("MPEG") -> MimeTypes.VIDEO_MPEG2
                else -> MimeTypes.VIDEO_H264
            }
            type == "subtitle" || type == "teletext" || codecUpper.contains("DVBSUB") || codecUpper.contains("SUBTITLE") || codecUpper.contains("TEXT") || codecUpper.contains("TELETEXT") -> when {
                codecUpper.contains("DVBSUB") || codecUpper.contains("DVB") -> MimeTypes.APPLICATION_DVBSUBS
                codecUpper.contains("TELETEXT") -> "application/x-teletext"
                codecUpper.contains("PGS") -> MimeTypes.APPLICATION_PGS
                codecUpper.contains("WEBVTT") -> MimeTypes.TEXT_VTT
                codecUpper.contains("TTML") || codecUpper.contains("EBU_TT") -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            codecUpper.contains("H264") || codecUpper.contains("AVC") -> MimeTypes.VIDEO_H264
            codecUpper.contains("HEVC") || codecUpper.contains("H265") -> MimeTypes.VIDEO_H265
            codecUpper.contains("MPEG2AUDIO") || codecUpper.contains("MPEG1AUDIO") || codecUpper.contains("MP2") || codecUpper.contains("MPA") -> {
                isMp2Stream[index] = true
                MimeTypes.AUDIO_RAW
            }
            codecUpper.contains("MPEG2VIDEO") || codecUpper.contains("MPEG2") -> MimeTypes.VIDEO_MPEG2
            codecUpper.contains("AAC") || codecUpper.contains("LATM") || codecUpper.contains("LOAS") || codecUpper.contains("MP4A") -> MimeTypes.AUDIO_AAC
            codecUpper.contains("AC3") || codecUpper.contains("AC-3") || codecUpper.contains("A52") || codecUpper.contains("DOLBY") -> MimeTypes.AUDIO_AC3
            codecUpper.contains("EAC3") || codecUpper.contains("E-AC-3") || codecUpper.contains("AC3+") -> MimeTypes.AUDIO_E_AC3
            codecUpper.contains("DTS") || codecUpper.contains("DCA") -> MimeTypes.AUDIO_DTS
            codecUpper.contains("OPUS") -> MimeTypes.AUDIO_OPUS
            codecUpper.contains("PCM") || codecUpper.contains("LPCM") -> MimeTypes.AUDIO_RAW
            else -> {
                Log.w(TAG, "Unknown codec: $codec, type: $type. Adding to ignored streams.")
                ignoredStreams.add(index)
                null
            }
        } ?: return

        val trackType = when {
            type == "video" || mimeType.startsWith("video/") -> C.TRACK_TYPE_VIDEO
            type == "audio" || mimeType.startsWith("audio/") -> C.TRACK_TYPE_AUDIO
            type == "subtitle" || type == "teletext" || mimeType.startsWith("text/") || mimeType.startsWith("application/") -> C.TRACK_TYPE_TEXT
            else -> C.TRACK_TYPE_AUDIO
        }
        val trackOutput = extractorOutput?.track(index.toInt(), trackType) ?: return

        trackTypes[index] = trackType
        trackMimeTypes[index] = mimeType

        val lang = (msg["language"] as? String) ?: (msg["lang"] as? String)

        val builder = Format.Builder()
            .setId(index.toString())
            .setSampleMimeType(mimeType)

        if (!lang.isNullOrEmpty()) {
            builder.setLanguage(lang)
        }

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
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
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

            if (isMp2Stream[index] == true) {
                // Mp2Decoder always outputs 16-bit stereo PCM (even for mono MP2 tracks)
                builder.setChannelCount(2)
                if (sampleRate <= 0) {
                    builder.setSampleRate(48000)
                    trackSampleRates[index] = 48000
                }
                builder.setPcmEncoding(C.ENCODING_PCM_16BIT)
            } else if (channelCount > 0) {
                builder.setChannelCount(channelCount)
            }

            Log.d(TAG, "Audio stream index=$index: rawRate=$rawRate -> sampleRate=$sampleRate Hz, channels=$channelCount, isMp2=${isMp2Stream[index]}")
        }

        var hasCsd = false

        if (mimeType == MimeTypes.AUDIO_AAC) {
            val sampleRate = builder.build().sampleRate.let { if (it > 0) it else 48000 }
            val channels = builder.build().channelCount.let { if (it > 0) it else 2 }
            val asc = if (meta != null && meta.isNotEmpty()) meta else buildAacAudioSpecificConfig(sampleRate, channels)
            builder.setInitializationData(listOf(asc))
            hasCsd = true
            Log.i(TAG, "Configured AAC AudioSpecificConfig CSD for stream $index: $sampleRate Hz, $channels ch, metaSize=${meta?.size ?: 0}, ascHex=[${asc.joinToString { "0x%02X".format(it) }}]")
        } else if (mimeType == MimeTypes.VIDEO_H264 && meta != null && meta.isNotEmpty()) {
            val csdList = parseAnnexBCsd(meta, MimeTypes.VIDEO_H264)
            val initData = if (csdList.isNotEmpty()) {
                Log.d(TAG, "Parsed ${csdList.size} Annex-B NAL units (SPS/PPS) from H264 meta")
                val spsNal = csdList.firstOrNull { it.size >= 5 && (it[4].toInt() and 0x1F) == 7 }
                if (spsNal != null) {
                    parseSpsMetadata(spsNal, builder)
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
        } else if (mimeType == MimeTypes.VIDEO_MPEG2) {
            if (meta != null && meta.isNotEmpty()) {
                var i = 0
                var foundSeqHeader = false
                while (i + 6 < meta.size) {
                    if (meta[i] == 0.toByte() && meta[i + 1] == 0.toByte() && meta[i + 2] == 1.toByte() && (meta[i + 3].toInt() and 0xFF) == 0xB3) {
                        val b4 = meta[i + 4].toInt() and 0xFF
                        val b5 = meta[i + 5].toInt() and 0xFF
                        val b6 = meta[i + 6].toInt() and 0xFF
                        val w = (b4 shl 4) or (b5 ushr 4)
                        val h = ((b5 and 0x0F) shl 8) or b6
                        val seqHeader = extractMpeg2HeaderCsd(meta, i)
                        builder.setInitializationData(listOf(seqHeader))
                        if (w in 1..4096 && h in 1..2160) {
                            builder.setWidth(w)
                            builder.setHeight(h)
                            trackWidths[index] = w
                            trackHeights[index] = h
                        }
                        hasCsd = true
                        foundSeqHeader = true
                        Log.d(TAG, "Parsed valid MPEG2 Sequence Header from meta for stream $index: ${w}x${h}")
                        break
                    }
                    i++
                }
                if (!foundSeqHeader) {
                    Log.d(TAG, "MPEG2 meta present (${meta.size} bytes) but does not contain Sequence Header (0x000001B3). Deferring CSD setup to keyframe.")
                }
            }
            Log.d(TAG, "Configured MPEG2 video format for stream $index (meta size=${meta?.size ?: 0}, hasCsd=$hasCsd)")
        } else if (mimeType == MimeTypes.APPLICATION_DVBSUBS) {
            val compId = (msg["composition_id"] as? Number)?.toInt()
                ?: (msg["compositionId"] as? Number)?.toInt()
                ?: (msg["composition-id"] as? Number)?.toInt()
                ?: (msg["subid"] as? Number)?.toInt()
                ?: (meta?.takeIf { it.size >= 2 }?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) })
                ?: 1
            val ancId = (msg["ancillary_id"] as? Number)?.toInt()
                ?: (msg["ancillaryId"] as? Number)?.toInt()
                ?: (msg["ancillary-id"] as? Number)?.toInt()
                ?: (msg["subid"] as? Number)?.toInt()
                ?: (meta?.takeIf { it.size >= 4 }?.let { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) })
                ?: compId

            val csd = if (meta != null && meta.size >= 4) {
                meta
            } else {
                byteArrayOf(
                    ((compId shr 8) and 0xFF).toByte(),
                    (compId and 0xFF).toByte(),
                    ((ancId shr 8) and 0xFF).toByte(),
                    (ancId and 0xFF).toByte()
                )
            }
            builder.setInitializationData(listOf(csd))
            hasCsd = true
            Log.i(TAG, "Configured DVB Subtitle CSD for stream $index: compositionId=$compId, ancillaryId=$ancId")
        }

        trackHasCsd[index] = hasCsd

        val format = builder.build()
        trackFormats[index] = format
        Log.i(TAG, "Configured track $index format: $format, hasCsd=$hasCsd")

        trackOutput.format(format)
        tracks[index] = trackOutput
    }

    private fun handleSubscriptionStart(msg: Map<String, Any>) {
        Log.i(TAG, "Handling subscriptionStart message")
        waitingForSubscriptionStart = false
        masterFirstPts = C.TIME_UNSET
        masterOffset = 0L
        droppedPreRollCount = 0
        lastRawTimeUsMap.clear()
        lastSampleTimeUsMap.clear()
        hasSeenKeyframe.clear()
        trackFormats.clear()

        val globalMeta = msg["meta"] as? ByteArray
        val streamsList = msg["streams"] as? List<*>

        var hasVideo = false
        if (streamsList != null) {
            for (item in streamsList) {
                val streamMap = item as? Map<*, *> ?: continue
                val type = (streamMap["type"] as? String)?.trim() ?: ""
                val handler = (streamMap["handler"] as? String)?.trim() ?: ""
                if (type == "video" || type == "H264" || type == "HEVC" || type == "MPEG2VIDEO" ||
                    handler == "H264" || handler == "HEVC" || handler == "MPEG2VIDEO"
                ) {
                    hasVideo = true
                }
                handleStreamAdd(streamMap, globalMeta)
            }
        }

        // Pre-register video stream 1 if subscriptionStart omitted video (e.g. faststart = 1 before first IDR arrives)
        // so that an early audio muxpkt calling endTracks() does not lock out the video track.
        if (!hasVideo && tracks[1L] == null) {
            Log.i(TAG, "No video stream in subscriptionStart; pre-registering stream 1 as video/H264 before endTracks()")
            val syntheticVideoMap = mapOf<String, Any>(
                "index" to 1L,
                "type" to "video",
                "handler" to "H264"
            )
            handleStreamAdd(syntheticVideoMap, globalMeta)
        }

        extractorOutput?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    private fun handleMuxPktView(buffer: ByteArray, pkt: Htsmsg.MuxPktView) {
        val ptsUs = if (pkt.hasPts) pkt.pts else C.TIME_UNSET
        val dtsUs = if (pkt.hasDts) pkt.dts else C.TIME_UNSET
        val isKeyFlag = pkt.frameTypeIsKey || ((pkt.flags and 0x01) != 0)
        processMuxPkt(
            streamIndex = pkt.stream,
            ptsUs = ptsUs,
            dtsUs = dtsUs,
            isKeyFlag = isKeyFlag,
            hasExplicitFrameType = pkt.hasFrameType,
            buffer = buffer,
            payloadOffset = pkt.payloadOffset,
            payloadLength = pkt.payloadLength
        )
    }

    private fun handleMuxPkt(msg: Map<String, Any>) {
        val streamIndex = (msg["stream"] as? Number)?.toLong() ?: return
        val payload = msg["payload"] as? ByteArray ?: return
        val ptsUs = (msg["pts"] as? Number)?.toLong() ?: C.TIME_UNSET
        val dtsUs = (msg["dts"] as? Number)?.toLong() ?: C.TIME_UNSET
        val rawFrameType = msg["frametype"]
        val hasExplicitFrameType = rawFrameType != null
        val frameTypeIsKey = when (rawFrameType) {
            is String -> rawFrameType == "I" || rawFrameType == "key"
            is Number -> {
                val ft = rawFrameType.toInt()
                ft == 'I'.code || ft == 'i'.code || ft == 1
            }
            else -> false
        }
        val flags = (msg["flags"] as? Number)?.toInt() ?: 0
        val isKeyFlag = frameTypeIsKey || (flags and 0x01) != 0
        processMuxPkt(
            streamIndex = streamIndex,
            ptsUs = ptsUs,
            dtsUs = dtsUs,
            isKeyFlag = isKeyFlag,
            hasExplicitFrameType = hasExplicitFrameType,
            buffer = payload,
            payloadOffset = 0,
            payloadLength = payload.size
        )
    }

    private fun processMuxPkt(
        streamIndex: Long,
        ptsUs: Long,
        dtsUs: Long,
        isKeyFlag: Boolean,
        hasExplicitFrameType: Boolean = false,
        buffer: ByteArray,
        payloadOffset: Int,
        payloadLength: Int
    ) {
        try {
            if (!firstPacketReported) {
                firstPacketReported = true
                TuneTelemetryTracker.shared.recordStage(TuneStage.FIRST_PACKET)
            }

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

                val inferredType = if (streamIndex == 1L || isKeyFlag ||
                    containsKeyframe(buffer, payloadOffset, payloadLength, MimeTypes.VIDEO_H264) ||
                    containsKeyframe(buffer, payloadOffset, payloadLength, MimeTypes.VIDEO_H265) ||
                    containsKeyframe(buffer, payloadOffset, payloadLength, MimeTypes.VIDEO_MPEG2)
                ) {
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
            val isAudio = (trackType == C.TRACK_TYPE_AUDIO)
            val isText = (trackType == C.TRACK_TYPE_TEXT)

            if (!tracksEnded && tracks.isNotEmpty()) {
                extractorOutput?.endTracks()
                tracksEnded = true
            }

            val presentationTimeUs = when {
                ptsUs != C.TIME_UNSET -> ptsUs
                dtsUs != C.TIME_UNSET -> dtsUs
                else -> C.TIME_UNSET
            }

            val decodeTimeUs = when {
                dtsUs != C.TIME_UNSET -> dtsUs
                ptsUs != C.TIME_UNSET -> ptsUs
                else -> C.TIME_UNSET
            }

            var isKeyframe = isAudio || isText || isKeyFlag
            if (!isKeyframe && (!hasExplicitFrameType || hasSeenKeyframe[streamIndex] != true)) {
                isKeyframe = containsKeyframe(buffer, payloadOffset, payloadLength, mimeType)
            }

            if (trackType == C.TRACK_TYPE_VIDEO) {
                if (isKeyframe) {
                    hasSeenKeyframe[streamIndex] = true
                    if (isDebug) {
                        Log.d(TAG, "TVH_STREAM_DEBUG: [MUXPKT KEYFRAME] Stream $streamIndex ($mimeType) pts=$ptsUs us, dts=$dtsUs us, size=$payloadLength")
                    }
                } else if (hasSeenKeyframe[streamIndex] != true && droppedPreRollCount < 75) {
                    // Drop orphan pre-keyframe P/B-frames sent by faststart=1 so MediaCodec starts cleanly on an I-frame
                    droppedPreRollCount++
                    return
                }
            } else if (isAudio) {
                val hasVideoTrack = trackTypes.values.any { it == C.TRACK_TYPE_VIDEO }
                val anyVideoKeyframeSeen = trackTypes.any { (id, type) ->
                    type == C.TRACK_TYPE_VIDEO && hasSeenKeyframe[id] == true
                }
                if (hasVideoTrack && !anyVideoKeyframeSeen && droppedPreRollCount < 75) {
                    // Hold back pre-keyframe audio so audio doesn't start ahead of the first video keyframe
                    droppedPreRollCount++
                    return
                }
                hasSeenKeyframe[streamIndex] = true
                if (isDebug && (audioPktCount < 3 || audioPktCount % 250 == 0)) {
                    Log.d(TAG, "TVH_STREAM_DEBUG: [MUXPKT KEYFRAME] Stream $streamIndex ($mimeType) pts=$ptsUs us, dts=$dtsUs us, size=$payloadLength")
                }
            } else if (isKeyframe) {
                hasSeenKeyframe[streamIndex] = true
            }

        val defaultStepUs = if (trackType == C.TRACK_TYPE_VIDEO) 40000L else 21333L

        val packetMinTime = when {
            ptsUs != C.TIME_UNSET && dtsUs != C.TIME_UNSET -> minOf(ptsUs, dtsUs)
            ptsUs != C.TIME_UNSET -> ptsUs
            dtsUs != C.TIME_UNSET -> dtsUs
            else -> C.TIME_UNSET
        }

        if (packetMinTime != C.TIME_UNSET) {
            if (masterFirstPts == C.TIME_UNSET) {
                masterFirstPts = packetMinTime
                masterFirstPtsIsVideo = (trackType == C.TRACK_TYPE_VIDEO)
                masterOffset = 0L - masterFirstPts
                Log.i(TAG, "Set masterFirstPts=$masterFirstPts us, masterOffset=$masterOffset us (stream=$streamIndex, isVideo=$masterFirstPtsIsVideo)")
            }
        }

        val lastDecode = lastRawTimeUsMap[streamIndex]
        val lastSample = lastSampleTimeUsMap[streamIndex]

        if (isDebug && lastDecode != null && decodeTimeUs != C.TIME_UNSET && (trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO)) {
            val decodeDelta = decodeTimeUs - lastDecode
            if (decodeDelta > 500_000L || decodeDelta < -500_000L) {
                Log.w(TAG, "TVH_STREAM_DEBUG: [PTS JUMP DETECTED] Stream $streamIndex ($mimeType) decode delta jump: decodeDelta=${decodeDelta / 1000}ms (lastDecode=${lastDecode}us -> decodeTimeUs=${decodeTimeUs}us)")
            }
            if (lastVideoSampleTimeUs != C.TIME_UNSET && lastAudioSampleTimeUs != C.TIME_UNSET) {
                val avDriftUs = Math.abs(lastVideoSampleTimeUs - lastAudioSampleTimeUs)
                if (avDriftUs > 3_000_000L && muxPktCount % 200 == 0) {
                    Log.w(TAG, "TVH_STREAM_DEBUG: [A/V SYNC DRIFT] High A/V Sync Drift: ${avDriftUs / 1000}ms (videoTime=${lastVideoSampleTimeUs}us, audioTime=${lastAudioSampleTimeUs}us)")
                } else if (avDriftUs > 500_000L && muxPktCount % 200 == 0) {
                    Log.d(TAG, "TVH_STREAM_DEBUG: [STREAM INTERLEAVING GAP] Packet arrival timestamp gap: ${avDriftUs / 1000}ms (videoTime=${lastVideoSampleTimeUs}us, audioTime=${lastAudioSampleTimeUs}us)")
                }
            }
        }

        val calculatedSampleTime = when {
            presentationTimeUs != C.TIME_UNSET -> presentationTimeUs + masterOffset
            lastSample != null -> lastSample + defaultStepUs
            else -> 0L
        }

        val sampleTimeUs: Long = if (lastSample != null) {
            if (isAudio) {
                maxOf(calculatedSampleTime, lastSample)
            } else {
                // Video B-frames reorder PTS up to 1 second backward relative to last PTS, but clamp larger regressions
                if (calculatedSampleTime < lastSample && lastSample - calculatedSampleTime > 1_000_000L) {
                    lastSample
                } else {
                    calculatedSampleTime
                }
            }
        } else {
            calculatedSampleTime
        }

        if (decodeTimeUs != C.TIME_UNSET) {
            lastRawTimeUsMap[streamIndex] = decodeTimeUs
        }
        lastSampleTimeUsMap[streamIndex] = sampleTimeUs

        muxPktCount++
        if (isAudio) {
            audioPktCount++
            lastAudioSampleTimeUs = sampleTimeUs
            if (audioPktCount == 1) {
                Log.i(TAG, "DIAGNOSTIC FIRST AUDIO SAMPLE OUTPUT: pts=$ptsUs us -> sampleTimeUs=$sampleTimeUs us")
            }
        } else {
            videoPktCount++
            lastVideoSampleTimeUs = sampleTimeUs
            if (videoPktCount == 1) {
                Log.i(TAG, "First video sample output: pts=$ptsUs us -> sampleTimeUs=$sampleTimeUs us")
            }
        }

        if (isAudio && mimeType == MimeTypes.AUDIO_AAC && payloadLength >= 7 && buffer[payloadOffset] == 0xFF.toByte() && (buffer[payloadOffset + 1].toInt() and 0xF6) == 0xF0) {
            var offset = 0
            var sampleCount = 0
            var lastFrameDurationUs = 21333L
            while (offset + 7 <= payloadLength) {
                val frameStart = payloadOffset + offset
                if (buffer[frameStart] != 0xFF.toByte() || (buffer[frameStart + 1].toInt() and 0xF6) != 0xF0) {
                    break
                }
                val protectionAbsent = (buffer[frameStart + 1].toInt() and 0x01) != 0
                val headerSize = if (protectionAbsent) 7 else 9
                if (offset + 6 >= payloadLength) break

                val frameLength = ((buffer[frameStart + 3].toInt() and 0x03) shl 11) or
                        ((buffer[frameStart + 4].toInt() and 0xFF) shl 3) or
                        ((buffer[frameStart + 5].toInt() and 0xFF) shr 5)

                if (frameLength <= headerSize || offset + frameLength > payloadLength) {
                    val remainingSize = payloadLength - offset
                    if (remainingSize > headerSize) {
                        val sampleSize = remainingSize - headerSize
                        sampleParsable.reset(buffer, frameStart + remainingSize)
                        sampleParsable.setPosition(frameStart + headerSize)
                        trackOutput.sampleData(sampleParsable, sampleSize)
                        val frameTimeUs = sampleTimeUs + (sampleCount * lastFrameDurationUs)
                        trackOutput.sampleMetadata(
                            frameTimeUs,
                            C.BUFFER_FLAG_KEY_FRAME,
                            sampleSize,
                            0,
                            null
                        )
                        sampleCount++
                    }
                    break
                }

                val sampleSize = frameLength - headerSize
                sampleParsable.reset(buffer, frameStart + frameLength)
                sampleParsable.setPosition(frameStart + headerSize)
                trackOutput.sampleData(sampleParsable, sampleSize)

                val freqIdx = if (offset + 2 < payloadLength) (buffer[frameStart + 2].toInt() and 0x3C) shr 2 else -1
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
                lastFrameDurationUs = frameDurationUs
                val calculatedFrameTimeUs = sampleTimeUs + (sampleCount * frameDurationUs)
                val frameTimeUs = if (lastSample != null) maxOf(calculatedFrameTimeUs, lastSample) else calculatedFrameTimeUs

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
            if (sampleCount > 0) {
                val lastCalculated = sampleTimeUs + ((sampleCount - 1) * lastFrameDurationUs)
                lastSampleTimeUsMap[streamIndex] = if (lastSample != null) maxOf(lastCalculated, lastSample) else lastCalculated
            }
            return
        }

        val hasCsd = trackHasCsd[streamIndex] ?: false
        val currentWidth = trackWidths[streamIndex] ?: 0
        val currentHeight = trackHeights[streamIndex] ?: 0
        val needsFormatUpdate = !hasCsd || currentWidth == 0 || currentHeight == 0

        if (!isAudio && isKeyframe && needsFormatUpdate) {
            val existingFormat = trackFormats[streamIndex]
            if (mimeType == MimeTypes.VIDEO_H264 || mimeType == MimeTypes.VIDEO_H265) {
                val csdList = parseAnnexBCsd(buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength), mimeType)
                if (csdList.isNotEmpty()) {
                    val currentTrack = tracks[streamIndex]

                    val builder = existingFormat?.buildUpon() ?: Format.Builder()
                        .setId(streamIndex.toString())
                        .setSampleMimeType(mimeType)
                        .setMaxInputSize(2 * 1024 * 1024)

                    builder.setInitializationData(csdList)

                    if (mimeType == MimeTypes.VIDEO_H264) {
                        val spsNal = csdList.firstOrNull { it.size >= 5 && (it[4].toInt() and 0x1F) == 7 }
                        if (spsNal != null) {
                            parseSpsMetadata(spsNal, builder, streamIndex)
                        }
                    }

                    val width = trackWidths[streamIndex] ?: 0
                    val height = trackHeights[streamIndex] ?: 0

                    val format = builder.build()
                    currentTrack?.format(format)
                    trackFormats[streamIndex] = format
                    trackHasCsd[streamIndex] = true
                    Log.d(TAG, "Extracted inline CSD from keyframe for video stream $streamIndex (${if (width > 0) "${width}x${height}" else "dynamic resolution"}): ${csdList.size} NAL units, format=$format")
                }
            } else if (mimeType == MimeTypes.VIDEO_MPEG2) {
                var i = payloadOffset
                val end = payloadOffset + payloadLength
                var parsedWidth = 0
                var parsedHeight = 0
                while (i + 6 < end) {
                    if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte() && (buffer[i + 3].toInt() and 0xFF) == 0xB3) {
                        val b4 = buffer[i + 4].toInt() and 0xFF
                        val b5 = buffer[i + 5].toInt() and 0xFF
                        val b6 = buffer[i + 6].toInt() and 0xFF
                        parsedWidth = (b4 shl 4) or (b5 ushr 4)
                        parsedHeight = ((b5 and 0x0F) shl 8) or b6
                        break
                    }
                    i++
                }
                if (parsedWidth in 1..4096 && parsedHeight in 1..2160) {
                    val currentTrack = tracks[streamIndex]
                    val builder = existingFormat?.buildUpon() ?: Format.Builder()
                        .setId(streamIndex.toString())
                        .setSampleMimeType(mimeType)

                    builder.setWidth(parsedWidth).setHeight(parsedHeight)

                    val format = builder.build()
                    currentTrack?.format(format)
                    trackFormats[streamIndex] = format
                    trackHasCsd[streamIndex] = true
                    trackWidths[streamIndex] = parsedWidth
                    trackHeights[streamIndex] = parsedHeight
                    Log.d(TAG, "Parsed MPEG2 Sequence Header resolution: ${parsedWidth}x${parsedHeight} for video stream $streamIndex, format=$format")
                }
            }
        }

        if (isMp2Stream[streamIndex] == true) {
            val decoder = mp2Decoders.getOrPut(streamIndex) { Mp2Decoder() }
            val pcmData = decoder.decodeFrame(buffer, payloadOffset, payloadLength)
            if (pcmData != null) {
                sampleParsable.reset(pcmData, pcmData.size)
                trackOutput.sampleData(sampleParsable, pcmData.size)
                trackOutput.sampleMetadata(
                    sampleTimeUs,
                    C.BUFFER_FLAG_KEY_FRAME,
                    pcmData.size,
                    0,
                    null
                )
            } else {
                Log.w(TAG, "Mp2Decoder failed to decode frame for stream $streamIndex (size=$payloadLength); skipping frame")
            }
        } else {
            sampleParsable.reset(buffer, payloadOffset + payloadLength)
            sampleParsable.setPosition(payloadOffset)
            trackOutput.sampleData(sampleParsable, payloadLength)
            trackOutput.sampleMetadata(
                sampleTimeUs,
                if (isKeyframe) C.BUFFER_FLAG_KEY_FRAME else 0,
                payloadLength,
                0,
                null
            )
        }
        } catch (e: Throwable) {
            Log.e(TAG, "TVH_STREAM_DEBUG: [MUXPKT ERROR] Exception processing muxpkt: ${e.message}", e)
        }
    }

    private fun containsKeyframe(payload: ByteArray, offset: Int, length: Int, mimeType: String): Boolean {
        val end = minOf(payload.size, offset + length)
        if (mimeType == MimeTypes.VIDEO_MPEG2) {
            var i = offset
            while (i + 5 < end) {
                if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 1.toByte() && payload[i + 3] == 0.toByte()) {
                    val b5 = payload[i + 5].toInt() and 0xFF
                    val pictureCodingType = (b5 ushr 3) and 0x07
                    if (pictureCodingType == 1) return true
                }
                i++
            }
            return false
        }

        var i = offset
        while (i < end - 3) {
            val startCodeLen = if (payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 0.toByte() && payload[i + 3] == 1.toByte()) {
                4
            } else if (i < end - 2 && payload[i] == 0.toByte() && payload[i + 1] == 0.toByte() && payload[i + 2] == 1.toByte()) {
                3
            } else {
                0
            }

            if (startCodeLen > 0) {
                val nalStart = i + startCodeLen
                if (nalStart < end) {
                    val nalHeaderByte = payload[nalStart].toInt() and 0xFF
                    if (mimeType == MimeTypes.VIDEO_H265) {
                        val nalUnitType = (nalHeaderByte ushr 1) and 0x3F
                        if (nalUnitType in 16..21 || nalUnitType in 32..34) {
                            return true
                        }
                    } else {
                        val nalUnitType = nalHeaderByte and 0x1F
                        if (nalUnitType == 5 || nalUnitType == 7) {
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

    private fun parseSpsMetadata(spsNalUnit: ByteArray, builder: Format.Builder, streamIndex: Long = -1L) {
        try {
            if (spsNalUnit.size >= 5 && (spsNalUnit[4].toInt() and 0x1F) == 7) {
                val spsData = NalUnitUtil.parseSpsNalUnit(spsNalUnit, 4, spsNalUnit.size)
                if (spsData.width > 0 && spsData.height > 0) {
                    builder.setWidth(spsData.width)
                    builder.setHeight(spsData.height)
                    if (streamIndex >= 0L) {
                        trackWidths[streamIndex] = spsData.width
                        trackHeights[streamIndex] = spsData.height
                    }
                }
                if (spsData.pixelWidthHeightRatio > 0f) {
                    builder.setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
                }

                val codecString = CodecSpecificDataUtil.buildAvcCodecString(
                    spsData.profileIdc,
                    spsData.constraintsFlagsAndReservedZero2Bits,
                    spsData.levelIdc
                )
                if (codecString.isNotEmpty()) {
                    builder.setCodecs(codecString)
                }
                Log.d(TAG, "Parsed H.264 SPS metadata: ${spsData.width}x${spsData.height}, sar=${spsData.pixelWidthHeightRatio}, codecs=$codecString")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse SPS metadata: ${e.message}")
        }
    }

    private fun extractMpeg2HeaderCsd(payload: ByteArray, startIndex: Int): ByteArray {
        var j = startIndex + 4
        while (j + 3 < payload.size) {
            if (payload[j] == 0.toByte() && payload[j + 1] == 0.toByte() && payload[j + 2] == 1.toByte()) {
                val code = payload[j + 3].toInt() and 0xFF
                if (code == 0x00 || code in 0x01..0xAF) {
                    return payload.copyOfRange(startIndex, j)
                }
            }
            j++
        }
        val fallbackEnd = minOf(payload.size, startIndex + 32)
        return payload.copyOfRange(startIndex, fallbackEnd)
    }

    private fun parseAnnexBCsd(payload: ByteArray, mimeType: String = MimeTypes.VIDEO_H264): List<ByteArray> {
        val offsets = mutableListOf<Int>()
        val scanLimit = minOf(payload.size, 4096)
        var i = 0
        while (i <= scanLimit - 3) {
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
        waitingForSubscriptionStart = true
        masterFirstPts = C.TIME_UNSET
        masterFirstPtsIsVideo = false
        masterOffset = 0L
        muxPktCount = 0
        videoPktCount = 0
        audioPktCount = 0
        droppedPreRollCount = 0
        lastVideoSampleTimeUs = C.TIME_UNSET
        lastAudioSampleTimeUs = C.TIME_UNSET
        lastRawTimeUsMap.clear()
        streamPtsOffsetMap.clear()
        lastSampleTimeUsMap.clear()
        lastPtsMap.clear()
        hasSeenKeyframe.clear()
    }

    override fun release() {
        released = true
        waitingForSubscriptionStart = true
        masterFirstPts = C.TIME_UNSET
        masterFirstPtsIsVideo = false
        masterOffset = 0L
        muxPktCount = 0
        videoPktCount = 0
        audioPktCount = 0
        droppedPreRollCount = 0
        lastVideoSampleTimeUs = C.TIME_UNSET
        lastAudioSampleTimeUs = C.TIME_UNSET
        lastRawTimeUsMap.clear()
        streamPtsOffsetMap.clear()
        lastSampleTimeUsMap.clear()
        lastPtsMap.clear()
        hasSeenKeyframe.clear()
        tracks.clear()
        trackTypes.clear()
        trackMimeTypes.clear()
        trackHasCsd.clear()
        trackWidths.clear()
        trackHeights.clear()
        trackFormats.clear()
        ignoredStreams.clear()
    }
}

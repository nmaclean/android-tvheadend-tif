package com.nmaclean.tvheadend.iptv.epg

import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

object StreamingXmltvParser {

    private val xmltvDateFormat1 = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val xmltvDateFormat2 = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(inputStream: InputStream, minEpochMs: Long = 0L): EpgParseResult {
        val buffered = if (inputStream is BufferedInputStream) inputStream else BufferedInputStream(inputStream)
        buffered.mark(1024)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val isGzip = (b1 == 0x1f && b2 == 0x8b)
        try { Log.i("StreamingXmltvParser", "Starting XMLTV parsing (isGzip=$isGzip)...") } catch (_: Throwable) {}

        val programmesMap = mutableMapOf<String, MutableList<EpgProgramme>>()
        val channelNamesMap = mutableMapOf<String, String>()
        val channelIconsMap = mutableMapOf<String, String>()
        var skippedCount = 0
        var totalProgrammesParsed = 0

        val actualInputStream: InputStream = if (isGzip) {
            GZIPInputStream(buffered)
        } else {
            buffered
        }

        val parser: XmlPullParser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(actualInputStream, StandardCharsets.UTF_8.name())
        }

        var eventType = parser.eventType
        var currentChannel: String? = null
        var currentXmltvChannelId: String? = null
        var currentStart: String? = null
        var currentStop: String? = null
        var currentTitle: String? = null
        var currentSubTitle: String? = null
        var currentDesc: String? = null
        var currentCategory: String? = null
        var currentIcon: String? = null
        var inChannelElement = false

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tagName = parser.name
                when (tagName) {
                    "channel" -> {
                        inChannelElement = true
                        currentXmltvChannelId = parser.getAttributeValue(null, "id")?.trim()
                    }
                    "display-name" -> {
                        val chId = currentXmltvChannelId
                        if (inChannelElement && chId != null) {
                            val text = safeNextText(parser)
                            if (!text.isNullOrEmpty()) {
                                channelNamesMap[chId] = text
                            }
                        }
                    }
                    "programme" -> {
                        inChannelElement = false
                        currentChannel = parser.getAttributeValue(null, "channel")?.trim()
                        currentStart = parser.getAttributeValue(null, "start")?.trim()
                        currentStop = parser.getAttributeValue(null, "stop")?.trim()
                        currentTitle = null
                        currentSubTitle = null
                        currentDesc = null
                        currentCategory = null
                        currentIcon = null
                    }
                    "title" -> {
                        if (!inChannelElement && currentChannel != null) {
                            currentTitle = safeNextText(parser)
                        }
                    }
                    "sub-title" -> {
                        if (!inChannelElement && currentChannel != null) {
                            currentSubTitle = safeNextText(parser)
                        }
                    }
                    "desc" -> {
                        if (!inChannelElement && currentChannel != null) {
                            currentDesc = safeNextText(parser)
                        }
                    }
                    "category" -> {
                        if (!inChannelElement && currentChannel != null && currentCategory == null) {
                            currentCategory = safeNextText(parser)
                        }
                    }
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")?.trim()
                        val chId = currentXmltvChannelId
                        if (!src.isNullOrEmpty()) {
                            if (inChannelElement && chId != null) {
                                channelIconsMap[chId] = src
                            } else if (currentChannel != null && currentIcon == null) {
                                currentIcon = src
                            }
                        }
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                val tagName = parser.name
                if (tagName == "channel") {
                    inChannelElement = false
                    currentXmltvChannelId = null
                } else if (tagName == "programme") {
                    val startStr = currentStart
                    val stopStr = currentStop
                    val chStr = currentChannel
                    val titleStr = currentTitle
                    if (chStr != null && startStr != null && stopStr != null && !titleStr.isNullOrEmpty()) {
                        val startMs = parseXmltvTimestampFast(startStr)
                        val endMs = parseXmltvTimestampFast(stopStr)
                        if (startMs != null && endMs != null && endMs > startMs) {
                            if (minEpochMs <= 0L || endMs >= minEpochMs) {
                                val programme = EpgProgramme(
                                    channelTvgId = chStr,
                                    title = titleStr,
                                    subTitle = currentSubTitle,
                                    description = currentDesc,
                                    category = currentCategory,
                                    startTimeEpochMs = startMs,
                                    endTimeEpochMs = endMs,
                                    posterArtUri = currentIcon,
                                )
                                programmesMap.getOrPut(chStr) { mutableListOf() }.add(programme)
                                totalProgrammesParsed++
                                if (totalProgrammesParsed % 25000 == 0) {
                                    try { Log.d("StreamingXmltvParser", "Parsed $totalProgrammesParsed programmes so far...") } catch (_: Throwable) {}
                                }
                            } else {
                                skippedCount++
                            }
                        } else {
                            skippedCount++
                        }
                    } else {
                        skippedCount++
                    }
                    currentChannel = null
                    currentStart = null
                    currentStop = null
                    currentTitle = null
                    currentSubTitle = null
                    currentDesc = null
                    currentCategory = null
                    currentIcon = null
                }
            }
            eventType = parser.next()
        }

        val result = EpgParseResult(
            programmesByChannel = programmesMap,
            channelNames = channelNamesMap,
            channelIcons = channelIconsMap,
            skippedCount = skippedCount
        )
        try { Log.i("StreamingXmltvParser", "Finished XMLTV parsing: ${programmesMap.size} channels with $totalProgrammesParsed total programs, ${channelNamesMap.size} channels metadata, skipped $skippedCount programmes.") } catch (_: Throwable) {}
        return result
    }

    private fun safeNextText(parser: XmlPullParser): String? {
        return try {
            val text = parser.nextText()
            if (text.isNullOrBlank()) null else text.trim()
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseXmltvTimestampFast(timestampStr: String): Long? {
        val s = timestampStr.trim()
        if (s.length < 14) return null
        for (i in 0 until 14) {
            if (s[i] !in '0'..'9') {
                return parseXmltvTimestampFallback(s)
            }
        }
        try {
            val year = (s[0] - '0') * 1000 + (s[1] - '0') * 100 + (s[2] - '0') * 10 + (s[3] - '0')
            val month = (s[4] - '0') * 10 + (s[5] - '0')
            val day = (s[6] - '0') * 10 + (s[7] - '0')
            val hour = (s[8] - '0') * 10 + (s[9] - '0')
            val min = (s[10] - '0') * 10 + (s[11] - '0')
            val sec = (s[12] - '0') * 10 + (s[13] - '0')

            if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || min !in 0..59 || sec !in 0..59) {
                return parseXmltvTimestampFallback(s)
            }

            var offsetSeconds = 0
            var idx = 14
            while (idx < s.length && s[idx] == ' ') idx++

            if (idx < s.length) {
                val signChar = s[idx]
                if (signChar == '+' || signChar == '-') {
                    val sign = if (signChar == '-') -1 else 1
                    idx++
                    if (idx + 3 < s.length) {
                        val h1 = s[idx] - '0'
                        val h2 = s[idx + 1] - '0'
                        idx += 2
                        if (idx < s.length && s[idx] == ':') idx++
                        val m1 = s[idx] - '0'
                        val m2 = s[idx + 1] - '0'
                        if (h1 in 0..9 && h2 in 0..9 && m1 in 0..9 && m2 in 0..9) {
                            val tzHour = h1 * 10 + h2
                            val tzMin = m1 * 10 + m2
                            offsetSeconds = sign * (tzHour * 3600 + tzMin * 60)
                        }
                    }
                }
            }

            val localEpochSec = LocalDateTime.of(year, month, day, hour, min, sec)
                .toEpochSecond(ZoneOffset.UTC)
            return (localEpochSec - offsetSeconds) * 1000L
        } catch (_: Throwable) {
            return parseXmltvTimestampFallback(s)
        }
    }

    private fun parseXmltvTimestampFallback(clean: String): Long? {
        return try {
            if (clean.length >= 19 && (clean[14] == '+' || clean[14] == '-')) {
                synchronized(xmltvDateFormat1) {
                    xmltvDateFormat1.parse(clean)?.time
                }
            } else if (clean.length >= 14) {
                val sub = clean.substring(0, 14)
                synchronized(xmltvDateFormat2) {
                    xmltvDateFormat2.parse(sub)?.time
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}



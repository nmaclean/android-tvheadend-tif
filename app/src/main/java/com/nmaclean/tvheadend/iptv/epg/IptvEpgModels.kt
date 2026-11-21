package com.nmaclean.tvheadend.iptv.epg

data class EpgProgramme(
    val channelTvgId: String,
    val title: String,
    val subTitle: String?,
    val description: String?,
    val category: String?,
    val startTimeEpochMs: Long,
    val endTimeEpochMs: Long,
    val posterArtUri: String?
)

data class EpgParseResult(
    val programmesByChannel: Map<String, List<EpgProgramme>>,
    val channelNames: Map<String, String> = emptyMap(),
    val channelIcons: Map<String, String> = emptyMap(),
    val skippedCount: Int
)

object IptvEpgConstants {
    const val DEFAULT_IPTV_EPG_URL = "http://localhost/xmltv.xml"
    val DEFAULT_EPG_PATHS = listOf("/xmltv.xml", "/epg.xml", "xmltv.xml", "epg.xml")
}

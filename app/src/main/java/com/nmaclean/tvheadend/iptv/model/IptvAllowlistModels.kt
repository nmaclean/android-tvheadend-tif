package com.nmaclean.tvheadend.iptv.model

import kotlinx.serialization.Serializable

@Serializable
data class AllowlistSnapshot(
    val allowlistVersion: Int = 1,
    val updatedAtEpochMs: Long,
    val channels: List<AllowlistChannel>
)

@Serializable
data class AllowlistChannel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class M3uCandidateChannel(
    val index: Int,
    val suggestedId: String,
    val name: String,
    val group: String,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap()
)

@Serializable
data class M3uParseResult(
    val candidates: List<M3uCandidateChannel>,
    val skippedCount: Int,
    val groups: List<String>,
    val suggestedEpgUrl: String? = null
)

@Serializable
data class IptvProviderData(
    val streamUrl: String,
    val headers: Map<String, String> = emptyMap()
)

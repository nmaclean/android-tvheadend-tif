package com.nmaclean.tvheadend.model

/**
 * Data model representing a Tvheadend live channel with TV Provider row ID,
 * stream UUID, display name, channel number, icon, and input ID metadata.
 */
data class TvhChannel(
    val id: Long,
    val uuid: String,
    val name: String,
    val number: Int,
    val icon: String = "",
    val inputId: String = ""
)

package com.nmaclean.tvheadend.htsp

import com.nmaclean.tvheadend.model.TvhChannel

/**
 * Thread-safe registry store for cached HTSP channels and tags.
 */
object ChannelRegistry {
    private val channelMap = mutableMapOf<Long, TvhChannel>()

    fun updateChannels(channels: List<TvhChannel>) {
        synchronized(channelMap) {
            channelMap.clear()
            val tvhChannelsOnly = channels.filter { isTvheadendChannel(it) }
            val uniqueChannels = tvhChannelsOnly.distinctBy { ch ->
                if (ch.id > 0L) "id_${ch.id}" else if (ch.uuid.isNotEmpty()) ch.uuid else "name_${ch.name}"
            }
            for (ch in uniqueChannels) {
                channelMap[ch.id] = ch
            }
        }
    }

    fun getAllChannels(): List<TvhChannel> {
        synchronized(channelMap) {
            return channelMap.values.filter { isTvheadendChannel(it) }.toList()
        }
    }

    fun getChannelById(id: Long): TvhChannel? {
        synchronized(channelMap) {
            val found = channelMap[id] ?: channelMap.values.firstOrNull { it.uuid == id.toString() }
            return if (found != null && isTvheadendChannel(found)) found else null
        }
    }

    fun getChannelByNumber(number: Int): TvhChannel? {
        synchronized(channelMap) {
            return channelMap.values.firstOrNull { it.number == number && isTvheadendChannel(it) }
        }
    }

    fun getChannelByName(name: String): TvhChannel? {
        synchronized(channelMap) {
            return channelMap.values.firstOrNull { it.name.equals(name, ignoreCase = true) && isTvheadendChannel(it) }
        }
    }

    private fun isTvheadendChannel(ch: TvhChannel): Boolean {
        if (ch.uuid.startsWith("{") || ch.uuid.contains("streamUrl") || ch.uuid.startsWith("iptv:")) return false
        if (ch.inputId.isNotEmpty() && !ch.inputId.contains("TvheadendInputService")) return false
        return true
    }
}

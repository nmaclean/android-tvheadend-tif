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
            val uniqueChannels = channels.distinctBy { ch ->
                if (ch.uuid.isNotEmpty()) ch.uuid else if (ch.number > 0) "num_${ch.number}" else "name_${ch.name}"
            }
            for (ch in uniqueChannels) {
                channelMap[ch.id] = ch
            }
        }
    }

    fun getAllChannels(): List<TvhChannel> {
        synchronized(channelMap) {
            return channelMap.values.distinctBy { ch ->
                if (ch.uuid.isNotEmpty()) ch.uuid else if (ch.number > 0) "num_${ch.number}" else "name_${ch.name}"
            }.toList()
        }
    }

    fun getChannelById(id: Long): TvhChannel? {
        synchronized(channelMap) {
            return channelMap[id]
                ?: channelMap.values.firstOrNull { it.uuid == id.toString() }
        }
    }

    fun getChannelByNumber(number: Int): TvhChannel? {
        synchronized(channelMap) {
            return channelMap.values.firstOrNull { it.number == number }
        }
    }

    fun getChannelByName(name: String): TvhChannel? {
        synchronized(channelMap) {
            return channelMap.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }
}

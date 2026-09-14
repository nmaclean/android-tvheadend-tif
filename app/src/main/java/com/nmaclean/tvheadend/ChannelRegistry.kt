package com.nmaclean.tvheadend

object ChannelRegistry {
    private val uuidMap = mutableMapOf<String, String>() // Maps rowId / number -> hex UUID

    fun register(key: String, uuid: String) {
        uuidMap[key] = uuid
    }

    fun getUuid(key: String): String? {
        return uuidMap[key]
    }

    fun clear() {
        uuidMap.clear()
    }
}

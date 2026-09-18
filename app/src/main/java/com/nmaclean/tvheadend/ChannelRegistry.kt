package com.nmaclean.tvheadend

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry mapping channel row IDs or numbers to Tvheadend hex UUID strings.
 */
object ChannelRegistry {
    private const val TAG = "ChannelRegistry"
    private val uuidMap = ConcurrentHashMap<String, String>()

    /**
     * Registers a mapping between a channel identifier key and its Tvheadend hex UUID.
     *
     * @param key Channel row ID or display number key.
     * @param uuid Tvheadend hex UUID string.
     */
    fun register(key: String, uuid: String) {
        Log.i(TAG, "Registering channel mapping: key='$key' -> uuid='$uuid'")
        uuidMap[key] = uuid
    }

    /**
     * Retrieves the registered Tvheadend hex UUID for the given channel key.
     *
     * @param key Channel row ID or display number key.
     * @return Registered Tvheadend UUID string, or `null` if not found.
     */
    fun getUuid(key: String): String? {
        val uuid = uuidMap[key]
        Log.i(TAG, "Looked up channel mapping: key='$key' -> uuid='$uuid'")
        return uuid
    }

    /**
     * Clears all registered channel mappings.
     */
    fun clear() {
        Log.i(TAG, "Clearing channel registry (cleared ${uuidMap.size} entries)")
        uuidMap.clear()
    }
}

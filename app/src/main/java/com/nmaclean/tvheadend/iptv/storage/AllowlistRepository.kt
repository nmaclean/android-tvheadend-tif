package com.nmaclean.tvheadend.iptv.storage

import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import com.nmaclean.tvheadend.iptv.model.AllowlistSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AllowlistRepository(private val filesDir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val allowlistFile = File(filesDir, "iptv_allowlist.json")
    private val tmpFile = File(filesDir, "iptv_allowlist.json.tmp")

    fun load(): AllowlistSnapshot {
        if (!allowlistFile.exists()) {
            return AllowlistSnapshot(
                allowlistVersion = 1,
                updatedAtEpochMs = System.currentTimeMillis(),
                channels = emptyList()
            )
        }
        return try {
            val content = allowlistFile.readText(Charsets.UTF_8)
            json.decodeFromString<AllowlistSnapshot>(content)
        } catch (e: Exception) {
            // Fallback to empty snapshot if corrupt or malformed
            AllowlistSnapshot(
                allowlistVersion = 1,
                updatedAtEpochMs = System.currentTimeMillis(),
                channels = emptyList()
            )
        }
    }

    fun save(channels: List<AllowlistChannel>, confirmEmpty: Boolean = false): AllowlistSnapshot {
        if (channels.isEmpty() && !confirmEmpty) {
            throw IllegalArgumentException("Cannot save empty allowlist without confirming empty collection.")
        }
        val snapshot = AllowlistSnapshot(
            allowlistVersion = 1,
            updatedAtEpochMs = System.currentTimeMillis(),
            channels = channels
        )
        val serialized = json.encodeToString(snapshot)

        tmpFile.writeText(serialized, Charsets.UTF_8)
        try {
            Files.move(
                tmpFile.toPath(),
                allowlistFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            // Fallback if atomic move is not supported on underlying filesystem
            if (tmpFile.exists()) {
                tmpFile.copyTo(allowlistFile, overwrite = true)
                tmpFile.delete()
            } else {
                throw e
            }
        }
        return snapshot
    }

    fun findById(id: String): AllowlistChannel? {
        return load().channels.find { it.id == id }
    }

    fun clear() {
        save(emptyList(), confirmEmpty = true)
        if (tmpFile.exists()) {
            tmpFile.delete()
        }
    }
}

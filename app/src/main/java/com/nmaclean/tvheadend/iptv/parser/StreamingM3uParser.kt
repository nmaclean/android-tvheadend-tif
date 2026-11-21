package com.nmaclean.tvheadend.iptv.parser

import com.nmaclean.tvheadend.iptv.model.M3uCandidateChannel
import com.nmaclean.tvheadend.iptv.model.M3uParseResult
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object StreamingM3uParser {

    fun parse(inputStream: InputStream): M3uParseResult {
        val candidates = mutableListOf<M3uCandidateChannel>()
        val groupsSet = mutableSetOf<String>()
        var skippedCount = 0

        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        var line: String?

        var currentTvgId: String? = null
        var currentTvgLogo: String? = null
        var currentGroupTitle: String? = null
        var currentName: String? = null
        val currentHeaders = mutableMapOf<String, String>()
        var hasExtInf = false
        var detectedEpgUrl: String? = null

        while (true) {
            line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTM3U")) {
                val urlAttribute = extractHeaderEpgUrl(trimmed)
                if (!urlAttribute.isNullOrEmpty()) {
                    detectedEpgUrl = urlAttribute
                }
                continue
            } else if (trimmed.startsWith("#EXTINF:")) {
                hasExtInf = true
                currentTvgId = extractAttribute(trimmed, "tvg-id")
                currentTvgLogo = extractAttribute(trimmed, "tvg-logo")
                currentGroupTitle = extractAttribute(trimmed, "group-title") ?: "Uncategorized"

                // Extract display title after the last comma
                val lastCommaIndex = trimmed.lastIndexOf(',')
                currentName = if (lastCommaIndex != -1 && lastCommaIndex < trimmed.length - 1) {
                    trimmed.substring(lastCommaIndex + 1).trim()
                } else {
                    "Unknown Channel"
                }
            } else if (trimmed.startsWith("#EXTVLCOPT:")) {
                val opt = trimmed.substring("#EXTVLCOPT:".length).trim()
                val parts = opt.split('=', limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    if (key == "http-user-agent") {
                        currentHeaders["User-Agent"] = value
                    } else if (key == "http-referrer" || key == "http-referer") {
                        currentHeaders["Referer"] = value
                    }
                }
            } else if (trimmed.startsWith("#EXTHTTP:")) {
                // Optional JSON or header mapping
                val jsonStr = trimmed.substring("#EXTHTTP:".length).trim()
                try {
                    // Simple key-value extraction if json format is {"User-Agent": "..."}
                    val clean = jsonStr.removeSurrounding("{", "}").trim()
                    clean.split(",").forEach { pair ->
                        val kv = pair.split(":", limit = 2)
                        if (kv.size == 2) {
                            val k = kv[0].trim().removeSurrounding("\"").trim()
                            val v = kv[1].trim().removeSurrounding("\"").trim()
                            currentHeaders[k] = v
                        }
                    }
                } catch (_: Exception) {
                    // Ignore malformed exthttp
                }
            } else if (trimmed.startsWith("#")) {
                // Other comment/meta lines, ignore
                continue
            } else {
                // Potential stream URI line
                if (hasExtInf && (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("rtsp://") || trimmed.startsWith("udp://") || trimmed.startsWith("sop://"))) {
                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        val extracted = extractEpgUrlFromStreamUrl(trimmed)
                        val currentDet = detectedEpgUrl
                        if (extracted != null && (currentDet == null || (!currentDet.contains("username=") && extracted.contains("username=")))) {
                            detectedEpgUrl = extracted
                        }
                    }

                    val name = currentName ?: "Unknown Channel"
                    val group = currentGroupTitle ?: "Uncategorized"
                    groupsSet.add(group)

                    val baseSlug = slugify(name)
                    val suggestedId = "iptv-$baseSlug"

                    candidates.add(
                        M3uCandidateChannel(
                            index = candidates.size + 1,
                            suggestedId = suggestedId,
                            name = name,
                            group = group,
                            logoUrl = currentTvgLogo,
                            tvgId = currentTvgId,
                            streamUrl = trimmed,
                            headers = currentHeaders.toMap()
                        )
                    )
                } else {
                    skippedCount++
                }
                // Reset state for next entry
                currentTvgId = null
                currentTvgLogo = null
                currentGroupTitle = null
                currentName = null
                currentHeaders.clear()
                hasExtInf = false
            }
        }

        // Handle trailing lines if any unconsumed EXTINF without URI
        if (hasExtInf) {
            skippedCount++
        }

        // Ensure unique suggested IDs
        val seenIds = mutableMapOf<String, Int>()
        val uniqueCandidates = candidates.map { candidate ->
            val count = seenIds.getOrDefault(candidate.suggestedId, 0)
            seenIds[candidate.suggestedId] = count + 1
            if (count > 0) {
                candidate.copy(suggestedId = "${candidate.suggestedId}-$count")
            } else {
                candidate
            }
        }

        return M3uParseResult(
            candidates = uniqueCandidates,
            skippedCount = skippedCount,
            groups = groupsSet.sorted(),
            suggestedEpgUrl = detectedEpgUrl
        )
    }

    fun extractEpgUrlFromStreamUrl(urlStr: String): String? {
        try {
            val uri = java.net.URI(urlStr)
            val scheme = uri.scheme ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host ?: return null
            val portStr = if (uri.port != -1) ":${uri.port}" else ""
            val baseUrl = "$scheme://$host$portStr"

            var username: String? = null
            var password: String? = null

            // 1. Check query parameters
            val query = uri.query
            if (!query.isNullOrEmpty()) {
                val params = query.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    val k = parts[0].lowercase().trim()
                    val v = if (parts.size > 1) parts[1] else ""
                    k to v
                }
                username = params["username"] ?: params["user"]
                password = params["password"] ?: params["pass"]
            }

            // 2. Check path segments if Xtream Codes style (e.g. /live/username/password/stream.ts)
            if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
                val path = uri.path ?: ""
                val segments = path.split("/").filter { it.isNotEmpty() }
                if (segments.size >= 3) {
                    val action = segments[0].lowercase()
                    if (action == "live" || action == "movie" || action == "series" || action == "play") {
                        val u = segments[1]
                        val p = segments[2]
                        if (u.isNotEmpty() && p.isNotEmpty() && !p.contains(".")) {
                            username = u
                            password = p
                        }
                    }
                }
            }

            return if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                "$baseUrl/xmltv.php?username=$username&password=$password"
            } else {
                "$baseUrl/xmltv.php"
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun extractHeaderEpgUrl(headerLine: String): String? {
        val attributeNames = listOf("url-tvg", "tvg-url", "x-tvg-url", "epg-url", "url")
        for (attr in attributeNames) {
            val pattern = Regex("""$attr=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            val match = pattern.find(headerLine)
            val value = match?.groupValues?.getOrNull(1)?.trim()
            if (!value.isNullOrEmpty()) {
                val firstUrl = value.split(",").firstOrNull { it.trim().startsWith("http") }?.trim()
                if (!firstUrl.isNullOrEmpty()) {
                    return firstUrl
                }
            }
        }
        return null
    }

    private fun extractAttribute(line: String, attrName: String): String? {
        val pattern = Regex("""$attrName=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        val match = pattern.find(line)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
    }

    private fun slugify(input: String): String {
        val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
        val slug = Regex("[^\\w\\s-]").replace(normalized, "").lowercase()
        return Regex("[\\s-]+").replace(slug, "-").trim('-').ifEmpty { "channel" }
    }
}

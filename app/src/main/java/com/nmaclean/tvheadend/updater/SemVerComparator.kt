package com.nmaclean.tvheadend.updater

/**
 * Parsed semantic version representation supporting optional `v` prefix and pre-release suffixes.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)

        // Per SemVer 2.0.0: a normal version has higher precedence than a pre-release version.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    private fun comparePreRelease(a: String, b: String): Int {
        val partsA = a.split('.')
        val partsB = b.split('.')
        val count = maxOf(partsA.size, partsB.size)
        for (i in 0 until count) {
            if (i >= partsA.size) return -1
            if (i >= partsB.size) return 1
            val segA = partsA[i]
            val segB = partsB[i]
            val numA = segA.toIntOrNull()
            val numB = segB.toIntOrNull()
            val cmp = when {
                numA != null && numB != null -> numA.compareTo(numB)
                numA != null -> -1 // Numeric identifiers always have lower precedence than non-numeric
                numB != null -> 1
                else -> segA.compareTo(segB)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}

/**
 * Utility for parsing and comparing semantic version strings (e.g. `"v1.2.3"` vs `"1.2.0"`).
 */
object SemVerComparator {

    private val VERSION_REGEX = Regex("""^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    /**
     * Parses [rawVersion] into a [SemVer], or returns `null` if invalid.
     */
    fun parse(rawVersion: String): SemVer? {
        val trimmed = rawVersion.trim()
        if (trimmed.isEmpty()) return null
        val match = VERSION_REGEX.matchEntire(trimmed) ?: return null

        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null
        val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
        val preRelease = match.groupValues[4].takeIf { it.isNotEmpty() }

        return SemVer(major, minor, patch, preRelease)
    }

    /**
     * Compares [left] and [right] semantically. Returns `null` if either string cannot be parsed.
     */
    fun compare(left: String, right: String): Int? {
        val parsedLeft = parse(left) ?: return null
        val parsedRight = parse(right) ?: return null
        return parsedLeft.compareTo(parsedRight)
    }

    /**
     * Returns `true` if [candidateVersion] is strictly newer than [currentVersion].
     * Pre-release candidates are ignored unless [includePreReleases] is `true`.
     */
    fun isNewer(
        candidateVersion: String,
        currentVersion: String,
        includePreReleases: Boolean = false
    ): Boolean {
        val candidate = parse(candidateVersion) ?: return false
        val current = parse(currentVersion) ?: return false
        if (!includePreReleases && candidate.preRelease != null) return false
        return candidate > current
    }
}

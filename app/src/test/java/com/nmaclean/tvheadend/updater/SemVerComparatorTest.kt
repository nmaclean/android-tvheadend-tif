package com.nmaclean.tvheadend.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerComparatorTest {

    @Test
    fun testParseValidVersions() {
        assertEquals(SemVer(1, 2, 3), SemVerComparator.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVerComparator.parse("V1.2.3"))
        assertEquals(SemVer(1, 2, 0), SemVerComparator.parse("1.2"))
        assertEquals(SemVer(2, 0, 0, "rc.1"), SemVerComparator.parse("v2.0.0-rc.1+build42"))
    }

    @Test
    fun testParseInvalidVersionsReturnsNull() {
        assertNull(SemVerComparator.parse(""))
        assertNull(SemVerComparator.parse("   "))
        assertNull(SemVerComparator.parse("release-candidate"))
        assertNull(SemVerComparator.parse("v1.2.x"))
    }

    @Test
    fun testIsNewerStandardVersions() {
        assertTrue(SemVerComparator.isNewer("v1.2.3", "1.2.0"))
        assertTrue(SemVerComparator.isNewer("v2.0.0", "v1.9.9"))
        assertFalse(SemVerComparator.isNewer("v1.0.2", "1.0.2"))
        assertFalse(SemVerComparator.isNewer("v1.0.1", "1.0.2"))
    }

    @Test
    fun testPreReleaseHandling() {
        // Pre-releases are ignored by default when checking for stable updates
        assertFalse(SemVerComparator.isNewer("v1.3.0-rc.1", "1.2.0"))
        // Included when explicitly opted in
        assertTrue(SemVerComparator.isNewer("v1.3.0-rc.1", "1.2.0", includePreReleases = true))

        // Per SemVer 2.0.0: 1.3.0-rc.1 < 1.3.0
        val cmp = SemVerComparator.compare("1.3.0-rc.1", "1.3.0")
        assertNotNull(cmp)
        assertTrue(cmp!! < 0)
    }
}

package com.nmaclean.tvheadend.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateManagerTest {

    private val manager = GitHubUpdateManager()

    @Test
    fun testParseLatestReleaseWithApkAsset() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "prerelease": false,
              "draft": false,
              "body": "### What's New\n- Faster channel switching",
              "assets": [
                {
                  "name": "checksums.sha256",
                  "size": 128,
                  "browser_download_url": "https://github.com/nmaclean/android-tvheadend-tif/releases/download/v1.2.0/checksums.sha256"
                },
                {
                  "name": "app-release.apk",
                  "size": 15728640,
                  "browser_download_url": "https://github.com/nmaclean/android-tvheadend-tif/releases/download/v1.2.0/app-release.apk"
                }
              ]
            }
        """.trimIndent()

        val release = manager.parseLatestReleaseJson(json)
        assertNotNull(release)
        assertEquals("v1.2.0", release?.tagName)
        assertEquals("1.2.0", release?.versionName)
        assertEquals("### What's New\n- Faster channel switching", release?.releaseNotes)
        assertEquals("app-release.apk", release?.assetName)
        assertEquals(15728640L, release?.assetSizeBytes)
        assertEquals(
            "https://github.com/nmaclean/android-tvheadend-tif/releases/download/v1.2.0/app-release.apk",
            release?.apkDownloadUrl
        )
        assertFalse(release?.isPreRelease == true)
    }

    @Test
    fun testParseReleaseMissingApkAssetReturnsNull() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "prerelease": false,
              "draft": false,
              "body": "Source only",
              "assets": [
                {
                  "name": "source.zip",
                  "size": 1024,
                  "browser_download_url": "https://example.com/source.zip"
                }
              ]
            }
        """.trimIndent()

        assertNull(manager.parseLatestReleaseJson(json))
    }

    @Test
    fun testParseDraftReleaseReturnsNull() {
        val json = """
            {
              "tag_name": "v1.2.0",
              "prerelease": false,
              "draft": true,
              "assets": [
                {
                  "name": "app-release.apk",
                  "size": 100,
                  "browser_download_url": "https://example.com/app-release.apk"
                }
              ]
            }
        """.trimIndent()

        assertNull(manager.parseLatestReleaseJson(json))
    }

    @Test
    fun testParsePreReleaseFlagPreserved() {
        val json = """
            {
              "tag_name": "v1.3.0-rc.1",
              "prerelease": true,
              "draft": false,
              "body": null,
              "assets": [
                {
                  "name": "app-release.apk",
                  "size": 100,
                  "browser_download_url": "https://example.com/app-release.apk"
                }
              ]
            }
        """.trimIndent()

        val release = manager.parseLatestReleaseJson(json)
        assertNotNull(release)
        assertTrue(release?.isPreRelease == true)
        assertEquals("", release?.releaseNotes)
    }

    @Test
    fun testParseMalformedJsonReturnsNull() {
        assertNull(manager.parseLatestReleaseJson("not-json"))
        assertNull(manager.parseLatestReleaseJson("{}"))
    }

    @Test
    fun testParseReleasesListFiltersDraftsPreReleasesAndMissingApks() {
        val json = """
            [
              {
                "tag_name": "v1.2.8",
                "prerelease": false,
                "draft": false,
                "body": "Latest",
                "assets": [{"name": "app-1.2.8.apk", "size": 100, "browser_download_url": "https://example.com/128.apk"}]
              },
              {
                "tag_name": "v1.2.8-rc1",
                "prerelease": true,
                "draft": false,
                "assets": [{"name": "app-rc1.apk", "size": 100, "browser_download_url": "https://example.com/rc1.apk"}]
              },
              {
                "tag_name": "v1.2.7",
                "prerelease": false,
                "draft": true,
                "assets": [{"name": "app-1.2.7.apk", "size": 100, "browser_download_url": "https://example.com/127.apk"}]
              },
              {
                "tag_name": "v1.2.6",
                "prerelease": false,
                "draft": false,
                "body": "Previous",
                "assets": [{"name": "app-1.2.6.apk", "size": 90, "browser_download_url": "https://example.com/126.apk"}]
              }
            ]
        """.trimIndent()

        val list = manager.parseReleasesListJson(json)
        assertEquals(2, list.size)
        assertEquals("v1.2.8", list[0].tagName)
        assertEquals("v1.2.6", list[1].tagName)
    }

    @Test
    fun testParseReleasesAtomFallback() {
        val atomXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/nmaclean/android-tvheadend-tif/releases/tag/v1.2.15"/>
                <title>v1.2.15</title>
              </entry>
              <entry>
                <link rel="alternate" type="text/html" href="https://github.com/nmaclean/android-tvheadend-tif/releases/tag/v1.2.14"/>
                <title>v1.2.14</title>
              </entry>
            </feed>
        """.trimIndent()

        val list = manager.parseReleasesAtom(atomXml, limit = 15)
        assertEquals(2, list.size)
        assertEquals("v1.2.15", list[0].tagName)
        assertEquals("1.2.15", list[0].versionName)
        assertEquals(
            "https://github.com/nmaclean/android-tvheadend-tif/releases/download/v1.2.15/app-release.apk",
            list[0].apkDownloadUrl
        )
        assertEquals("v1.2.14", list[1].tagName)
    }
}

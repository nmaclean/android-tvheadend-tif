package com.nmaclean.tvheadend.iptv.epg

import android.content.ContentResolver
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IptvEpgSyncerTest {

    private lateinit var contentResolver: ContentResolver
    private lateinit var tempDir: File
    private lateinit var repository: AllowlistRepository
    private lateinit var syncer: IptvEpgSyncer

    @Before
    fun setUp() {
        contentResolver = mock(ContentResolver::class.java)
        tempDir = Files.createTempDirectory("epg_test").toFile()
        repository = AllowlistRepository(tempDir)
        syncer = IptvEpgSyncer(contentResolver, "com.nmaclean.tvheadend/.TvheadendInputService", repository)
    }

    @Test
    fun testSyncEmptyAllowlistReturnsZero() {
        val parseResult = EpgParseResult(
            programmesByChannel = mapOf("bbconehd.uk" to listOf(
                EpgProgramme("bbconehd.uk", "News", null, null, null, 1000L, 2000L, null)
            )),
            skippedCount = 0
        )
        val count = syncer.syncPrograms(parseResult)
        assertEquals(0, count)
    }
}

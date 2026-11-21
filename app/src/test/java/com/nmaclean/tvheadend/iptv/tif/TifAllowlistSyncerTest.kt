package com.nmaclean.tvheadend.iptv.tif

import android.content.ContentResolver
import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TifAllowlistSyncerTest {

    private lateinit var tempDir: File
    private lateinit var repository: AllowlistRepository
    private lateinit var syncer: TifAllowlistSyncer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("tif_test").toFile()
        repository = AllowlistRepository(tempDir)
        val contentResolver = object : ContentResolver(null) {}
        syncer = TifAllowlistSyncer(contentResolver, "com.nmaclean.tvheadend/.TvheadendInputService", repository)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSyncEmptyAllowlist() {
        val count = syncer.sync()
        assertEquals(0, count)
    }

    @Test
    fun testSyncWithChannels() {
        repository.save(
            listOf(
                AllowlistChannel(
                    id = "test-1",
                    name = "Channel 1",
                    group = "News",
                    streamUrl = "http://example.com/1.m3u8"
                )
            )
        )

        val count = syncer.sync()
        assertEquals(1, count)
    }
}

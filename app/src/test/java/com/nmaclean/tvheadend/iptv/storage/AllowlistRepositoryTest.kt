package com.nmaclean.tvheadend.iptv.storage

import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AllowlistRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repository: AllowlistRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("allowlist_test").toFile()
        repository = AllowlistRepository(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testLoadEmptyWhenFileMissing() {
        val snapshot = repository.load()
        assertNotNull(snapshot)
        assertTrue(snapshot.channels.isEmpty())
        assertEquals(1, snapshot.allowlistVersion)
    }

    @Test
    fun testSaveAndLoadChannels() {
        val channel = AllowlistChannel(
            id = "iptv-1",
            name = "Channel 1",
            group = "Sports",
            streamUrl = "http://example.com/1.m3u8"
        )

        repository.save(listOf(channel))

        val loaded = repository.load()
        assertEquals(1, loaded.channels.size)
        assertEquals(channel, loaded.channels[0])

        val found = repository.findById("iptv-1")
        assertNotNull(found)
        assertEquals("Channel 1", found?.name)

        assertNull(repository.findById("non-existent"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSaveEmptyWithoutConfirmationThrows() {
        repository.save(emptyList(), confirmEmpty = false)
    }

    @Test
    fun testSaveEmptyWithConfirmationSucceeds() {
        val channel = AllowlistChannel(id = "iptv-1", name = "C1", group = "G", streamUrl = "http://ex.com")
        repository.save(listOf(channel))

        val snapshot = repository.save(emptyList(), confirmEmpty = true)
        assertTrue(snapshot.channels.isEmpty())
    }
}

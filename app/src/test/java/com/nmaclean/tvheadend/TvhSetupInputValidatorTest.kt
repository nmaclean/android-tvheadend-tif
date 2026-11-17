package com.nmaclean.tvheadend

import com.nmaclean.tvheadend.model.TvhConstants
import com.nmaclean.tvheadend.sync.TvhSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvhSetupInputValidatorTest {

    @Test
    fun testNormalizeHostTrimsAndIgnoresPlaceholder() {
        assertEquals("192.168.1.50", TvhSetupInputValidator.normalizeHost("  192.168.1.50 \n"))
        assertEquals("", TvhSetupInputValidator.normalizeHost("e.g., 192.168.1.50"))
        assertEquals("", TvhSetupInputValidator.normalizeHost(null))
    }

    @Test
    fun testNormalizePortHandlesDefaultsAndInvalidInput() {
        assertEquals(9982, TvhSetupInputValidator.normalizePort("9982", TvhConstants.DEFAULT_HTSP_PORT))
        assertEquals(9981, TvhSetupInputValidator.normalizePort(" 9981 ", TvhConstants.DEFAULT_HTTP_PORT))
        assertEquals(9982, TvhSetupInputValidator.normalizePort("", TvhConstants.DEFAULT_HTSP_PORT))
        assertEquals(9982, TvhSetupInputValidator.normalizePort("70000", TvhConstants.DEFAULT_HTSP_PORT))
        assertEquals(9981, TvhSetupInputValidator.normalizePort("abc", TvhConstants.DEFAULT_HTTP_PORT))
    }

    @Test
    fun testResolvePasswordPreservesExistingWhenMasked() {
        assertEquals("secret123", TvhSetupInputValidator.resolvePassword(TvhSetupInputValidator.PASSWORD_MASK, "secret123", "Optional password"))
        assertEquals("secret123", TvhSetupInputValidator.resolvePassword(TvhSetupInputValidator.LEGACY_PASSWORD_MASK, "secret123", "Optional password"))
        assertEquals("secret123", TvhSetupInputValidator.resolvePassword("Optional password", "secret123", "Optional password"))
        assertEquals("newpass", TvhSetupInputValidator.resolvePassword("  newpass  ", "secret123", "Optional password"))
        assertEquals("", TvhSetupInputValidator.resolvePassword("", "secret123", "Optional password"))
    }

    @Test
    fun testFormatDescriptions() {
        assertEquals("e.g., 192.168.1.50", TvhSetupInputValidator.formatHostDescription(""))
        assertEquals("tv.lan", TvhSetupInputValidator.formatHostDescription("tv.lan"))
        assertEquals("Optional username", TvhSetupInputValidator.formatUsernameDescription(""))
        assertEquals("alice", TvhSetupInputValidator.formatUsernameDescription("alice"))
        assertEquals("Optional password", TvhSetupInputValidator.formatPasswordDescription(""))
        assertEquals(TvhSetupInputValidator.PASSWORD_MASK, TvhSetupInputValidator.formatPasswordDescription("pw"))
    }

    @Test
    fun testProgressSummaryAndPreviewStateEmission() {
        val updates = mutableListOf<TvhSyncManager.SyncProgressUpdate>()
        val emit: (TvhSyncManager.SyncProgressUpdate) -> Unit = { updates.add(it) }

        emit(
            TvhSyncManager.SyncProgressUpdate(
                importedChannels = 42,
                totalChannels = 118,
                logosLoaded = 38,
                currentChannelNumber = 101,
                currentChannelName = "BBC One",
                latestPreviewItem = TvhSyncManager.ChannelLogoPreviewItem(
                    channelId = 1L,
                    number = 101,
                    name = "BBC One",
                    logoBitmap = null,
                    hasLogoUrl = false,
                    logoFailed = false
                )
            )
        )

        assertEquals(1, updates.size)
        val latest = updates.first()
        assertEquals(
            "Imported 42 / 118 channels • 38 logos loaded",
            TvhSetupInputValidator.formatProgressSummary(
                latest.importedChannels,
                latest.totalChannels,
                latest.logosLoaded
            )
        )
        assertEquals("BBC One", latest.latestPreviewItem?.name)
        assertFalse(latest.latestPreviewItem?.hasLogoUrl ?: true)
        assertTrue(latest.currentChannelNumber == 101)
    }
}

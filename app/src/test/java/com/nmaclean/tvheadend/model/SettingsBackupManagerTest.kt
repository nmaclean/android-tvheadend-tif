package com.nmaclean.tvheadend.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsBackupManagerTest {

    @Test
    fun `serialize and deserialize round-trips all TIF and general settings`() {
        val original = SettingsBackupPayload(
            exportedFromVersion = "1.2.37",
            exportedAtMs = 1700000000000L,
            host = "192.168.1.50",
            htspPort = 9982,
            httpPort = 9981,
            username = "tvuser",
            password = "secret-password",
            subtitleBottomPaddingFraction = 0.12f,
            isStreamingDebugEnabled = true
        )

        val json = SettingsBackupManager.serialize(original)
        val restored = SettingsBackupManager.deserialize(json)

        assertNotNull(restored)
        assertEquals(original, restored)
    }

    @Test
    fun `deserialize returns null for blank or hostless JSON`() {
        assertNull(SettingsBackupManager.deserialize(""))
        assertNull(SettingsBackupManager.deserialize("{not valid json"))
        assertNull(SettingsBackupManager.deserialize("""{"host":"","htspPort":9982}"""))
    }
}

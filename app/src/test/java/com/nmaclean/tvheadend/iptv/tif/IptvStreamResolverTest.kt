package com.nmaclean.tvheadend.iptv.tif

import android.media.tv.TvContract
import com.nmaclean.tvheadend.iptv.model.IptvProviderData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IptvStreamResolverTest {

    @Test
    fun testResolveValidIptvStream() {
        val providerData = IptvProviderData(
            streamUrl = "http://example.com/live/stream.m3u8",
            headers = mapOf("User-Agent" to "TestAgent", "Authorization" to "Bearer token123")
        )
        val jsonStr = Json.encodeToString(providerData)
        val dataBytes = jsonStr.toByteArray(Charsets.UTF_8)

        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
        ))
        cursor.setResults(arrayOf(arrayOf("iptv:ch1", dataBytes)))

        val context = RuntimeEnvironment.getApplication()
        val contentResolver = context.contentResolver
        val uri = TvContract.buildChannelUri(101L)
        shadowOf(contentResolver).setCursor(uri, cursor)

        val resolved = IptvStreamResolver.resolve(contentResolver, uri)

        assertNotNull(resolved)
        assertEquals("http://example.com/live/stream.m3u8", resolved?.streamUrl)
        assertEquals("TestAgent", resolved?.headers?.get("User-Agent"))
        assertEquals("Bearer token123", resolved?.headers?.get("Authorization"))
    }

    @Test
    fun testResolveNonIptvChannelReturnsNull() {
        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
        ))
        cursor.setResults(arrayOf(arrayOf("tvh:123", "some_data".toByteArray(Charsets.UTF_8))))

        val context = RuntimeEnvironment.getApplication()
        val contentResolver = context.contentResolver
        val uri = TvContract.buildChannelUri(202L)
        shadowOf(contentResolver).setCursor(uri, cursor)

        val resolved = IptvStreamResolver.resolve(contentResolver, uri)

        assertNull(resolved)
    }

    @Test
    fun testResolveEmptyCursorReturnsNull() {
        val cursor = RoboCursor()
        cursor.setColumnNames(listOf(
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
        ))
        cursor.setResults(emptyArray())

        val context = RuntimeEnvironment.getApplication()
        val contentResolver = context.contentResolver
        val uri = TvContract.buildChannelUri(303L)
        shadowOf(contentResolver).setCursor(uri, cursor)

        val resolved = IptvStreamResolver.resolve(contentResolver, uri)

        assertNull(resolved)
    }
}

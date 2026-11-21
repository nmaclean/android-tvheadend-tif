package com.nmaclean.tvheadend.sync

import android.content.Context
import android.media.tv.TvContract
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TifChannelObserverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun testRapidOnChangeEventsCoalesceToOneCallback() {
        var callbackCount = 0
        val observer = TifChannelObserver(
            contentResolver = context.contentResolver,
            debounceMs = 250L
        ) {
            callbackCount++
        }

        repeat(10) {
            observer.onChange(false, TvContract.Channels.CONTENT_URI)
            ShadowLooper.idleMainLooper(20L, TimeUnit.MILLISECONDS)
        }

        assertEquals("No callback should fire before 250ms elapses", 0, callbackCount)

        ShadowLooper.idleMainLooper(300L, TimeUnit.MILLISECONDS)
        assertEquals("10 rapid onChange events should coalesce into exactly 1 callback after 250ms", 1, callbackCount)
    }

    @Test
    fun testUnregisterCancelsPendingDebouncedCallback() {
        var callbackCount = 0
        val observer = TifChannelObserver(
            contentResolver = context.contentResolver,
            debounceMs = 250L
        ) {
            callbackCount++
        }

        observer.onChange(false, TvContract.Channels.CONTENT_URI)
        ShadowLooper.idleMainLooper(50L, TimeUnit.MILLISECONDS)
        assertEquals(0, callbackCount)

        observer.unregister()

        ShadowLooper.idleMainLooper(300L, TimeUnit.MILLISECONDS)
        assertEquals("Unregistering should cancel pending debounced runnable, resulting in 0 callbacks", 0, callbackCount)
    }
}

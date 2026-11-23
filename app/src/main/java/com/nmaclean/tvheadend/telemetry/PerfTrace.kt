package com.nmaclean.tvheadend.telemetry

import android.os.Trace
import android.util.Log

/**
 * Lightweight performance tracing helper emitting Android Trace markers (Perfetto)
 * and logcat [PERF_TRACE] timing entries.
 */
object PerfTrace {

    inline fun <T> section(tag: String, sectionName: String, thresholdMs: Long = 5L, block: () -> T): T {
        val startMs = System.currentTimeMillis()
        Trace.beginSection("$tag:$sectionName")
        return try {
            block()
        } finally {
            Trace.endSection()
            val durationMs = System.currentTimeMillis() - startMs
            if (durationMs >= thresholdMs) {
                Log.d(tag, "[PERF_TRACE] $sectionName completed in ${durationMs}ms")
            }
        }
    }
}

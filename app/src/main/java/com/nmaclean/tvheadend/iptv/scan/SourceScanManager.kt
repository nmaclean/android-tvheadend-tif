package com.nmaclean.tvheadend.iptv.scan

import android.content.Context
import android.media.tv.TvContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class ScanSource {
    TVHEADEND,
    IPTV,
    ALL
}

enum class ClearTarget {
    IPTV,
    TVHEADEND,
    BOTH
}

data class ClearResult(
    val clearedChannels: Int = 0,
    val clearedPrograms: Int = 0,
    val clearedLogos: Int = 0
)

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val source: ScanSource, val step: String, val progressPercent: Int) : ScanState
    data class Completed(val summary: String) : ScanState
    data class Error(val message: String) : ScanState
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceScanManager(
    private val context: Context,
    private val handlers: List<InputSourceHandler> = listOf(TvheadendSourceHandler(), IptvSourceHandler())
) {
    companion object {
        const val TAG = "SourceScanManager"
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    suspend fun startScan(source: ScanSource) {
        withContext(Dispatchers.IO) {
            _scanState.update { ScanState.Scanning(source, "Initializing scan...", 10) }
            try {
                val results = mutableListOf<String>()
                val targetHandlers = when (source) {
                    ScanSource.TVHEADEND -> handlers.filter { it.source == ScanSource.TVHEADEND }
                    ScanSource.IPTV -> handlers.filter { it.source == ScanSource.IPTV }
                    ScanSource.ALL -> handlers
                }

                for (handler in targetHandlers) {
                    val res = handler.scan(context) { step, progress ->
                        _scanState.update { ScanState.Scanning(source, step, progress) }
                    }
                    results.add(res)
                }

                val summary = results.filter { it.isNotEmpty() }.joinToString(" | ")
                _scanState.update { ScanState.Completed(summary.ifEmpty { "Scan completed successfully." }) }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                _scanState.update { ScanState.Error(e.message ?: "Unknown scan error") }
            }
        }
    }

    suspend fun clearTvInputData(target: ClearTarget): ClearResult = withContext(Dispatchers.IO) {
        var totalChannels = 0
        var totalPrograms = 0
        var totalLogos = 0

        val targetHandlers = when (target) {
            ClearTarget.TVHEADEND -> handlers.filter { it.source == ScanSource.TVHEADEND }
            ClearTarget.IPTV -> handlers.filter { it.source == ScanSource.IPTV }
            ClearTarget.BOTH -> handlers
        }

        for (handler in targetHandlers) {
            val res = handler.clear(context)
            totalChannels += res.clearedChannels
            totalPrograms += res.clearedPrograms
            totalLogos += res.clearedLogos
        }

        if (target == ClearTarget.BOTH) {
            try {
                val progsDel = context.contentResolver.delete(TvContract.Programs.CONTENT_URI, null, null)
                totalPrograms += progsDel.coerceAtLeast(0)
            } catch (_: Exception) {}
            try {
                val channelsDel = context.contentResolver.delete(TvContract.Channels.CONTENT_URI, null, null)
                totalChannels += channelsDel.coerceAtLeast(0)
            } catch (_: Exception) {}
        }

        com.nmaclean.tvheadend.htsp.ChannelRegistry.updateChannels(emptyList())

        try {
            context.contentResolver.notifyChange(TvContract.Channels.CONTENT_URI, null)
            context.contentResolver.notifyChange(TvContract.Programs.CONTENT_URI, null)
        } catch (_: Throwable) {}

        ClearResult(totalChannels, totalPrograms, totalLogos)
    }

    suspend fun clearAllTifInputs(): ClearResult = clearTvInputData(ClearTarget.BOTH)

    suspend fun resetAllDataAndSettings(): ClearResult = withContext(Dispatchers.IO) {
        val clearResult = clearAllTifInputs()
        try {
            val settings = com.nmaclean.tvheadend.model.TvhSettings(context)
            settings.host = ""
            settings.username = ""
            settings.password = ""
            settings.htspPort = com.nmaclean.tvheadend.model.TvhConstants.DEFAULT_HTSP_PORT
            settings.httpPort = com.nmaclean.tvheadend.model.TvhConstants.DEFAULT_HTTP_PORT
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting TvhSettings: ${e.message}")
        }
        try {
            val allowlistRepo = com.nmaclean.tvheadend.iptv.storage.AllowlistRepository(context.filesDir)
            allowlistRepo.save(emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing AllowlistRepository: ${e.message}")
        }
        com.nmaclean.tvheadend.htsp.ChannelRegistry.updateChannels(emptyList())
        clearResult
    }
}

package com.nmaclean.tvheadend.ui.settings

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import com.nmaclean.tvheadend.iptv.parser.StreamingM3uParser
import com.nmaclean.tvheadend.iptv.scan.IptvSourceHandler
import com.nmaclean.tvheadend.iptv.scan.ScanSource
import com.nmaclean.tvheadend.iptv.scan.ScanState
import com.nmaclean.tvheadend.iptv.scan.SourceScanManager
import com.nmaclean.tvheadend.iptv.server.LanCurationServer
import com.nmaclean.tvheadend.iptv.server.LanNetworkUtils
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import com.nmaclean.tvheadend.ui.TvhUiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class M3uCurationUiState(
    val isServerRunning: Boolean = false,
    val lanIp: String? = null,
    val port: Int = 8765,
    val allowlistCount: Int = 0,
    val syncStatusMessage: String? = null,
    val isSyncing: Boolean = false,
    val suggestedEpgUrl: String? = null
)

class M3uCurationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AllowlistRepository(application.filesDir)
    private val epgSettings = IptvEpgSettingsStore(application)
    private var curationServer: LanCurationServer? = null

    private val _uiState = MutableStateFlow(
        M3uCurationUiState(
            lanIp = LanNetworkUtils.findLanIpv4Address(),
            allowlistCount = repository.load().channels.size,
            suggestedEpgUrl = epgSettings.epgUrl.takeIf { it.isNotBlank() }
        )
    )
    val uiState: StateFlow<M3uCurationUiState> = _uiState.asStateFlow()

    fun toggleServer(start: Boolean) {
        val app = getApplication<Application>()
        if (start) {
            if (curationServer == null) {
                curationServer = LanCurationServer(app, port = 8765) { updatedChannels ->
                    _uiState.update { it.copy(allowlistCount = updatedChannels.size) }
                }
            }
            try {
                curationServer?.start()
                val actualPort = curationServer?.actualPort ?: 8765
                _uiState.update {
                    it.copy(
                        isServerRunning = true,
                        port = actualPort,
                        lanIp = LanNetworkUtils.findLanIpv4Address(),
                        allowlistCount = repository.load().channels.size
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isServerRunning = false, syncStatusMessage = "Failed to start server: ${e.message}") }
            }
        } else {
            curationServer?.stop()
            curationServer = null
            _uiState.update { it.copy(isServerRunning = false) }
        }
    }

    fun importM3uUrl(rawUrl: String, onDone: (Boolean) -> Unit = {}) {
        val urlStr = rawUrl.trim()
        if (urlStr.isEmpty()) {
            onDone(true)
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncStatusMessage = "Fetching M3U playlist...") }
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val conn = URL(urlStr).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    try {
                        conn.inputStream.use { stream ->
                            StreamingM3uParser.parse(stream)
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                val channels = parsed.candidates.mapIndexed { idx, c ->
                    AllowlistChannel(
                        id = c.suggestedId.ifBlank { "ch_${idx + 1}" },
                        name = c.name.ifBlank { "Channel ${idx + 1}" },
                        group = c.group,
                        logoUrl = c.logoUrl,
                        tvgId = c.tvgId,
                        streamUrl = c.streamUrl,
                        headers = c.headers
                    )
                }
                withContext(Dispatchers.IO) {
                    repository.save(channels)
                }
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        allowlistCount = channels.size,
                        suggestedEpgUrl = parsed.suggestedEpgUrl ?: it.suggestedEpgUrl,
                        syncStatusMessage = "Imported ${channels.size} M3U channels into allowlist."
                    )
                }
                onDone(true)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatusMessage = "Failed to import M3U: ${e.message}"
                    )
                }
                onDone(false)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun scanIptvChannels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncStatusMessage = "Initializing IPTV stream scan...") }
            try {
                val app = getApplication<Application>()
                val scanManager = SourceScanManager(app)

                val collectJob = launch {
                    scanManager.scanState.collect { state ->
                        when (state) {
                            is ScanState.Scanning -> {
                                _uiState.update {
                                    it.copy(
                                        isSyncing = true,
                                        syncStatusMessage = "[${state.progressPercent}%] ${state.step}"
                                    )
                                }
                            }
                            is ScanState.Completed -> {
                                TvhUiRepository.invalidateCaches()
                                com.nmaclean.tvheadend.iptv.epg.IptvEpgSyncWorker.schedulePeriodicSync(app)
                                _uiState.update {
                                    it.copy(
                                        isSyncing = false,
                                        syncStatusMessage = state.summary,
                                        allowlistCount = repository.load().channels.size
                                    )
                                }
                            }
                            is ScanState.Error -> {
                                _uiState.update {
                                    it.copy(
                                        isSyncing = false,
                                        syncStatusMessage = "Scan error: ${state.message}"
                                    )
                                }
                            }
                            is ScanState.Idle -> {}
                        }
                    }
                }

                scanManager.startScan(ScanSource.IPTV)
                collectJob.cancel()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatusMessage = "Scan failed: ${e.message}"
                    )
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun saveAndSyncEpgUrl(rawEpgUrl: String, onDone: () -> Unit = {}) {
        val trimmed = rawEpgUrl.trim()
        viewModelScope.launch {
            if (trimmed.isNotEmpty()) {
                epgSettings.isEpgEnabled = true
                epgSettings.epgUrl = trimmed
                _uiState.update { it.copy(isSyncing = true, syncStatusMessage = "Syncing XMLTV EPG guide...") }
                try {
                    val app = getApplication<Application>()
                    val handler = IptvSourceHandler()
                    val summary = handler.scan(app) { step, pct ->
                        _uiState.update { it.copy(isSyncing = true, syncStatusMessage = "[$pct%] $step") }
                    }
                    TvhUiRepository.invalidateCaches()
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            allowlistCount = repository.load().channels.size,
                            syncStatusMessage = summary
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isSyncing = false,
                            syncStatusMessage = "EPG sync warning: ${e.message}"
                        )
                    }
                }
            }
            onDone()
        }
    }

    fun clearChannels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncStatusMessage = "Clearing TIF channels and EPG programs...") }
            try {
                val app = getApplication<Application>()
                val scanManager = SourceScanManager(app)
                val result = scanManager.clearTvInputData(com.nmaclean.tvheadend.iptv.scan.ClearTarget.IPTV)
                TvhUiRepository.invalidateCaches()
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatusMessage = "Cleared ${result.clearedChannels} IPTV TIF channels, ${result.clearedPrograms} EPG programs, and ${result.clearedLogos} logos.",
                        allowlistCount = repository.load().channels.size
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSyncing = false,
                        syncStatusMessage = "Clear failed: ${e.message}"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        curationServer?.stop()
        curationServer = null
    }
}

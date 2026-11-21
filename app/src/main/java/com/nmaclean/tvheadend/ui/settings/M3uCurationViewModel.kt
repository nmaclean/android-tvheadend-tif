package com.nmaclean.tvheadend.ui.settings

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.iptv.scan.ScanSource
import com.nmaclean.tvheadend.iptv.scan.ScanState
import com.nmaclean.tvheadend.iptv.scan.SourceScanManager
import com.nmaclean.tvheadend.iptv.server.LanCurationServer
import com.nmaclean.tvheadend.iptv.server.LanNetworkUtils
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class M3uCurationUiState(
    val isServerRunning: Boolean = false,
    val lanIp: String? = null,
    val port: Int = 8765,
    val allowlistCount: Int = 0,
    val syncStatusMessage: String? = null,
    val isSyncing: Boolean = false
)

class M3uCurationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AllowlistRepository(application.filesDir)
    private var curationServer: LanCurationServer? = null

    private val _uiState = MutableStateFlow(
        M3uCurationUiState(
            lanIp = LanNetworkUtils.findLanIpv4Address(),
            allowlistCount = repository.load().channels.size
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

    fun clearChannels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncStatusMessage = "Clearing TIF channels and EPG programs...") }
            try {
                val app = getApplication<Application>()
                val scanManager = SourceScanManager(app)
                val result = scanManager.clearTvInputData(com.nmaclean.tvheadend.iptv.scan.ClearTarget.IPTV)
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

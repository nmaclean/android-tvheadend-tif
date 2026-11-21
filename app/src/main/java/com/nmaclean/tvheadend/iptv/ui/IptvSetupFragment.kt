package com.nmaclean.tvheadend.iptv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.BuildConfig
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSyncWorker
import com.nmaclean.tvheadend.iptv.scan.ClearTarget
import com.nmaclean.tvheadend.iptv.scan.ScanSource
import com.nmaclean.tvheadend.iptv.scan.ScanState
import com.nmaclean.tvheadend.iptv.scan.SourceScanManager
import com.nmaclean.tvheadend.iptv.server.LanCurationServer
import com.nmaclean.tvheadend.iptv.server.LanNetworkUtils
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import com.nmaclean.tvheadend.iptv.tif.IptvTvInputService
import com.nmaclean.tvheadend.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Standard Leanback GuidedStepSupportFragment TV Setup Flow for IPTV Input,
 * matching TvheadendInputService and Android TV's native Live Channels TIF UI.
 */
class IptvWelcomeFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ID_LAN_SERVER = 1L
        private const val ID_SCAN = 2L
        private const val ID_CLEAR = 3L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "IPTV Stream Setup"
        val repository = AllowlistRepository(requireContext().filesDir)
        val channelCount = repository.load().channels.size
        val description = "Configure M3U playlist stream sources, LAN curation server, and XMLTV program guide for Android TV Live Channels.\n\nChannels in Allowlist: $channelCount\nVersion: v${BuildConfig.VERSION_NAME}"
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_LAN_SERVER)
                .title("LAN Curation Server Setup")
                .description("Upload M3U file or edit channels from phone/PC browser")
                .build()
        )
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_SCAN)
                .title("Start Channel Scan & Sync")
                .description("Parse M3U allowlist and write channels to TvContract")
                .build()
        )
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_CLEAR)
                .title("Clear IPTV Channels")
                .description("Delete registered IPTV channels and EPG from TvContract")
                .build()
        )
    }

    @OptIn(UnstableApi::class)
    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_LAN_SERVER -> add(parentFragmentManager, IptvLanServerFragment())
            ID_SCAN -> add(parentFragmentManager, IptvChannelScanFragment())
            ID_CLEAR -> {
                CoroutineScope(Dispatchers.IO).launch {
                    val scanManager = SourceScanManager(requireContext())
                    val result = scanManager.clearTvInputData(ClearTarget.IPTV)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Cleared ${result.clearedChannels} IPTV channels", Toast.LENGTH_SHORT).show()
                        add(parentFragmentManager, IptvWelcomeFragment())
                    }
                }
            }
        }
    }
}

class IptvLanServerFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ID_TOGGLE_SERVER = 1L
        private const val ID_SCAN = 2L
        private const val ID_BACK = 3L
    }

    private var server: LanCurationServer? = null
    private var isRunning = false

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val lanIp = LanNetworkUtils.findLanIpv4Address()
        val repository = AllowlistRepository(requireContext().filesDir)
        val count = repository.load().channels.size
        val serverUrl = if (lanIp != null) "http://$lanIp:8765" else "No active LAN IP"
        val statusText = if (isRunning) "RUNNING at $serverUrl" else "STOPPED"
        val title = "LAN Curation Server"
        val description = "Status: $statusText\n\nOpen $serverUrl on your phone or PC browser to upload M3U files or curate channel playlists.\n\nChannels Prepared: $count"
        val breadcrumb = "IPTV Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_TOGGLE_SERVER)
                .title(if (isRunning) "Stop Server" else "Start LAN Server")
                .description(if (isRunning) "Stop background web curation server" else "Listen on port 8765 for web browser uploads")
                .build()
        )
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_SCAN)
                .title("Proceed to Channel Scan")
                .description("Import saved channels into Android TV")
                .build()
        )
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_BACK)
                .title("Back")
                .description("Return to setup menu")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        when (action.id) {
            ID_TOGGLE_SERVER -> {
                if (isRunning) {
                    server?.stop()
                    server = null
                    isRunning = false
                } else {
                    try {
                        server = LanCurationServer(requireContext().applicationContext, 8765)
                        server?.start()
                        isRunning = true
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Failed to start server: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                val idx = findActionPositionById(ID_TOGGLE_SERVER)
                if (idx >= 0) {
                    actions[idx].title = if (isRunning) "Stop Server" else "Start LAN Server"
                    notifyActionChanged(idx)
                }
            }
            ID_SCAN -> add(parentFragmentManager, IptvChannelScanFragment())
            ID_BACK -> requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        server?.stop()
        server = null
    }
}

class IptvChannelScanFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ID_FINISH = 1L
        private const val ID_BACK = 2L
    }

    private var isScanning = true

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = if (isScanning) "Importing IPTV Channels" else "IPTV Scan Complete"
        val description = if (isScanning) "Parsing M3U allowlist and writing channels to TvContract..." else "Channels successfully registered in TvContract."
        val breadcrumb = "IPTV Channel Scan"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        if (!isScanning) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_FINISH)
                    .title("Done / Open Live Channels")
                    .description("Complete setup and browse imported IPTV channels")
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_BACK)
                    .title("Back")
                    .description("Return to IPTV setup")
                    .build()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isScanning) {
            startScan()
        }
    }

    @OptIn(UnstableApi::class)
    private fun startScan() {
        thread {
            val app = requireContext().applicationContext
            val scanManager = SourceScanManager(app)

            CoroutineScope(Dispatchers.Main).launch {
                scanManager.scanState.collect { state ->
                    if (!isAdded) return@collect
                    when (state) {
                        is ScanState.Scanning -> {
                            guidanceStylist?.descriptionView?.text = "[${state.progressPercent}%] ${state.step}"
                        }
                        is ScanState.Completed -> {
                            IptvEpgSyncWorker.schedulePeriodicSync(app)
                            isScanning = false
                            guidanceStylist?.titleView?.text = "IPTV Scan Complete"
                            guidanceStylist?.descriptionView?.text = state.summary

                            val newActions = mutableListOf<GuidedAction>()
                            newActions.add(
                                GuidedAction.Builder(activity)
                                    .id(ID_FINISH)
                                    .title("Done / Open Live Channels")
                                    .description("Complete setup and browse imported channels")
                                    .build()
                            )
                            newActions.add(
                                GuidedAction.Builder(activity)
                                    .id(ID_BACK)
                                    .title("Back")
                                    .description("Return to IPTV setup")
                                    .build()
                            )
                            setActions(newActions)
                        }
                        is ScanState.Error -> {
                            isScanning = false
                            guidanceStylist?.titleView?.text = "Scan Error"
                            guidanceStylist?.descriptionView?.text = state.message
                        }
                        is ScanState.Idle -> {}
                    }
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                scanManager.startScan(ScanSource.IPTV)
            }
        }
    }

    @OptIn(UnstableApi::class)
    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_FINISH) {
            val inputId = requireActivity().intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)
                ?: try { TvContract.buildInputId(ComponentName(requireContext(), IptvTvInputService::class.java)) } catch (_: Exception) { "" }

            val resultIntent = Intent().apply {
                if (inputId.isNotEmpty()) {
                    putExtra(TvInputInfo.EXTRA_INPUT_ID, inputId)
                }
            }
            requireActivity().setResult(Activity.RESULT_OK, resultIntent)
            if (requireActivity().callingActivity == null) {
                startActivity(Intent(requireContext(), MainActivity::class.java))
            }
            requireActivity().finish()
        } else if (action.id == ID_BACK) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}

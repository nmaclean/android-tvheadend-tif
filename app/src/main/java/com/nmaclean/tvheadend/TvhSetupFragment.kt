package com.nmaclean.tvheadend

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import com.nmaclean.tvheadend.model.TvhConstants
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.sync.TvhSyncWorker
import kotlin.concurrent.thread

class TvhWelcomeFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ID_START = 1L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "Tvheadend TIF Setup"
        val description = "Connect your Android TV to your Tvheadend server to stream live TV channels, program guides (EPG), and channel logos.\n\n\n\nBuild: ${BuildConfig.BUILD_TIME}"
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_START)
                .title("Start")
                .description("Configure server connection and import channels")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_START) {
            val credentialsFragment = TvhCredentialsFragment()
            add(parentFragmentManager, credentialsFragment)
        }
    }
}

class TvhCredentialsFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "TvhCredentialsFragment"
        private const val ID_HOST = 1L
        private const val ID_PORT = 2L
        private const val ID_HTTP_PORT = 3L
        private const val ID_USER = 4L
        private const val ID_PASS = 5L
        private const val ID_SAVE = 6L
        private const val ID_CLEAR = 7L
        private const val ID_BACK = 8L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "Server Credentials"
        val description = "Enter your server IP address, HTSP port, and optional authentication credentials."
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val settings = TvhSettings(requireContext())

        val hostDesc = if (settings.host.isEmpty()) "e.g., 192.168.1.50" else settings.host
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_HOST)
                .title("Server IP / Host")
                .description(hostDesc)
                .editable(true)
                .build()
        )

        val portDesc = if (settings.htspPort == 0) "${TvhConstants.DEFAULT_HTSP_PORT} (Default HTSP)" else settings.htspPort.toString()
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_PORT)
                .title("HTSP Port")
                .description(portDesc)
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )

        val httpPortDesc = if (settings.httpPort == 0) "${TvhConstants.DEFAULT_HTTP_PORT} (Default HTTP)" else settings.httpPort.toString()
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_HTTP_PORT)
                .title("HTTP Stream Port")
                .description(httpPortDesc)
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )

        val userDesc = if (settings.username.isEmpty()) "Optional username" else settings.username
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_USER)
                .title("Username")
                .description(userDesc)
                .editable(true)
                .build()
        )

        val passDesc = if (settings.password.isEmpty()) "Optional password" else "********"
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_PASS)
                .title("Password")
                .description(passDesc)
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_SAVE)
                .title("Save & Start Scanning")
                .description("Save settings and import channels & EPG")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_CLEAR)
                .title("Clear Settings")
                .description("Reset all fields to default values")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_BACK)
                .title("Back")
                .description("Return to welcome screen")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_BACK) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
            return
        }

        if (action.id == ID_CLEAR) {
            TvhSettings(requireContext()).clear()
            TvhSyncManager.clearAllChannelsAndPrograms(requireContext())
            Toast.makeText(activity, "Settings & Guide cleared", Toast.LENGTH_SHORT).show()
            parentFragmentManager.beginTransaction()
                .replace(R.id.setup_fragment_container, TvhCredentialsFragment())
                .commit()
            return
        }

        if (action.id == ID_SAVE) {
            val hostAction = findActionById(ID_HOST)
            val portAction = findActionById(ID_PORT)
            val httpPortAction = findActionById(ID_HTTP_PORT)
            val userAction = findActionById(ID_USER)
            val passAction = findActionById(ID_PASS)

            val hostText = (hostAction?.description ?: hostAction?.title)?.toString()?.trim() ?: ""
            val host = if (hostText.startsWith("e.g.")) "" else hostText

            val portText = (portAction?.description ?: portAction?.title)?.toString() ?: ""
            val port = portText.filter { it.isDigit() }.toIntOrNull() ?: TvhConstants.DEFAULT_HTSP_PORT

            val httpPortText = (httpPortAction?.description ?: httpPortAction?.title)?.toString() ?: ""
            val httpPort = httpPortText.filter { it.isDigit() }.toIntOrNull() ?: TvhConstants.DEFAULT_HTTP_PORT

            val userText = (userAction?.description ?: userAction?.title)?.toString()?.trim() ?: ""
            val user = if (userText == "Optional username") "" else userText

            val passText = (passAction?.description ?: passAction?.title)?.toString()?.trim() ?: ""
            val pass = if (passText == "Optional password" || passText == "********") {
                TvhSettings(requireContext()).password
            } else {
                passText
            }

            Log.d(TAG, "Saving settings - Host: '$host', Port: $port, HTTP Port: $httpPort, User: '$user'")

            val settings = TvhSettings(requireContext())
            settings.host = host
            settings.htspPort = port
            settings.httpPort = httpPort
            settings.username = user
            settings.password = pass

            val resultFragment = TvhSetupResultFragment.newInstance(isScanning = true)
            add(parentFragmentManager, resultFragment)
        }
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val index = findActionPositionById(action.id)
        if (index >= 0) {
            val updatedAction = GuidedAction.Builder(context)
                .id(action.id)
                .title(action.title)
                .description(action.editTitle)
                .editable(action.isEditable)
                .editInputType(action.editInputType)
                .build()
            actions[index] = updatedAction
            notifyActionChanged(index)
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onGuidedActionEditCanceled(action: GuidedAction) {
        val index = findActionPositionById(action.id)
        if (index >= 0) {
            val updatedAction = GuidedAction.Builder(context)
                .id(action.id)
                .title(action.title)
                .description(action.editTitle)
                .editable(action.isEditable)
                .editInputType(action.editInputType)
                .build()
            actions[index] = updatedAction
            notifyActionChanged(index)
        }
    }
}

class TvhSetupResultFragment : GuidedStepSupportFragment() {
    companion object {
        private const val ARG_IS_SCANNING = "arg_is_scanning"
        private const val ARG_CHANNELS = "arg_channels"
        private const val ARG_LOGOS = "arg_logos"
        private const val ARG_PROGRAMS = "arg_programs"
        private const val ID_FINISH = 100L
        private const val ID_BACK = 101L

        fun newInstance(isScanning: Boolean, channels: Int = 0, logos: Int = 0, programs: Int = 0): TvhSetupResultFragment {
            val fragment = TvhSetupResultFragment()
            val args = Bundle().apply {
                putBoolean(ARG_IS_SCANNING, isScanning)
                putInt(ARG_CHANNELS, channels)
                putInt(ARG_LOGOS, logos)
                putInt(ARG_PROGRAMS, programs)
            }
            fragment.arguments = args
            return fragment
        }
    }

    private var isScanning = true
    private var channelCount = 0
    private var logoCount = 0
    private var programCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            isScanning = it.getBoolean(ARG_IS_SCANNING, true)
            channelCount = it.getInt(ARG_CHANNELS, 0)
            logoCount = it.getInt(ARG_LOGOS, 0)
            programCount = it.getInt(ARG_PROGRAMS, 0)
        }
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = if (isScanning) "Scanning Channels" else "Setup Complete"
        val description = if (isScanning) {
            "Connecting to Tvheadend server and importing live TV channels, logotypes, and EPG program guide in the background...\n\nPlease wait."
        } else {
            "Successfully imported $channelCount channels, $logoCount logotypes, and $programCount EPG programs from Tvheadend.\n\nSelect Finish below to complete setup and start watching TV."
        }
        val breadcrumb = "TV Input Setup"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        if (isScanning) {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_BACK)
                    .title("Cancel / Back")
                    .description("Return to server credentials")
                    .build()
            )
        } else {
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_FINISH)
                    .title("Finish")
                    .description("Complete setup and start watching TV")
                    .build()
            )
            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_BACK)
                    .title("Back")
                    .description("Modify server settings or re-sync")
                    .build()
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isScanning) {
            startBackgroundSync()
        } else {
            val finishIndex = findActionPositionById(ID_FINISH)
            if (finishIndex >= 0) {
                setSelectedActionPosition(finishIndex)
            }
        }
    }

    private var syncStarted = false

    @SuppressLint("SetTextI18n")
    private fun startBackgroundSync() {
        if (syncStarted) return
        syncStarted = true

        thread {
            val result = TvhSyncManager.performSync(requireContext())

            if (!isAdded) return@thread

            requireActivity().runOnUiThread {
                if (result.success) {
                    Toast.makeText(activity, "Import successful!", Toast.LENGTH_SHORT).show()
                    isScanning = false
                    channelCount = result.channelCount
                    logoCount = result.logoCount
                    programCount = result.programCount

                    // Update actions list
                    val newActions = mutableListOf<GuidedAction>()
                    newActions.add(
                        GuidedAction.Builder(activity)
                            .id(ID_FINISH)
                            .title("Finish")
                            .description("Complete setup and start watching TV")
                            .build()
                    )
                    newActions.add(
                        GuidedAction.Builder(activity)
                            .id(ID_BACK)
                            .title("Back")
                            .description("Modify server settings or re-sync")
                            .build()
                    )
                    setActions(newActions)

                    // Default focus to the Finish button
                    val finishIndex = findActionPositionById(ID_FINISH)
                    if (finishIndex >= 0) {
                        setSelectedActionPosition(finishIndex)
                    }

                    // Update guidance text
                    val stylist = guidanceStylist
                    if (stylist != null) {
                        stylist.titleView?.text = "Setup Complete"
                        stylist.descriptionView?.text = "Successfully imported $channelCount channels, $logoCount logotypes, and $programCount EPG programs from Tvheadend.\n\nSelect Finish below to complete setup and start watching TV."
                    }
                } else {
                    Toast.makeText(activity, "Error: ${result.error}", Toast.LENGTH_LONG).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_FINISH) {
            val syncIntent = Intent(requireContext(), SyncService::class.java)
            requireContext().startService(syncIntent)
            TvhSyncWorker.schedulePeriodicSync(requireContext())

            requireActivity().setResult(Activity.RESULT_OK)
            requireActivity().finish()
        } else if (action.id == ID_BACK) {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }
}

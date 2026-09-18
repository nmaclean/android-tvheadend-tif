package com.nmaclean.tvheadend

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import kotlin.concurrent.thread

class TvhSetupFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "TvhSetupFragment"
        private const val ID_HOST = 1L
        private const val ID_PORT = 2L
        private const val ID_HTTP_PORT = 3L
        private const val ID_USER = 4L
        private const val ID_PASS = 5L
        private const val ID_SAVE = 6L
        private const val ID_CLEAR = 7L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "Tvheadend TIF Setup"
        val description = "Enter your server credentials and ports to import channels and EPG.\n\n\n\nBuild: ${BuildConfig.BUILD_TIME}"
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
                .title("Save & Complete Setup")
                .description("Import channels and EPG program guide")
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_CLEAR)
                .title("Clear Settings")
                .description("Reset all fields to default values")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ID_CLEAR) {
            TvhSettings(requireContext()).clear()
            Toast.makeText(activity, "Settings cleared", Toast.LENGTH_SHORT).show()
            parentFragmentManager.beginTransaction()
                .replace(R.id.setup_fragment_container, TvhSetupFragment())
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

            Log.d(TAG, "Starting setup with Host: '$host', Port: $port, HTTP Port: $httpPort, User: '$user'")

            val settings = TvhSettings(requireContext())
            settings.host = host
            settings.htspPort = port
            settings.httpPort = httpPort
            settings.username = user
            settings.password = pass

            Toast.makeText(activity, "Importing channels...", Toast.LENGTH_LONG).show()

            thread {
                val result = TvhSyncManager.performChannelSync(requireContext())

                requireActivity().runOnUiThread {
                    if (result.success) {
                        Toast.makeText(activity, "Imported ${result.channelCount} channels! EPG & logos syncing in background.", Toast.LENGTH_LONG).show()

                        val syncIntent = Intent(requireContext(), SyncService::class.java)
                        requireContext().startService(syncIntent)

                        requireActivity().setResult(Activity.RESULT_OK)
                        requireActivity().finish()
                    } else {
                        Toast.makeText(activity, "Error: ${result.error}", Toast.LENGTH_LONG).show()
                    }
                }
            }
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
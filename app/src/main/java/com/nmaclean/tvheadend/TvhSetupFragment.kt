package com.nmaclean.tvheadend

import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.media.tv.TvContract
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
        val description = "Enter your server credentials and ports to import channels."
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

        val portDesc = if (settings.htspPort == 0 || settings.htspPort == 9982) "9982 (Default HTSP)" else settings.htspPort.toString()
        actions.add(
            GuidedAction.Builder(activity)
                .id(ID_PORT)
                .title("HTSP Port")
                .description(portDesc)
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )

        val httpPortDesc = if (settings.httpPort == 0 || settings.httpPort == 9981) "9981 (Default HTTP)" else settings.httpPort.toString()
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
                .description("Import channels and finish configuration")
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
            // Reload the fragment to refresh the UI
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
            val port = portText.filter { it.isDigit() }.toIntOrNull() ?: 9982

            val httpPortText = (httpPortAction?.description ?: httpPortAction?.title)?.toString() ?: ""
            val httpPort = httpPortText.filter { it.isDigit() }.toIntOrNull() ?: 9981

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

            Toast.makeText(activity, "Connecting to Tvheadend...", Toast.LENGTH_SHORT).show()

            thread {
                try {
                    val client = HtspClient(host, port)
                    if (client.connect(user, pass)) {
                        val channels = client.fetchChannels()
                        client.disconnect()

                        Log.d(TAG, "Fetched ${channels.size} channels from HTSP client.")
                        val resolver = requireActivity().contentResolver

                        val inputId = TvContract.buildInputId(
                            ComponentName(requireContext(), TvheadendInputService::class.java)
                        )
                        resolver.delete(TvContract.buildChannelsUriForInput(inputId), null, null)

                        for (ch in channels) {
                            val logoUri = "http://$host:$httpPort/imagecache/channels/${ch.uuid}"
                            val uuidToStore = if (ch.uuid.isNotEmpty()) ch.uuid else ch.id.toString()

                            val values = ContentValues().apply {
                                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, ch.number.toString())
                                put(TvContract.Channels.COLUMN_DISPLAY_NAME, ch.name)
                                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                                put(TvContract.Channels.COLUMN_APP_LINK_ICON_URI, logoUri)
                                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA, uuidToStore.toByteArray(Charsets.UTF_8))
                            }
                            resolver.insert(TvContract.Channels.CONTENT_URI, values)
                        }

                        requireActivity().runOnUiThread {
                            Toast.makeText(activity, "Setup SUCCESS! Imported ${channels.size} channels.", Toast.LENGTH_LONG).show()
                            requireActivity().setResult(Activity.RESULT_OK)
                            requireActivity().finish()
                        }
                    } else {
                        requireActivity().runOnUiThread {
                            Toast.makeText(activity, "Authentication failed.", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during setup sync", e)
                    requireActivity().runOnUiThread {
                        Toast.makeText(activity, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

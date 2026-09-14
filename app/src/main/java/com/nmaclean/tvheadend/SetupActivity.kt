package com.nmaclean.tvheadend

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import kotlin.concurrent.thread

class SetupActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(
                this,
                TvhSetupFragment(),
                android.R.id.content
            )
        }
    }

    class TvhSetupFragment : GuidedStepSupportFragment() {
        companion object {
            private const val TAG = "TvhSetupFragment"
            private const val ID_HOST = 1L
            private const val ID_PORT = 2L
            private const val ID_HTTP_PORT = 3L
            private const val ID_USER = 4L
            private const val ID_PASS = 5L
            private const val ID_SAVE = 6L
        }

        override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
            val title = "Tvheadend TIF Setup"
            val description = "Enter your server credentials and ports to import channels."
            val breadcrumb = "TV Input Setup"
            return GuidanceStylist.Guidance(title, description, breadcrumb, null)
        }

        override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
            val prefs = requireActivity().getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)

            val host = prefs.getString("host", "192.168.4.100") ?: "192.168.4.100"
            val htspPort = prefs.getInt("port", 9982).toString()
            val httpPort = prefs.getInt("httpPort", 9981).toString()
            val user = prefs.getString("user", "admin") ?: "admin"
            val pass = prefs.getString("pass", "ab1903") ?: "ab1903"

            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_HOST)
                    .title("Server IP / Host")
                    .description(host)
                    .editable(true)
                    .build()
            )

            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_PORT)
                    .title("HTSP Port")
                    .description(htspPort)
                    .editable(true)
                    .editInputType(InputType.TYPE_CLASS_NUMBER)
                    .build()
            )

            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_HTTP_PORT)
                    .title("HTTP Stream Port")
                    .description(httpPort)
                    .editable(true)
                    .editInputType(InputType.TYPE_CLASS_NUMBER)
                    .build()
            )

            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_USER)
                    .title("Username")
                    .description(user)
                    .editable(true)
                    .build()
            )

            actions.add(
                GuidedAction.Builder(activity)
                    .id(ID_PASS)
                    .title("Password")
                    .description(pass)
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
        }

        override fun onGuidedActionClicked(action: GuidedAction) {
            if (action.id == ID_SAVE) {
                val host = findActionById(ID_HOST)?.description?.toString()?.trim() ?: "192.168.4.100"
                val port = findActionById(ID_PORT)?.description?.toString()?.toIntOrNull() ?: 9982
                val httpPort = findActionById(ID_HTTP_PORT)?.description?.toString()?.toIntOrNull() ?: 9981
                val user = findActionById(ID_USER)?.description?.toString()?.trim() ?: "admin"
                val pass = findActionById(ID_PASS)?.description?.toString()?.trim() ?: "ab1903"

                val prefs = requireActivity().getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("host", host)
                    .putInt("port", port)
                    .putInt("httpPort", httpPort)
                    .putString("user", user)
                    .putString("pass", pass)
                    .apply()

                Toast.makeText(activity, "Connecting to Tvheadend...", Toast.LENGTH_SHORT).show()

                thread {
                    try {
                        val client = HtspClient(host, port)
                        if (client.connect(user, pass)) {
                            val channels = client.fetchChannels()
                            client.disconnect()

                            Log.d(TAG, "Fetched ${channels.size} channels from HTSP client.")
                            val resolver = requireActivity().contentResolver

                            resolver.delete(
                                TvContract.buildChannelsUriForInput(MainActivity.INPUT_ID),
                                null, null
                            )

                            for (ch in channels) {
                                val logoUri = "http://$host:$httpPort/imagecache/channels/${ch.uuid}"
                                val uuidToStore = if (ch.uuid.isNotEmpty()) ch.uuid else ch.id.toString()

                                val values = ContentValues().apply {
                                    put(TvContract.Channels.COLUMN_INPUT_ID, MainActivity.INPUT_ID)
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
                                val activityRef = requireActivity()
                                activityRef.setResult(Activity.RESULT_OK)
                                activityRef.finish()
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
    }
}

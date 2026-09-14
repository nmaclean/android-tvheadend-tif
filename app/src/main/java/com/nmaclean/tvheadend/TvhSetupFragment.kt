package com.nmaclean.tvheadend

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

class TvhSetupFragment : GuidedStepSupportFragment() {

    companion object {
        private const val TAG = "TvhSetupFragment"
        private const val ACTION_ID_HOST = 1L
        private const val ACTION_ID_PORT = 2L
        private const val ACTION_ID_USER = 3L
        private const val ACTION_ID_PASS = 4L
        private const val ACTION_ID_SAVE = 5L
    }

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val title = "Tvheadend Server Setup"
        val description = "Enter your server credentials to connect and import live channels & EPG."
        val breadcrumb = "Configuration"
        return GuidanceStylist.Guidance(title, description, breadcrumb, null)
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        val prefs = requireActivity().getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)

        val host = prefs.getString("host", "192.168.4.100") ?: "192.168.4.100"
        val port = prefs.getInt("port", 9982).toString()
        val user = prefs.getString("user", "admin") ?: "admin"
        val pass = prefs.getString("pass", "ab1903") ?: "ab1903"

        actions.add(
            GuidedAction.Builder(activity)
                .id(ACTION_ID_HOST)
                .title("Server IP / Host")
                .description(host)
                .editable(true)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ACTION_ID_PORT)
                .title("Port")
                .description(port)
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_NUMBER)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ACTION_ID_USER)
                .title("Username")
                .description(user)
                .editable(true)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ACTION_ID_PASS)
                .title("Password")
                .description(pass)
                .editable(true)
                .editInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                .build()
        )

        actions.add(
            GuidedAction.Builder(activity)
                .id(ACTION_ID_SAVE)
                .title("Save & Connect")
                .description("Validate and save configuration")
                .build()
        )
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id == ACTION_ID_SAVE) {
            val hostAction = findActionById(ACTION_ID_HOST)
            val portAction = findActionById(ACTION_ID_PORT)
            val userAction = findActionById(ACTION_ID_USER)
            val passAction = findActionById(ACTION_ID_PASS)

            val host = hostAction?.description?.toString()?.trim() ?: "192.168.4.100"
            val port = portAction?.description?.toString()?.toIntOrNull() ?: 9982
            val user = userAction?.description?.toString()?.trim() ?: "admin"
            val pass = passAction?.description?.toString()?.trim() ?: "ab1903"

            val prefs = requireActivity().getSharedPreferences("TvhPrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("host", host)
                .putInt("port", port)
                .putString("user", user)
                .putString("pass", pass)
                .apply()

            Log.d(TAG, "Configuration saved. Triggering SyncService to import channels...")
            
            // Start the SyncService to handle background connection, fetching, and TIF provider insertion
            val intent = Intent(requireContext(), SyncService::class.java)
            requireContext().startService(intent)

            requireActivity().setResult(Activity.RESULT_OK)
            requireActivity().finish()
        }
    }
}

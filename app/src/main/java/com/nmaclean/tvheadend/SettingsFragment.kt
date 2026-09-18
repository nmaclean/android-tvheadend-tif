package com.nmaclean.tvheadend

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlin.concurrent.thread

/**
 * Preferences fragment allowing users to configure Tvheadend connection settings and trigger manual channel/EPG sync.
 */
@OptIn(UnstableApi::class)
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = TvhConstants.PREFS_NAME
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("sync_channels")?.setOnPreferenceClickListener {
            val context = context ?: return@setOnPreferenceClickListener false
            Toast.makeText(context, "Syncing channels & EPG...", Toast.LENGTH_SHORT).show()

            thread {
                val result = TvhSyncManager.performSync(context)
                activity?.runOnUiThread {
                    if (result.success) {
                        Toast.makeText(
                            context,
                            "Sync complete! ${result.channelCount} channels, ${result.eventCount} EPG events.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            "Sync failed: ${result.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            true
        }
    }
}

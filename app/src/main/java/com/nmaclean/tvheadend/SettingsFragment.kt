package com.nmaclean.tvheadend

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.updater.UpdatePromptDialog
import kotlin.concurrent.thread

/**
 * Preferences fragment allowing users to configure Tvheadend connection settings and trigger manual channel/EPG sync.
 */
@OptIn(UnstableApi::class)
class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "tvh_settings"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val debugPref = findPreference<SwitchPreferenceCompat>("streaming_debug")
        debugPref?.apply {
            val updateSummary = { isChecked: Boolean ->
                summary = if (isChecked) {
                    "Status: [ ON ] — Verbose logging enabled in Logcat for HTSP, demuxing & ExoPlayer"
                } else {
                    "Status: [ OFF ] — Standard logging"
                }
            }

            updateSummary(isChecked)

            setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                updateSummary(enabled)
                true
            }
        }

        findPreference<Preference>("sync_channels")?.setOnPreferenceClickListener {
            val context = context ?: return@setOnPreferenceClickListener false
            thread {
                val result = TvhSyncManager.performSync(context)
                activity?.runOnUiThread {
                    if (!result.success) {
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

        val updatePref = findPreference<Preference>("check_for_updates")
        updatePref?.summary = "Current version: v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME}) — Check GitHub Releases"
        updatePref?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener false
            UpdatePromptDialog(ctx).show()
            true
        }

        suppressTvRemoteImeBanner(preferenceScreen)
    }

    private fun suppressTvRemoteImeBanner(group: PreferenceGroup?) {
        group ?: return
        for (i in 0 until group.preferenceCount) {
            when (val pref = group.getPreference(i)) {
                is EditTextPreference -> {
                    pref.setOnBindEditTextListener { editText ->
                        editText.imeOptions = editText.imeOptions or
                            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                            EditorInfo.IME_FLAG_NO_FULLSCREEN
                    }
                }
                is PreferenceGroup -> suppressTvRemoteImeBanner(pref)
            }
        }
    }
}

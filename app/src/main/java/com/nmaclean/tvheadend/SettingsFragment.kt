package com.nmaclean.tvheadend

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "TvhPrefs"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("sync_channels")?.setOnPreferenceClickListener {
            true
        }
    }
}

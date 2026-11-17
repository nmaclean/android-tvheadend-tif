package com.nmaclean.tvheadend.ui

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.SettingsFragment

/**
 * FragmentActivity hosting the General Settings preference screen (server credentials, diagnostic logs, EPG sync).
 */
@OptIn(UnstableApi::class)
class GeneralSettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, SettingsFragment())
                .commit()
        }
    }
}

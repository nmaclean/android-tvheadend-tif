package com.nmaclean.tvheadend.ui

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R

/**
 * Activity for configuring device TV inputs, running tuner scans, and managing stream sources.
 */
@OptIn(UnstableApi::class)
class ScanningInputsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, InputManagerFragment())
                .commit()
        }
    }
}

package com.nmaclean.tvheadend

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment

class SetupActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(
                this,
                TvhSetupFragment(),
                R.id.setup_fragment_container
            )
        }
    }
}

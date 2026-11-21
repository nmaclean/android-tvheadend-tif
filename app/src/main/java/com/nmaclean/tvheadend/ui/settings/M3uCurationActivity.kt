package com.nmaclean.tvheadend.ui.settings

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.GuidedStepSupportFragment
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.iptv.ui.IptvWelcomeFragment

class M3uCurationActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(
                this,
                IptvWelcomeFragment(),
                R.id.setup_fragment_container
            )
        }
    }
}

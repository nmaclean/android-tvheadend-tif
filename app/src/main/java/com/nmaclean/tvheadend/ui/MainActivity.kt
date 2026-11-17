package com.nmaclean.tvheadend.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.BrowseFrameLayout
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.model.TvhSettings
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.sync.TvhSyncWorker

/**
 * Main Android TV Launcher Activity that always loads directly to the Channel Guide & Settings Page.
 */
@OptIn(UnstableApi::class)
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val settings = TvhSettings(this)
        if (!settings.isFirstInstallCleanupDone) {
            settings.isFirstInstallCleanupDone = true
            TvhSyncManager.clearAllChannelsAndPrograms(this)
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, HomeFragment())
                .commit()
        }

        // Ensure 24-hour periodic EPG & channel polling work is scheduled
        TvhSyncWorker.schedulePeriodicSync(this)

        val rootLayout = findViewById<BrowseFrameLayout>(R.id.main_root_layout)
        val btnSettings = findViewById<View>(R.id.btn_header_settings)
        val mainContainer = findViewById<View>(R.id.main_browse_fragment)

        btnSettings?.apply {
            setOnClickListener {
                startActivity(Intent(this@MainActivity, InputManagerActivity::class.java))
            }
            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }

        // Custom Leanback focus search listener between top bar & content
        rootLayout?.setOnFocusSearchListener { focused, direction ->
            if (direction == View.FOCUS_UP && focused != btnSettings) {
                btnSettings
            } else if (direction == View.FOCUS_DOWN && focused == btnSettings) {
                val homeFrag = supportFragmentManager.findFragmentById(R.id.main_browse_fragment)
                homeFrag?.view ?: mainContainer
            } else {
                null
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SETTINGS || keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, InputManagerActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

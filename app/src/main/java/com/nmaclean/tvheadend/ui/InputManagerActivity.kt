package com.nmaclean.tvheadend.ui

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.view.View
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.ui.HomeFragment.HomeAction
import com.nmaclean.tvheadend.ui.presenter.HomeActionPresenter
import com.nmaclean.tvheadend.ui.presenter.InputCardPresenter

/**
 * Settings Hub activity presenting General Settings and Scanning & Inputs options.
 */
@OptIn(UnstableApi::class)
class InputManagerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_browse_fragment, SettingsHubFragment())
                .commit()
        }
    }
}

/**
 * Settings Hub fragment displaying high-level Settings sections: General Settings & Scanning & Inputs.
 */
@OptIn(UnstableApi::class)
class SettingsHubFragment : RowsSupportFragment() {

    private val mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = mRowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when ((item as? HomeAction)?.id) {
                "general_settings" -> {
                    startActivity(Intent(requireContext(), GeneralSettingsActivity::class.java))
                }
                "scanning_inputs" -> {
                    startActivity(Intent(requireContext(), ScanningInputsActivity::class.java))
                }
            }
        }

        populateRows()

        view.post {
            if (isAdded) {
                setSelectedPosition(0, false)
                view.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (mRowsAdapter.size() == 0) {
            populateRows()
        }
    }

    private fun populateRows() {
        if (mRowsAdapter.size() > 0) return
        mRowsAdapter.clear()

        val settingsAdapter = ArrayObjectAdapter(HomeActionPresenter())
        settingsAdapter.add(
            HomeAction(
                "general_settings",
                "General Settings",
                "Server IP, credentials & streaming debug",
                R.drawable.ic_settings_gear
            )
        )
        settingsAdapter.add(
            HomeAction(
                "scanning_inputs",
                "Scanning & Inputs",
                "TV inputs & stream sources configuration",
                R.drawable.ic_tv_input
            )
        )

        val header = HeaderItem(0, "Settings")
        mRowsAdapter.add(ListRow(header, settingsAdapter))
    }
}

class InputManagerFragment : VerticalGridSupportFragment() {
    companion object {
        private const val NUM_COLUMNS = 3
    }

    data class TifInputItem(val inputInfo: TvInputInfo, val channelCount: Int)

    private lateinit var mAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "TV Inputs & Channel Sources"

        val gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = NUM_COLUMNS
        }
        setGridPresenter(gridPresenter)

        mAdapter = ArrayObjectAdapter(InputCardPresenter())
        adapter = mAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is TifInputItem) {
                val setupIntent = item.inputInfo.createSetupIntent()
                if (setupIntent != null) {
                    startActivity(setupIntent)
                } else {
                    val intent = Intent(requireContext(), ChannelGridActivity::class.java).apply {
                        putExtra("input_id", item.inputInfo.id)
                    }
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadInputs()
    }

    private fun loadInputs() {
        val ctx = requireContext()
        val tvInputManager = ctx.getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        val inputs = tvInputManager.tvInputList
        val resolver = ctx.contentResolver

        val inputItems = mutableListOf<TifInputItem>()
        for (input in inputs) {
            // Only show TIF software/tuner inputs (exclude hardware passthrough inputs like HDMI, AV, Component)
            if (input.isPassthroughInput) continue

            var count = 0
            try {
                val uri = TvContract.buildChannelsUriForInput(input.id)
                resolver.query(uri, arrayOf(TvContract.Channels._ID), null, null, null)?.use { cursor ->
                    count = cursor.count
                }
            } catch (ignored: Exception) {}
            inputItems.add(TifInputItem(input, count))
        }

        // Deduplicate inputs by user-facing label so duplicate input services (e.g. Google Play Movies) appear only once.
        // Prefer the instance that has channels if one exists.
        val deduplicatedItems = inputItems.groupBy { item ->
            item.inputInfo.loadLabel(ctx)?.toString()?.trim() ?: item.inputInfo.id
        }.values.map { group ->
            group.maxByOrNull { it.channelCount } ?: group.first()
        }

        mAdapter.clear()
        deduplicatedItems.forEach { mAdapter.add(it) }
    }
}

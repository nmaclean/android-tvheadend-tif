package com.nmaclean.tvheadend.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.OptIn
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridView
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.ui.presenter.HomeActionPresenter

/**
 * Leanback Home Dashboard fragment displaying primary TV navigation options organized in left-aligned sections.
 */
@OptIn(UnstableApi::class)
class HomeFragment : RowsSupportFragment() {

    data class HomeAction(val id: String, val title: String, val description: String, val iconResId: Int)

    private val mRowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = mRowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when ((item as? HomeAction)?.id) {
                "guide" -> {
                    val intent = Intent(requireContext(), TvPlayerActivity::class.java).apply {
                        putExtra("open_epg", true)
                    }
                    startActivity(intent)
                }
                "server_settings" -> {
                    startActivity(Intent(requireContext(), InputManagerActivity::class.java))
                }
            }
        }

        populateRows()

        // Lock vertical grid alignment to top to prevent vertical bouncing/shifting on focus change
        verticalGridView?.apply {
            windowAlignment = VerticalGridView.WINDOW_ALIGN_NO_EDGE
            windowAlignmentOffset = 0
            windowAlignmentOffsetPercent = VerticalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
            itemAlignmentOffset = 0
            itemAlignmentOffsetPercent = VerticalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED
        }

        // Ensure the Live TV Guide card is selected without smooth scroll animation
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

        // Section: Live TV
        val mainAdapter = ArrayObjectAdapter(HomeActionPresenter())
        mainAdapter.add(HomeAction("guide", "Guide", "timeline program guide", R.drawable.ic_epg_guide))
        mRowsAdapter.add(ListRow(mainAdapter))
    }
}

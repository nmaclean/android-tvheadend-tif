package com.nmaclean.tvheadend.ui.presenter

import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.ui.InputManagerFragment.TifInputItem

/**
 * Presenter for rendering TIF Input items in InputManagerFragment.
 */
class InputCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(340, 190)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val inputItem = item as TifInputItem
        val ctx = viewHolder.view.context
        val cardView = viewHolder.view as ImageCardView

        val label = inputItem.inputInfo.loadLabel(ctx)?.toString() ?: "TV Input"

        cardView.titleText = label
        cardView.contentText = when (inputItem.channelCount) {
            0 -> "No channels"
            1 -> "1 Channel"
            else -> "${inputItem.channelCount} Channels"
        }
        cardView.mainImage = inputItem.inputInfo.loadIcon(ctx) ?: ContextCompat.getDrawable(ctx, R.drawable.ic_tv_banner)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }
}

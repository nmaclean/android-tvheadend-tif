package com.nmaclean.tvheadend.ui.presenter

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.ui.ChannelGuideFragment.SettingsItem

/**
 * Data holder for pre-fetched channel card information to avoid UI thread disk/database I/O.
 */
data class ChannelCardData(
    val channel: TvhChannel,
    val currentProgramTitle: String,
    val logo: Bitmap?
)

/**
 * Presenter for rendering Channel Grid cards using pre-fetched EPG and logo bitmaps.
 */
class ChannelCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(340, 195)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val cardView = viewHolder.view as ImageCardView
        val ctx = cardView.context

        if (item is SettingsItem) {
            cardView.titleText = item.title
            cardView.contentText = item.description
            cardView.mainImage = ContextCompat.getDrawable(ctx, R.drawable.ic_settings_gear)
            return
        }

        if (item is ChannelCardData) {
            val ch = item.channel
            cardView.titleText = "CH ${ch.number}  •  ${ch.name}"
            cardView.contentText = item.currentProgramTitle

            if (item.logo != null) {
                cardView.mainImage = BitmapDrawable(ctx.resources, item.logo)
            } else {
                cardView.mainImage = ContextCompat.getDrawable(ctx, R.drawable.ic_tv_placeholder)
            }
            return
        }

        if (item is TvhChannel) {
            cardView.titleText = "CH ${item.number}  •  ${item.name}"
            cardView.contentText = "No Program Information"
            cardView.mainImage = ContextCompat.getDrawable(ctx, R.drawable.ic_tv_placeholder)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }
}

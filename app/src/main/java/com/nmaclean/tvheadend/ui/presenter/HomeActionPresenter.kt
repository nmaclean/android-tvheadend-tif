package com.nmaclean.tvheadend.ui.presenter

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.ui.HomeFragment.HomeAction

/**
 * Presenter for rendering Home Dashboard action cards with small vector icons and glassmorphism styling.
 */
class HomeActionPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context

        val cardContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_mochi_card)
            isFocusable = true
            isClickable = true
            setPadding(dpToPx(ctx, 16), dpToPx(ctx, 14), dpToPx(ctx, 20), dpToPx(ctx, 14))

            val params = ViewGroup.MarginLayoutParams(dpToPx(ctx, 380), dpToPx(ctx, 100)).apply {
                setMargins(0, 0, dpToPx(ctx, 16), dpToPx(ctx, 12))
            }
            layoutParams = params

            setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.background = ContextCompat.getDrawable(ctx, R.drawable.bg_card_focused)
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    view.background = ContextCompat.getDrawable(ctx, R.drawable.bg_mochi_card)
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
        }

        // Left Icon Badge Container
        val iconBadge = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_card_rounded)
            val badgeParams = LinearLayout.LayoutParams(dpToPx(ctx, 52), dpToPx(ctx, 52)).apply {
                setMargins(0, 0, dpToPx(ctx, 16), 0)
            }
            layoutParams = badgeParams

            val iconIv = ImageView(ctx).apply {
                id = R.id.home_action_icon
                val iconParams = LinearLayout.LayoutParams(dpToPx(ctx, 28), dpToPx(ctx, 28))
                layoutParams = iconParams
            }
            addView(iconIv)
        }

        // Right Text Container
        val textStack = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL

            val titleTv = TextView(ctx).apply {
                id = R.id.home_action_title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
                setTypeface(null, Typeface.BOLD)
            }

            val descTv = TextView(ctx).apply {
                id = R.id.home_action_desc
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
                setPadding(0, dpToPx(ctx, 2), 0, 0)
            }

            addView(titleTv)
            addView(descTv)
        }

        cardContainer.addView(iconBadge)
        cardContainer.addView(textStack)

        return ViewHolder(cardContainer)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val action = item as HomeAction
        val cardView = viewHolder.view

        val iconIv = cardView.findViewById<ImageView>(R.id.home_action_icon)
        val titleTv = cardView.findViewById<TextView>(R.id.home_action_title)
        val descTv = cardView.findViewById<TextView>(R.id.home_action_desc)

        iconIv.setImageDrawable(ContextCompat.getDrawable(cardView.context, action.iconResId))
        titleTv.text = action.title
        descTv.text = action.description
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view
        val iconIv = cardView.findViewById<ImageView>(R.id.home_action_icon)
        iconIv.setImageDrawable(null)
    }

    private fun dpToPx(ctx: Context, dp: Int): Int {
        return (dp * ctx.resources.displayMetrics.density).toInt()
    }
}

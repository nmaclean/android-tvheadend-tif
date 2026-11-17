package com.nmaclean.tvheadend.ui

import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.view.View
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nmaclean.tvheadend.BuildConfig
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.ui.HomeFragment.HomeAction
import com.nmaclean.tvheadend.ui.presenter.HomeActionPresenter
import com.nmaclean.tvheadend.ui.presenter.InputCardPresenter
import com.nmaclean.tvheadend.ui.theme.MidnightCyanTokens
import com.nmaclean.tvheadend.ui.theme.TvClockHeader
import com.nmaclean.tvheadend.ui.theme.TvTheme
import com.nmaclean.tvheadend.ui.theme.focusCyanBorder
import com.nmaclean.tvheadend.updater.UpdatePromptDialog

/**
 * Settings Hub activity presenting Midnight-Cyan Compose cards for General Settings, Scanning & Inputs, Setup, and Updates.
 */
@OptIn(UnstableApi::class)
class InputManagerActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvTheme {
                SettingsHubScreen(
                    onOpenGeneralSettings = {
                        startActivity(Intent(this, GeneralSettingsActivity::class.java))
                    },
                    onOpenScanningInputs = {
                        startActivity(Intent(this, ScanningInputsActivity::class.java))
                    },
                    onCheckUpdates = {
                        UpdatePromptDialog(this).show()
                    }
                )
            }
        }
    }
}

private data class SettingsHubOption(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@kotlin.OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsHubScreen(
    onOpenGeneralSettings: () -> Unit,
    onOpenScanningInputs: () -> Unit,
    onCheckUpdates: () -> Unit
) {
    val options = remember {
        listOf(
            SettingsHubOption(
                title = "General Settings",
                subtitle = "Streaming debug mode, manual EPG sync & updates",
                icon = Icons.Outlined.Settings,
                onClick = onOpenGeneralSettings
            ),
            SettingsHubOption(
                title = "Scanning & Inputs",
                subtitle = "TV inputs, stream sources & TIF channel setup",
                icon = Icons.Outlined.LiveTv,
                onClick = onOpenScanningInputs
            ),
            SettingsHubOption(
                title = "App Updates",
                subtitle = "Installed: v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME})",
                icon = Icons.Outlined.SystemUpdate,
                onClick = onCheckUpdates
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightCyanTokens.BackgroundBrush)
            .padding(
                horizontal = MidnightCyanTokens.OverscanHorizontal,
                vertical = MidnightCyanTokens.OverscanVertical
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Settings",
                    color = MidnightCyanTokens.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Configure Tvheadend server, TIF inputs & diagnostics",
                    color = MidnightCyanTokens.TextSecondary,
                    fontSize = 14.sp
                )
            }
            TvClockHeader()
        }

        Spacer(modifier = Modifier.height(28.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier
                .fillMaxSize()
                .focusRestorer()
        ) {
            items(options, key = { it.title }) { option ->
                SettingsHubCard(option = option)
            }
        }
    }
}

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsHubCard(option: SettingsHubOption) {
    var isFocused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp)
            .clip(RoundedCornerShape(MidnightCyanTokens.CardCornerRadius))
            .background(if (isFocused) MidnightCyanTokens.CardBgElevated else MidnightCyanTokens.CardBg)
            .focusCyanBorder(isFocused = isFocused)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = option.onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = option.title,
            tint = if (isFocused) MidnightCyanTokens.CyanAccent else MidnightCyanTokens.TextSecondary,
            modifier = Modifier.size(34.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = option.title,
                color = MidnightCyanTokens.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = option.subtitle,
                color = MidnightCyanTokens.TextSecondary,
                fontSize = 13.sp
            )
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
        val deduplicatedItems = TvhUiRepository.loadTvInputs(requireContext()).map {
            TifInputItem(it.inputInfo, it.channelCount)
        }

        mAdapter.clear()
        deduplicatedItems.forEach { mAdapter.add(it) }
    }
}

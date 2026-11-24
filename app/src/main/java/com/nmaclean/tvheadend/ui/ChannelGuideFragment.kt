package com.nmaclean.tvheadend.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.VerticalGridPresenter
import androidx.media3.common.util.UnstableApi
import com.nmaclean.tvheadend.R
import com.nmaclean.tvheadend.SetupActivity
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.sync.EpgRepository
import com.nmaclean.tvheadend.sync.TvhSyncManager
import com.nmaclean.tvheadend.ui.presenter.ChannelCardData
import com.nmaclean.tvheadend.ui.presenter.ChannelCardPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Leanback Channel Grid fragment displaying channels and live EPG in a 4-column TV grid.
 */
@OptIn(UnstableApi::class)
class ChannelGuideFragment : VerticalGridSupportFragment() {

    companion object {
        private const val NUM_COLUMNS = 4
    }

    data class SettingsItem(val title: String, val description: String)

    private lateinit var mAdapter: ArrayObjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = "Live TV Channels"

        // Top Cyan Search/Settings Header Orb
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.tv_accent_cyan)
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SetupActivity::class.java))
        }

        val gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = NUM_COLUMNS
        }
        setGridPresenter(gridPresenter)

        mAdapter = ArrayObjectAdapter(ChannelCardPresenter())
        adapter = mAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is ChannelCardData -> {
                    val ch = item.channel
                    val intent = Intent(requireContext(), TvPlayerActivity::class.java).apply {
                        putExtra("channel_id", ch.id.toString())
                        putExtra("channel_name", ch.name)
                        putExtra("channel_number", ch.number)
                        putExtra("input_id", ch.icon)
                    }
                    startActivity(intent)
                }
                is TvhChannel -> {
                    val intent = Intent(requireContext(), TvPlayerActivity::class.java).apply {
                        putExtra("channel_id", item.id.toString())
                        putExtra("channel_name", item.name)
                        putExtra("channel_number", item.number)
                        putExtra("input_id", item.icon)
                    }
                    startActivity(intent)
                }
                is SettingsItem -> {
                    startActivity(Intent(requireContext(), SetupActivity::class.java))
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadChannels()
    }

    override fun onResume() {
        super.onResume()
        if (mAdapter.size() == 0) {
            loadChannels()
        }
    }

    private fun loadChannels() {
        val cached = ChannelRegistry.getAllChannels()
        if (cached.isNotEmpty()) {
            val cardItems = cached.map { ch -> ChannelCardData(ch, "Loading program...", null) }
            mAdapter.clear()
            cardItems.forEach { mAdapter.add(it) }
        }

        CoroutineScope(Dispatchers.IO).launch {
            val ctx = context ?: return@launch
            val channelList = mutableListOf<TvhChannel>()
            val resolver = ctx.contentResolver

            try {
                // Query ALL channels across ALL installed system TIF inputs
                resolver.query(
                    TvContract.Channels.CONTENT_URI,
                    arrayOf(
                        TvContract.Channels._ID,
                        TvContract.Channels.COLUMN_DISPLAY_NUMBER,
                        TvContract.Channels.COLUMN_DISPLAY_NAME,
                        TvContract.Channels.COLUMN_SERVICE_ID,
                        TvContract.Channels.COLUMN_INPUT_ID,
                        TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA
                    ),
                    null, null, null
                )?.use { cursor ->
                    val dbIdCol = cursor.getColumnIndex(TvContract.Channels._ID)
                    val numCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NUMBER)
                    val nameCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_DISPLAY_NAME)
                    val serviceIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_SERVICE_ID)
                    val inputIdCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INPUT_ID)
                    val dataCol = cursor.getColumnIndex(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_DATA)

                    while (cursor.moveToNext()) {
                        val dbId = cursor.getLong(dbIdCol)
                        val num = cursor.getString(numCol)?.toIntOrNull() ?: 1
                        val name = cursor.getString(nameCol) ?: "Channel"
                        val serviceId = if (serviceIdCol >= 0) cursor.getLong(serviceIdCol) else dbId
                        val inputId = if (inputIdCol >= 0) cursor.getString(inputIdCol) ?: "" else ""

                        val streamKey = if (dataCol >= 0) {
                            val bytes = cursor.getBlob(dataCol)
                            if (bytes != null && bytes.isNotEmpty()) String(bytes, Charsets.UTF_8) else serviceId.toString()
                        } else serviceId.toString()

                        channelList.add(TvhChannel(dbId, streamKey, name, num, "", inputId))
                    }
                }
            } catch (e: Exception) {
                Log.e("ChannelGuideFragment", "Error querying TvContract.Channels", e)
            }

            val uniqueChannels = channelList.distinctBy {
                if (it.uuid.isNotEmpty()) it.uuid else if (it.number > 0) "num_${it.number}" else "name_${it.name}"
            }.sortedWith(compareBy({ it.number }, { it.name }))

            // Blazingly fast single bulk query for all current EPG programs
            val nowMs = System.currentTimeMillis()
            val programsMap = EpgRepository.getAllProgramsInWindow(ctx, nowMs, nowMs + 1000L)

            // Pre-load logos in background with downsampling
            val logoMap = mutableMapOf<Long, Bitmap>()
            val logoOptions = BitmapFactory.Options().apply { inSampleSize = 2 }
            for (ch in uniqueChannels) {
                try {
                    val logoUri = TvContract.buildChannelLogoUri(ch.id)
                    resolver.openInputStream(logoUri)?.use { input ->
                        val bmp = BitmapFactory.decodeStream(input, null, logoOptions)
                        if (bmp != null) {
                            logoMap[ch.id] = bmp
                        }
                    }
                } catch (ignored: Exception) {}
            }

            val cardItems = uniqueChannels.map { ch ->
                val progs = programsMap[ch.id]
                val currentTitle = progs?.firstOrNull { nowMs in it.startTimeMs..it.endTimeMs }?.title
                    ?: "No Program Information"
                ChannelCardData(ch, currentTitle, logoMap[ch.id])
            }

            withContext(Dispatchers.Main) {
                mAdapter.clear()
                cardItems.forEach { mAdapter.add(it) }
            }

            // Background EPG sync check if database has 0 program entries
            if (programsMap.isEmpty() && uniqueChannels.isNotEmpty()) {
                try {
                    TvhSyncManager.performEpgAndLogoSync(ctx)
                    val updatedProgs = EpgRepository.getAllProgramsInWindow(ctx, nowMs, nowMs + 1000L)
                    val refreshedCardItems = uniqueChannels.map { ch ->
                        val progs = updatedProgs[ch.id]
                        val currentTitle = progs?.firstOrNull { nowMs in it.startTimeMs..it.endTimeMs }?.title
                            ?: "No Program Information"
                        ChannelCardData(ch, currentTitle, logoMap[ch.id])
                    }
                    withContext(Dispatchers.Main) {
                        mAdapter.clear()
                        refreshedCardItems.forEach { mAdapter.add(it) }
                    }
                } catch (ignored: Exception) {}
            }
        }
    }
}

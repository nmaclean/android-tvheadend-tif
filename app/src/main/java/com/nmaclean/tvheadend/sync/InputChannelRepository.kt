package com.nmaclean.tvheadend.sync

import android.content.Context
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.model.TvhChannel
import com.nmaclean.tvheadend.ui.TvhUiRepository

interface InputChannelRepository {
    fun loadChannels(context: Context): List<TvhChannel>
}

class TvheadendChannelRepository : InputChannelRepository {
    override fun loadChannels(context: Context): List<TvhChannel> {
        return ChannelRegistry.getAllChannels()
    }
}

class CombinedChannelRepository : InputChannelRepository {
    override fun loadChannels(context: Context): List<TvhChannel> {
        return TvhUiRepository.loadChannelsSync(context)
    }
}

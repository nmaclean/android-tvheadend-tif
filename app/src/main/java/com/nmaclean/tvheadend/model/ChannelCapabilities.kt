package com.nmaclean.tvheadend.model

/**
 * Polymorphic playback capabilities resolved per TV Input type and channel.
 */
data class ChannelCapabilities(
    val canPause: Boolean = true,
    val canSeek: Boolean = true,
    val canRecord: Boolean = true,
    val supportsTimeshift: Boolean = true,
    val showSignalMetrics: Boolean = true,
    val showEpgDetails: Boolean = true
)

interface InputPlaybackPolicy {
    fun resolveCapabilities(channel: TvhChannel?, isSeekable: Boolean = false): ChannelCapabilities
}

class TvheadendPlaybackPolicy : InputPlaybackPolicy {
    override fun resolveCapabilities(channel: TvhChannel?, isSeekable: Boolean): ChannelCapabilities {
        return ChannelCapabilities(
            canPause = true,
            canSeek = true,
            canRecord = true,
            supportsTimeshift = true,
            showSignalMetrics = true,
            showEpgDetails = true
        )
    }
}

class IptvPlaybackPolicy : InputPlaybackPolicy {
    override fun resolveCapabilities(channel: TvhChannel?, isSeekable: Boolean): ChannelCapabilities {
        return if (isSeekable) {
            ChannelCapabilities(
                canPause = true,
                canSeek = true,
                canRecord = false,
                supportsTimeshift = true,
                showSignalMetrics = false,
                showEpgDetails = true
            )
        } else {
            ChannelCapabilities(
                canPause = false,
                canSeek = false,
                canRecord = false,
                supportsTimeshift = false,
                showSignalMetrics = false,
                showEpgDetails = true
            )
        }
    }
}

object ChannelCapabilitiesResolver {
    fun resolve(channel: TvhChannel?, isSeekable: Boolean = false): ChannelCapabilities {
        if (channel == null) return TvheadendPlaybackPolicy().resolveCapabilities(null, isSeekable)
        val isIptv = channel.inputId.contains("IptvTvInputService") ||
                channel.uuid.startsWith("iptv:") ||
                channel.uuid.startsWith("http://") ||
                channel.uuid.startsWith("https://")
        val policy: InputPlaybackPolicy = if (isIptv) IptvPlaybackPolicy() else TvheadendPlaybackPolicy()
        return policy.resolveCapabilities(channel, isSeekable)
    }
}

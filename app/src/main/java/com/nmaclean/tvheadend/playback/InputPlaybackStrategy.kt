package com.nmaclean.tvheadend.playback

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.nmaclean.tvheadend.htsp.ChannelRegistry
import com.nmaclean.tvheadend.iptv.tif.IptvStreamResolver

sealed interface ResolvedStream {
    data class Htsp(val channelId: String) : ResolvedStream
    data class Http(val streamUrl: String, val headers: Map<String, String> = emptyMap()) : ResolvedStream
}

interface InputPlaybackStrategy {
    fun resolveStream(contentResolver: ContentResolver, channelUri: Uri, context: Context): ResolvedStream?
}

class HtspPlaybackStrategy : InputPlaybackStrategy {
    override fun resolveStream(contentResolver: ContentResolver, channelUri: Uri, context: Context): ResolvedStream? {
        val lastSegment = channelUri.lastPathSegment ?: return null
        val dbId = lastSegment.toLongOrNull() ?: return null
        val channel = ChannelRegistry.getChannelById(dbId)
        val htspId = channel?.uuid?.ifEmpty { channel.id.toString() } ?: lastSegment
        return ResolvedStream.Htsp(htspId)
    }
}

class IptvPlaybackStrategy : InputPlaybackStrategy {
    override fun resolveStream(contentResolver: ContentResolver, channelUri: Uri, context: Context): ResolvedStream? {
        val resolved = IptvStreamResolver.resolve(contentResolver, channelUri, context) ?: return null
        return ResolvedStream.Http(resolved.streamUrl, resolved.headers)
    }
}

package com.nmaclean.tvheadend.model

/**
 * Global constants for Tvheadend network socket buffers, ExoPlayer buffer targets, and HTSP settings.
 */
object TvhConstants {
    const val DEFAULT_HTSP_PORT = 9982
    const val DEFAULT_HTTP_PORT = 9981

    const val CLIENT_NAME = "Android TV TIF"
    const val HTSP_VERSION = 28

    const val SOCKET_CONNECT_TIMEOUT_MS = 5000
    const val SOCKET_SO_TIMEOUT_MS = 10000
    const val ASYNC_READ_TIMEOUT_MS = 3000
    const val ASYNC_METADATA_TIMEOUT_MS = 6000L
    const val MAX_HTSP_MESSAGE_LENGTH = 16 * 1024 * 1024

    const val SOCKET_RECEIVE_BUFFER_SIZE = 12 * 1024 * 1024
    const val SOCKET_SEND_BUFFER_SIZE = 2 * 1024 * 1024

    const val EXOPLAYER_MIN_BUFFER_MS = 2500
    const val EXOPLAYER_MAX_BUFFER_MS = 10000
    const val EXOPLAYER_BUFFER_FOR_PLAYBACK_MS = 450
    const val EXOPLAYER_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1000
}

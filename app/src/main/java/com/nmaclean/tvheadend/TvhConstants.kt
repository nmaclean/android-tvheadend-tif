package com.nmaclean.tvheadend

object TvhConstants {
    // Default Server Settings
    const val DEFAULT_HOST = "192.168.4.100"
    const val DEFAULT_HTSP_PORT = 9984
    const val DEFAULT_HTTP_PORT = 9983
    const val DEFAULT_USERNAME = "admin"
    const val DEFAULT_PASSWORD = "ab1903"

    // HTSP Protocol Identifiers
    const val CLIENT_NAME = "AndroidTV-TIFClient"
    const val HTSP_VERSION = 34

    // Network & Socket Timeouts (in milliseconds)
    const val SOCKET_CONNECT_TIMEOUT_MS = 10_000
    const val SOCKET_SO_TIMEOUT_MS = 60_000
    const val ASYNC_METADATA_TIMEOUT_MS = 8_000L
    const val ASYNC_READ_TIMEOUT_MS = 3_000

    // Buffer Configurations (in bytes)
    const val SOCKET_RECEIVE_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB
    const val SOCKET_SEND_BUFFER_SIZE = 512 * 1024          // 512 KB
    const val MAX_HTSP_MESSAGE_LENGTH = 10 * 1024 * 1024    // 10 MB

    // ExoPlayer Buffer Durations (in milliseconds)
    const val EXOPLAYER_MIN_BUFFER_MS = 5_000
    const val EXOPLAYER_MAX_BUFFER_MS = 30_000
    const val EXOPLAYER_BUFFER_FOR_PLAYBACK_MS = 1_500
    const val EXOPLAYER_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    // SharedPreferences Keys
    const val PREFS_NAME = "TvhPrefs"
    const val KEY_HOST = "host"
    const val KEY_HTSP_PORT = "port"
    const val KEY_HTTP_PORT = "httpPort"
    const val KEY_USERNAME = "user"
    const val KEY_PASSWORD = "pass"
}

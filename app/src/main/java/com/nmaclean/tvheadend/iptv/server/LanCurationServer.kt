package com.nmaclean.tvheadend.iptv.server

import android.content.Context
import com.nmaclean.tvheadend.iptv.epg.IptvEpgSettingsStore
import com.nmaclean.tvheadend.iptv.model.AllowlistChannel
import com.nmaclean.tvheadend.iptv.model.M3uCandidateChannel
import com.nmaclean.tvheadend.iptv.model.M3uParseResult
import com.nmaclean.tvheadend.iptv.parser.StreamingM3uParser
import com.nmaclean.tvheadend.iptv.scan.ScanSource
import com.nmaclean.tvheadend.iptv.scan.ScanState
import com.nmaclean.tvheadend.iptv.scan.SourceScanManager
import com.nmaclean.tvheadend.iptv.storage.AllowlistRepository
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.util.concurrent.TimeUnit

@Serializable
data class SaveAllowlistRequest(
    val channels: List<AllowlistChannel>,
    val confirmEmpty: Boolean = false
)

@Serializable
data class StatusResponse(
    val candidateCount: Int,
    val allowlistCount: Int,
    val skippedCount: Int,
    val logoCount: Int = 0,
    val epgProgramCount: Int = 0,
    val epgUrl: String? = null
)

@Serializable
data class UploadResponse(
    val success: Boolean,
    val candidateCount: Int,
    val skippedCount: Int,
    val groups: List<String>,
    val defaultEpgUrl: String? = null,
    val logoCount: Int = 0,
    val epgProgramCount: Int = 0
)

@Serializable
data class EpgUploadResponse(
    val success: Boolean,
    val programCount: Int,
    val channelCount: Int
)

@Serializable
data class ChannelsResponse(
    val channels: List<M3uCandidateChannel>,
    val total: Int,
    val skippedCount: Int,
    val groups: List<String>
)

@Serializable
data class EpgSettingsDto(
    val isEpgEnabled: Boolean,
    val epgUrl: String,
    val epgFallbackUrl: String,
    val lookaheadHours: Int,
    val offsetMinutes: Int,
    val matchByName: Boolean,
    val isLogosEnabled: Boolean,
    val useEpgIcon: Boolean
)

@Serializable
data class ScanResponse(
    val success: Boolean,
    val summary: String
)

@Serializable
data class ErrorResponse(
    val error: String
)

class LanCurationServer(
    private val context: Context,
    private val port: Int = 8765,
    private val onAllowlistUpdated: ((List<AllowlistChannel>) -> Unit)? = null
) {
    private var server: ApplicationEngine? = null
    private val repository = AllowlistRepository(context.filesDir)

    private var currentParseResult: M3uParseResult = M3uParseResult(emptyList(), 0, emptyList())
    var actualPort: Int = port
        private set

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun start() {
        if (server != null) return

        var activeEngine: ApplicationEngine? = null
        var currentTryPort = port

        for (attempt in 0..1) {
            try {
                activeEngine = embeddedServer(CIO, port = currentTryPort, host = "0.0.0.0") {
                    install(ContentNegotiation) {
                        json()
                    }
                    routing {
                        get("/") {
                            val html = loadIndexHtml()
                            call.respondText(html, io.ktor.http.ContentType.Text.Html)
                        }
                        get("/api/status") {
                            val allowlist = repository.load()
                            val store = IptvEpgSettingsStore(this@LanCurationServer.context)
                            val logoCount = currentParseResult.candidates.count { !it.logoUrl.isNullOrEmpty() }
                            val epgCount = getLocalEpgProgramCount()

                            call.respond(
                                StatusResponse(
                                    candidateCount = currentParseResult.candidates.size,
                                    allowlistCount = allowlist.channels.size,
                                    skippedCount = currentParseResult.skippedCount,
                                    logoCount = logoCount,
                                    epgProgramCount = epgCount,
                                    epgUrl = store.epgUrl
                                )
                            )
                        }
                        post("/api/upload") {
                            try {
                                val channelStream = call.receiveStream()
                                currentParseResult = StreamingM3uParser.parse(channelStream)
                                val store = IptvEpgSettingsStore(this@LanCurationServer.context)
                                val suggestedEpg = currentParseResult.suggestedEpgUrl
                                if (!suggestedEpg.isNullOrEmpty()) {
                                    store.epgUrl = suggestedEpg
                                    android.util.Log.i("LanCurationServer", "Updated IPTV EPG URL from upload to: $suggestedEpg")
                                }
                                val logoCount = currentParseResult.candidates.count { !it.logoUrl.isNullOrEmpty() }
                                val epgCount = getLocalEpgProgramCount()

                                call.respond(
                                    UploadResponse(
                                        success = true,
                                        candidateCount = currentParseResult.candidates.size,
                                        skippedCount = currentParseResult.skippedCount,
                                        groups = currentParseResult.groups,
                                        defaultEpgUrl = suggestedEpg ?: store.epgUrl,
                                        logoCount = logoCount,
                                        epgProgramCount = epgCount
                                    )
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("LanCurationServer", "Upload parse failed", e)
                                call.respond(
                                    io.ktor.http.HttpStatusCode.BadRequest,
                                    ErrorResponse(e.message ?: "Upload failed")
                                )
                            }
                        }
                        post("/api/upload-epg") {
                            try {
                                val epgStream = call.receiveStream()
                                val localEpgFile = java.io.File(this@LanCurationServer.context.filesDir, "iptv_epg.xml")
                                localEpgFile.outputStream().use { fileOut ->
                                    epgStream.copyTo(fileOut)
                                }

                                var programCount = 0
                                var channelCount = 0
                                if (localEpgFile.exists() && localEpgFile.length() > 0L) {
                                    val parseResult = localEpgFile.inputStream().use { com.nmaclean.tvheadend.iptv.epg.StreamingXmltvParser.parse(it) }
                                    programCount = parseResult.programmesByChannel.values.sumOf { it.size }
                                    channelCount = parseResult.programmesByChannel.size

                                    try {
                                        val allowlistRepo = AllowlistRepository(this@LanCurationServer.context.filesDir)
                                        val iptvComponent = android.content.ComponentName(this@LanCurationServer.context, com.nmaclean.tvheadend.iptv.tif.IptvTvInputService::class.java)
                                        val epgSyncer = com.nmaclean.tvheadend.iptv.epg.IptvEpgSyncer(this@LanCurationServer.context.contentResolver, iptvComponent, allowlistRepo)
                                        epgSyncer.syncPrograms(parseResult)
                                    } catch (ex: Exception) {
                                        android.util.Log.w("LanCurationServer", "EPG sync to TIF warning: ${ex.message}")
                                    }
                                }

                                call.respond(
                                    EpgUploadResponse(
                                        success = true,
                                        programCount = programCount,
                                        channelCount = channelCount
                                    )
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("LanCurationServer", "EPG upload parse failed", e)
                                call.respond(
                                    io.ktor.http.HttpStatusCode.BadRequest,
                                    ErrorResponse(e.message ?: "EPG upload failed")
                                )
                            }
                        }
                        get("/api/channels") {
                            val query = call.request.queryParameters["q"]?.lowercase() ?: ""
                            val group = call.request.queryParameters["group"]
                            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 1000

                            var filtered = currentParseResult.candidates
                            if (query.isNotEmpty()) {
                                filtered = filtered.filter { it.name.lowercase().contains(query) || it.streamUrl.lowercase().contains(query) }
                            }
                            if (!group.isNullOrEmpty()) {
                                filtered = filtered.filter { it.group == group }
                            }

                            val paged = filtered.drop(offset).take(limit)
                            call.respond(
                                ChannelsResponse(
                                    channels = paged,
                                    total = filtered.size,
                                    skippedCount = currentParseResult.skippedCount,
                                    groups = currentParseResult.groups
                                )
                            )
                        }
                        get("/api/allowlist") {
                            val snapshot = repository.load()
                            call.respond(snapshot)
                        }
                        post("/api/allowlist") {
                            try {
                                val req = call.receive<SaveAllowlistRequest>()
                                val snapshot = repository.save(req.channels, req.confirmEmpty)
                                onAllowlistUpdated?.invoke(snapshot.channels)
                                call.respond(snapshot)
                            } catch (e: IllegalArgumentException) {
                                call.respond(
                                    io.ktor.http.HttpStatusCode.BadRequest,
                                    ErrorResponse(e.message ?: "Invalid allowlist")
                                )
                            } catch (e: Exception) {
                                call.respond(
                                    io.ktor.http.HttpStatusCode.InternalServerError,
                                    ErrorResponse(e.message ?: "Save failed")
                                )
                            }
                        }
                        get("/api/epg-settings") {
                            val store = IptvEpgSettingsStore(this@LanCurationServer.context)
                            call.respond(
                                EpgSettingsDto(
                                    isEpgEnabled = store.isEpgEnabled,
                                    epgUrl = store.epgUrl,
                                    epgFallbackUrl = store.epgFallbackUrl,
                                    lookaheadHours = store.lookaheadHours,
                                    offsetMinutes = store.offsetMinutes,
                                    matchByName = store.matchByName,
                                    isLogosEnabled = store.isLogosEnabled,
                                    useEpgIcon = store.useEpgIcon
                                )
                            )
                        }
                        post("/api/epg-settings") {
                            try {
                                val req = call.receive<EpgSettingsDto>()
                                val store = IptvEpgSettingsStore(this@LanCurationServer.context)
                                store.isEpgEnabled = req.isEpgEnabled
                                store.epgUrl = req.epgUrl
                                store.epgFallbackUrl = req.epgFallbackUrl
                                store.lookaheadHours = req.lookaheadHours
                                store.offsetMinutes = req.offsetMinutes
                                store.matchByName = req.matchByName
                                store.isLogosEnabled = req.isLogosEnabled
                                store.useEpgIcon = req.useEpgIcon
                                call.respond(req)
                            } catch (e: Exception) {
                                call.respond(
                                    io.ktor.http.HttpStatusCode.BadRequest,
                                    ErrorResponse(e.message ?: "Failed to save EPG settings")
                                )
                            }
                        }
                        post("/api/scan") {
                            try {
                                val scanManager = SourceScanManager(this@LanCurationServer.context)
                                scanManager.startScan(ScanSource.IPTV)
                                val currentState = scanManager.scanState.value
                                val summary = when (currentState) {
                                    is ScanState.Completed -> currentState.summary
                                    is ScanState.Error -> currentState.message
                                    else -> "Scan completed successfully."
                                }
                                val success = currentState !is ScanState.Error
                                call.respond(ScanResponse(success = success, summary = summary))
                            } catch (e: Exception) {
                                call.respond(
                                    io.ktor.http.HttpStatusCode.InternalServerError,
                                    ErrorResponse(e.message ?: "Scan failed")
                                )
                            }
                        }
                    }
                }.start(wait = false)
                break
            } catch (e: Exception) {
                if (attempt == 0) {
                    currentTryPort = 0
                } else {
                    throw e
                }
            }
        }

        server = activeEngine
        actualPort = currentTryPort.takeIf { it > 0 } ?: port
    }

    fun stop() {
        try {
            server?.stop(1000L, 2000L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
        server = null
        currentParseResult = M3uParseResult(emptyList(), 0, emptyList())
    }

    private fun getLocalEpgProgramCount(): Int {
        return try {
            val localEpg = java.io.File(context.filesDir, "iptv_epg.xml")
            if (localEpg.exists() && localEpg.length() > 0L) {
                val epgRes = localEpg.inputStream().use { com.nmaclean.tvheadend.iptv.epg.StreamingXmltvParser.parse(it) }
                epgRes.programmesByChannel.values.sumOf { it.size }
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun loadIndexHtml(): String {
        return try {
            val inputStream: InputStream = context.assets.open("curation/index.html")
            inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h1>Curation UI asset not found</h1><p>${e.message}</p></body></html>"
        }
    }
}

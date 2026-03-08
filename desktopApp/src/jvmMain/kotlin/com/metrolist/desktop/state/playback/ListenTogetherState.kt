/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.state.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.listentogether.DesktopListenTogetherPlayback
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.listen_together_became_host
import com.metrolist.shared.generated.resources.listen_together_blocked_by_host
import com.metrolist.shared.generated.resources.listen_together_log_host_changed
import com.metrolist.shared.generated.resources.listen_together_log_join_rejected
import com.metrolist.shared.generated.resources.listen_together_log_joined_room
import com.metrolist.shared.generated.resources.listen_together_log_kicked_from_room
import com.metrolist.shared.generated.resources.listen_together_log_reconnected_to_room
import com.metrolist.shared.generated.resources.listen_together_log_room_created
import com.metrolist.shared.generated.resources.listen_together_log_server_error
import com.metrolist.shared.generated.resources.listen_together_log_suggestion_received
import com.metrolist.shared.generated.resources.listen_together_log_user_joined
import com.metrolist.shared.generated.resources.listen_together_log_user_left
import com.metrolist.shared.generated.resources.listen_together_log_waiting_for_buffers
import com.metrolist.shared.generated.resources.listen_together_queue_too_large
import com.metrolist.shared.generated.resources.listen_together_suggestion_approved
import com.metrolist.shared.generated.resources.listen_together_suggestion_rejected
import com.metrolist.shared.generated.resources.listen_together_suggestion_submitted
import com.metrolist.shared.generated.resources.listen_together_track_load_failed
import com.metrolist.shared.generated.resources.scrobble_connection_failed
import com.metrolist.shared.listentogether.ListenTogetherLogLevel
import com.metrolist.shared.listentogether.ListenTogetherLogger
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.listentogether.ListenTogetherPresentationText
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.listentogether.ListenTogetherText
import com.metrolist.shared.listentogether.ListenTogetherTrackResolver
import com.metrolist.shared.listentogether.network.isAllowedListenTogetherServerUrl
import com.metrolist.shared.listentogether.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.utils.XdgUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

object ListenTogetherState {
    private const val MAX_FRAME_SIZE_BYTES = 256L * 1024
    private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val presentationTextMap = mutableMapOf<ListenTogetherText, String>()

    init {
        migrateLegacySettings()
        AppState.scope.launch { preloadPresentationText() }
    }

    private suspend fun preloadPresentationText() {
        presentationTextMap[ListenTogetherText.CONNECTION_FAILED] = getString(Res.string.scrobble_connection_failed)
        presentationTextMap[ListenTogetherText.ROOM_CREATED] = getString(Res.string.listen_together_log_room_created)
        presentationTextMap[ListenTogetherText.JOINED_ROOM] = getString(Res.string.listen_together_log_joined_room)
        presentationTextMap[ListenTogetherText.JOIN_REJECTED] = getString(Res.string.listen_together_log_join_rejected)
        presentationTextMap[ListenTogetherText.KICKED] = getString(Res.string.listen_together_log_kicked_from_room)
        presentationTextMap[ListenTogetherText.SERVER_ERROR] = getString(Res.string.listen_together_log_server_error)
        presentationTextMap[ListenTogetherText.BLOCKED_BY_HOST] = getString(Res.string.listen_together_blocked_by_host)
        presentationTextMap[ListenTogetherText.BUFFER_WAIT] = getString(Res.string.listen_together_log_waiting_for_buffers)
        presentationTextMap[ListenTogetherText.HOST_CHANGED] = getString(Res.string.listen_together_log_host_changed)
        presentationTextMap[ListenTogetherText.USER_JOINED] = getString(Res.string.listen_together_log_user_joined)
        presentationTextMap[ListenTogetherText.USER_LEFT] = getString(Res.string.listen_together_log_user_left)
        presentationTextMap[ListenTogetherText.RECONNECTED] = getString(Res.string.listen_together_log_reconnected_to_room)
        presentationTextMap[ListenTogetherText.SUGGESTION_SUBMITTED] = getString(Res.string.listen_together_suggestion_submitted)
        presentationTextMap[ListenTogetherText.SUGGESTION_APPROVED] = getString(Res.string.listen_together_suggestion_approved)
        presentationTextMap[ListenTogetherText.SUGGESTION_REJECTED] = getString(Res.string.listen_together_suggestion_rejected)
        presentationTextMap[ListenTogetherText.QUEUE_TOO_LARGE] = getString(Res.string.listen_together_queue_too_large)
        presentationTextMap[ListenTogetherText.TRACK_LOAD_FAILED] = getString(Res.string.listen_together_track_load_failed)
        presentationTextMap[ListenTogetherText.BECAME_HOST] = getString(Res.string.listen_together_became_host)
        presentationTextMap[ListenTogetherText.SUGGESTION_RECEIVED] = getString(Res.string.listen_together_log_suggestion_received)
    }

    private val httpClient =
        HttpClient(CIO) {
            install(HttpFailureLog)
            install(WebSockets) {
                maxFrameSize = MAX_FRAME_SIZE_BYTES
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000L
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 60_000L
            }
        }

    val listenTogetherManager =
        ListenTogetherManager(
            callerScope = AppState.scope,
            httpClient = httpClient,
            playback = DesktopListenTogetherPlayback(),
            resolver =
                ListenTogetherTrackResolver { track ->
                    GlobalYouTubeRepository.instance.getSongMetadata(track.id) ?: track.toSongItem()
                },
            monotonicClockMs = { System.nanoTime() / 1_000_000L },
            wallClockMs = System::currentTimeMillis,
            timestampFormatter = { timestampFormatter.format(Instant.ofEpochMilli(it)) },
            presentationText = ListenTogetherPresentationText(::presentationText),
            logger =
                ListenTogetherLogger { level, message ->
                    when (level) {
                        ListenTogetherLogLevel.INFO -> {
                            AppLogger.info("ListenTogether", message)
                        }

                        ListenTogetherLogLevel.WARNING -> {
                            AppLogger.warning("ListenTogether", message)
                        }

                        ListenTogetherLogLevel.ERROR -> {
                            AppLogger.error("ListenTogether", message)
                        }

                        ListenTogetherLogLevel.DEBUG -> {
                            AppLogger.debug("ListenTogether", message)
                        }
                    }
                },
        )

    fun initialize() {
        DesktopDeviceConnect.manager.remote.transferBlocked = { listenTogetherManager.hasActiveOrPendingRoom }
    }

    fun suggestTrack(song: SongItem) {
        listenTogetherManager.suggestTrack(song)
    }

    @Composable
    fun isListenTogetherGuest(): Boolean {
        val isInRoom by listenTogetherManager.isInRoom.collectAsState()
        val role by listenTogetherManager.role.collectAsState()
        return isInRoom && role == ListenTogetherRoomRole.GUEST
    }

    fun close() {
        listenTogetherManager.close()
        httpClient.close()
    }

    private fun presentationText(key: ListenTogetherText): String = presentationTextMap[key].orEmpty()

    private fun migrateLegacySettings() {
        val legacyFile = File(XdgUtils.getXdgConfigHome(), "metrolist/listen_together.properties")
        val migratedFile = File(legacyFile.parentFile, "${legacyFile.name}.migrated")
        if (!legacyFile.isFile || migratedFile.exists()) return
        val properties = Properties().apply { legacyFile.inputStream().use { load(it) } }
        Settings.shared.listenTogetherUsername = properties.getProperty("username", Settings.shared.listenTogetherUsername)
        Settings.shared.listenTogetherAutoApproveJoinRequests =
            properties
                .getProperty("auto_approve_joins", Settings.shared.listenTogetherAutoApproveJoinRequests.toString())
                .toBoolean()
        Settings.shared.listenTogetherAutoApproveSongSuggestions =
            properties
                .getProperty("auto_approve_suggestions", Settings.shared.listenTogetherAutoApproveSongSuggestions.toString())
                .toBoolean()
        Settings.shared.listenTogetherSyncHostVolume =
            properties.getProperty("sync_volume", Settings.shared.listenTogetherSyncHostVolume.toString()).toBoolean()
        properties
            .getProperty("server_url")
            ?.takeIf(::isAllowedListenTogetherServerUrl)
            ?.let { Settings.shared.listenTogetherServerUrl = it }
        Settings.shared.listenTogetherCustomServers =
            properties.getProperty("custom_servers", Settings.shared.listenTogetherCustomServers)
        Settings.shared.listenTogetherBlockedUsers =
            properties
                .getProperty("blocked_users", Settings.shared.listenTogetherBlockedUsers)
                .split(',')
                .joinToString("\n") { it.trim() }
        if (!legacyFile.renameTo(migratedFile)) migratedFile.createNewFile()
    }
}

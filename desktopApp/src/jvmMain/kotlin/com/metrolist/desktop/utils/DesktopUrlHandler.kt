/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import com.metrolist.desktop.auth.DesktopDiscordLogin
import com.metrolist.desktop.auth.parseDiscordLoginCallback
import com.metrolist.desktop.state.app.DesktopNavigation
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.PodcastState
import com.metrolist.desktop.state.content.SearchState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.innertubex.utils.YouTubeUrlParser
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.extractListenTogetherRoomCode
import com.metrolist.shared.utils.isListenTogetherUrl
import com.metrolist.shared.utils.parseYouTubePodcastId
import com.metrolist.shared.utils.parseYouTubeUrl
import java.awt.Desktop
import java.awt.EventQueue
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object DesktopUrlHandler {
    @Volatile
    private var pendingUrl: String? = null

    @Volatile
    private var isUiReady: Boolean = false

    fun install() {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.APP_OPEN_URI)) {
                    desktop.setOpenURIHandler { event ->
                        event.uri?.let { uri ->
                            handleUrl(uri.toString())
                        }
                    }
                }
            }
        }
    }

    fun onUiReady() {
        isUiReady = true
        val pending = pendingUrl
        if (pending != null) {
            pendingUrl = null
            handleUrl(pending)
        }
    }

    fun canHandle(rawInput: String?): Boolean {
        if (rawInput.isNullOrBlank()) return false
        val normalized = normalizeUrl(rawInput) ?: return false
        if (parseDiscordLoginCallback(normalized) != null) return true
        if (isListenTogetherUrl(normalized)) return true
        return parseYouTubePodcastId(normalized) != null || parseYouTubeUrl(normalized) != null
    }

    fun handleUrl(rawInput: String?): Boolean {
        if (rawInput.isNullOrBlank()) return false
        val normalized = normalizeUrl(rawInput) ?: return false

        if (!canHandle(normalized)) return false

        if (!isUiReady) {
            pendingUrl = normalized
            return true
        }

        EventQueue.invokeLater {
            executeOpenUrl(normalized)
        }
        return true
    }

    fun normalizeUrl(raw: String): String? {
        val trimmed =
            raw
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.startsWith("metrolist://", ignoreCase = true)) {
            val afterScheme = trimmed.substring("metrolist://".length)
            if (afterScheme.startsWith("open?url=", ignoreCase = true)) {
                val encoded = afterScheme.substring("open?url=".length)
                val decoded = runCatching { URLDecoder.decode(encoded, StandardCharsets.UTF_8) }.getOrDefault(encoded)
                return normalizeUrl(decoded)
            }
            if (afterScheme.startsWith("http://", ignoreCase = true) || afterScheme.startsWith("https://", ignoreCase = true)) {
                return afterScheme
            }
            if (afterScheme.startsWith("music.youtube.com", ignoreCase = true) ||
                afterScheme.startsWith("youtube.com", ignoreCase = true) ||
                afterScheme.startsWith("www.youtube.com", ignoreCase = true) ||
                afterScheme.startsWith("youtu.be", ignoreCase = true)
            ) {
                return "https://$afterScheme"
            }
            return trimmed
        }

        return trimmed
    }

    private fun executeOpenUrl(url: String) {
        ComposeTrayManager.showWindow()
        if (parseDiscordLoginCallback(url) != null && DesktopDiscordLogin.flow.handleCallback(url)) return

        val roomCode = extractListenTogetherRoomCode(url)
        if (roomCode != null) {
            if (!Settings.shared.listenTogetherEnabled) return
            DesktopNavigation.openListenTogetherInvitation(roomCode) { ListenTogetherState.listenTogetherManager.joinRoom(it) }
            return
        }

        val podcastId = parseYouTubePodcastId(url)
        val parsed = parseYouTubeUrl(url)
        if (podcastId == null && parsed == null) return

        UiState.showSettings = false
        UiState.showSignIn = false
        UiState.showSwitchChannel = false
        PlaylistAlbumState.selectedLocalPlaylist = null
        SearchState.reset()

        if (podcastId != null) {
            PodcastState.fetch(podcastId)
            return
        }
        when (parsed) {
            is YouTubeUrlParser.ParsedUrl.Video -> {
                PlayerState.playFromId(parsed.id)
            }

            is YouTubeUrlParser.ParsedUrl.Playlist -> {
                PlaylistAlbumState.fetchPlaylistData(parsed.id)
            }

            is YouTubeUrlParser.ParsedUrl.Album -> {
                PlaylistAlbumState.fetchAlbumData(parsed.id)
            }

            is YouTubeUrlParser.ParsedUrl.Artist -> {
                ArtistState.fetchArtistData(parsed.id)
            }

            null -> {}
        }
    }
}

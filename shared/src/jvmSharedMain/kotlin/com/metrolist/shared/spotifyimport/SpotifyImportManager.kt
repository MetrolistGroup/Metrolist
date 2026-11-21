package com.metrolist.shared.spotifyimport

import com.metrolist.shared.network.hasHttpTransportFailureInCauseChain
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import meow.nyx.spotlib.PlaylistMeta
import meow.nyx.spotlib.PlaylistSource
import meow.nyx.spotlib.SpotifyAuthException
import meow.nyx.spotlib.SpotifyClient
import meow.nyx.spotlib.SpotifyException
import meow.nyx.spotlib.SpotifySession
import meow.nyx.spotlib.SpotifySessionExpiredException
import meow.nyx.spotlib.TrackItem
import java.util.concurrent.atomic.AtomicBoolean

class SpotifyImportManager(
    private val clientFactory: () -> SpotifyClient,
    private val loadStoredSession: () -> String? = { null },
    private val saveStoredSession: (String) -> Unit = {},
) : AutoCloseable {
    @Volatile
    private var client: SpotifyClient? = null

    @Volatile
    private var closed = false

    private var sessionGeneration = 0L

    val isAuthenticated: Boolean get() = client != null

    suspend fun authenticate(spDc: String) {
        require(spDc.isNotBlank()) { "Spotify login did not return a session cookie" }
        val generation =
            synchronized(this) {
                check(!closed) { "Spotify import is closed" }
                ++sessionGeneration
            }
        val candidate = clientFactory()
        try {
            candidate.loginWithSession(spDc)
            var installed = false
            val previous =
                synchronized(this) {
                    check(!closed) { "Spotify import is closed" }
                    if (generation != sessionGeneration) {
                        null
                    } else {
                        installed = true
                        client.also {
                            saveStoredSession(sessionJson.encodeToString(candidate.session))
                            client = candidate
                        }
                    }
                }
            if (!installed) {
                throw CancellationException("Spotify authentication was superseded")
            }
            previous?.close()
        } catch (error: CancellationException) {
            candidate.close()
            throw error
        } catch (error: Exception) {
            logFailure("Authentication failed", error)
            candidate.close()
            throw error
        }
    }

    suspend fun loadPlaylists(): List<PlaylistMeta> =
        withAuthenticatedClient("Loading playlists failed") { active ->
            playlistsForImport(active.listPlaylists()).also { persistSessionIfCurrent(active) }
        }

    suspend fun loadPlaylist(
        playlist: PlaylistMeta,
        onTrackProgress: ((loaded: Int, total: Int) -> Unit)? = null,
    ): LoadedPlaylist =
        withAuthenticatedClient("Loading playlist failed") { active ->
            val tracks =
                when (playlist.source) {
                    PlaylistSource.LIKED_SONGS -> {
                        active.likedSongs(MAX_IMPORT_TRACKS + 1, onProgress = onTrackProgress)
                    }

                    PlaylistSource.PLAYLIST -> {
                        active.playlistTracks(
                            playlist.uri,
                            MAX_IMPORT_TRACKS + 1,
                            onProgress = onTrackProgress,
                        )
                    }
                }
            persistSessionIfCurrent(active)
            val importTracks = tracksForImport(tracks.take(MAX_IMPORT_TRACKS))
            check(importTracks.isNotEmpty()) { "The Spotify playlist has no importable tracks" }
            LoadedPlaylist(
                uri = playlist.uri,
                name = playlist.name,
                image = playlist.image,
                tracks = importTracks,
                reachedTrackLimit = tracks.size > MAX_IMPORT_TRACKS,
            )
        }

    suspend fun loadPlaylistsForImport(
        playlists: List<PlaylistMeta>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onTrackProgress: ((playlistIndex: Int, totalPlaylists: Int, loaded: Int, total: Int) -> Unit)? = null,
        onError: ((playlist: PlaylistMeta, error: Exception) -> Unit)? = null,
    ): LoadedPlaylists {
        val selectedPlaylists = playlistsForImport(playlists)
        require(selectedPlaylists.isNotEmpty()) { "Choose at least one Spotify playlist" }
        val loadedPlaylists = mutableListOf<LoadedPlaylist>()
        var failedCount = 0
        val failures = mutableListOf<PlaylistFailure>()
        selectedPlaylists.forEachIndexed { index, playlist ->
            onProgress(index + 1, selectedPlaylists.size)
            try {
                loadedPlaylists +=
                    loadPlaylist(playlist) { loaded, total ->
                        onTrackProgress?.invoke(index + 1, selectedPlaylists.size, loaded, total)
                    }
            } catch (error: CancellationException) {
                throw error
            } catch (error: SpotifyAuthException) {
                throw error
            } catch (error: Exception) {
                failedCount++
                failures += PlaylistFailure(playlist.name, error.message ?: error::class.simpleName.orEmpty())
                onError?.invoke(playlist, error)
            }
            if (loadedPlaylists.sumOf { it.tracks.size } > PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
                throw ImportException(ImportErrorKey.TooManyTracks)
            }
        }
        check(loadedPlaylists.isNotEmpty()) { "The selected Spotify playlists have no importable tracks" }
        return LoadedPlaylists(loadedPlaylists, failedCount, failures)
    }

    suspend fun restorePersistedSession(): Boolean {
        val generation =
            synchronized(this) {
                check(!closed) { "Spotify import is closed" }
                if (client != null) return true
                sessionGeneration
            }
        val stored = loadStoredSession()?.takeIf(String::isNotBlank) ?: return false
        val session =
            runCatching { sessionJson.decodeFromString<SpotifySession>(stored) }
                .getOrElse {
                    clearStoredSessionIfCurrent(generation)
                    return false
                }
        if (!session.hasLoginCookie) {
            clearStoredSessionIfCurrent(generation)
            return false
        }
        val candidate = clientFactory()
        try {
            candidate.attachSession(session)
            candidate.ensureAuth()
            var installed = false
            val previous =
                synchronized(this) {
                    check(!closed) { "Spotify import is closed" }
                    if (generation != sessionGeneration || client != null) {
                        null
                    } else {
                        installed = true
                        client.also {
                            saveStoredSession(sessionJson.encodeToString(candidate.session))
                            client = candidate
                        }
                    }
                }
            if (!installed) {
                candidate.close()
                return false
            }
            previous?.close()
            return true
        } catch (error: CancellationException) {
            candidate.close()
            throw error
        } catch (error: SpotifySessionExpiredException) {
            candidate.close()
            clearStoredSessionIfCurrent(generation)
            return false
        } catch (error: Exception) {
            candidate.close()
            return false
        }
    }

    fun logout() {
        val previous =
            synchronized(this) {
                sessionGeneration++
                client.also {
                    client = null
                    saveStoredSession("")
                }
            }
        previous?.close()
    }

    override fun close() {
        val closingClient =
            synchronized(this) {
                closed = true
                sessionGeneration++
                client.also { client = null }
            }
        closingClient?.close()
    }

    private suspend fun authenticatedClient(): SpotifyClient =
        client ?: restorePersistedSession().let { client } ?: error("Sign in to Spotify first")

    private suspend fun <T> withAuthenticatedClient(
        failureMessage: String,
        operation: suspend (SpotifyClient) -> T,
    ): T {
        val active = authenticatedClient()
        return try {
            operation(active)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logFailure(failureMessage, error)
            invalidateExpiredSession(error, active)
            throw error
        }
    }

    private fun invalidateExpiredSession(
        error: Throwable,
        active: SpotifyClient,
    ) {
        if (error is SpotifySessionExpiredException) {
            val expiredClient =
                synchronized(this) {
                    client.takeIf { it === active }?.also {
                        sessionGeneration++
                        client = null
                        saveStoredSession("")
                    }
                }
            expiredClient?.close()
        }
    }

    private fun logFailure(
        message: String,
        error: Exception,
    ) {
        if (
            (error is SpotifyException && error.httpFailureLogged) ||
            error.hasHttpTransportFailureInCauseChain()
        ) {
            return
        }
        PlaybackEventLog.error(PlaybackEventKind.IMPORT, "SpotifyImport", message, error)
    }

    private fun persistSessionIfCurrent(active: SpotifyClient) {
        synchronized(this) {
            if (!closed && client === active) {
                saveStoredSession(sessionJson.encodeToString(active.session))
            }
        }
    }

    private fun clearStoredSessionIfCurrent(generation: Long) {
        synchronized(this) {
            if (!closed && generation == sessionGeneration && client == null) saveStoredSession("")
        }
    }

    data class LoadedPlaylist(
        val uri: String,
        val name: String,
        val image: String?,
        val tracks: List<ImportTrack>,
        val reachedTrackLimit: Boolean,
    )

    data class LoadedPlaylists(
        val playlists: List<LoadedPlaylist>,
        val failedCount: Int,
        val failures: List<PlaylistFailure> = emptyList(),
    )

    data class PlaylistFailure(
        val playlistName: String,
        val reason: String,
    )

    data class ImportTrack(
        val title: String,
        val artist: String,
        val album: String = "",
        val durationSeconds: Long? = null,
    )

    companion object {
        private val sessionJson = Json { ignoreUnknownKeys = true }
        private const val MAX_IMPORT_TRACKS = 100_000

        internal fun tracksForImport(tracks: List<TrackItem>): List<ImportTrack> =
            tracks
                .filter { track ->
                    track.type.equals("Track", ignoreCase = true) && !track.isLocal
                }.mapNotNull { track ->
                    track.name.trim().takeIf(String::isNotBlank)?.let { title ->
                        ImportTrack(title, track.artists.joinToString(", ").trim(), track.album.orEmpty(), track.durationMs?.div(1000))
                    }
                }

        internal fun playlistsForImport(playlists: List<PlaylistMeta>): List<PlaylistMeta> = playlists.distinctBy(PlaylistMeta::uri)

        internal fun sessionJsonForTest(session: SpotifySession): String = sessionJson.encodeToString(session)
    }
}

object SpotifyImportOperationGate {
    private val active = AtomicBoolean()

    fun tryStart(): Boolean = active.compareAndSet(false, true)

    fun finish() {
        active.set(false)
    }
}

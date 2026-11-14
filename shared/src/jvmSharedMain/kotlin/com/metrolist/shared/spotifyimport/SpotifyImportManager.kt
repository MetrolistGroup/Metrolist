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

    val isAuthenticated: Boolean get() = client != null

    suspend fun authenticate(spDc: String) {
        require(spDc.isNotBlank()) { "Spotify login did not return a session cookie" }
        check(!closed) { "Spotify import is closed" }
        val candidate = clientFactory()
        try {
            candidate.loginWithSession(spDc)
            val previous =
                synchronized(this) {
                    check(!closed) { "Spotify import is closed" }
                    client.also { client = candidate }
                }
            previous?.close()
            persistSession(candidate.session)
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
        try {
            val active = authenticatedClient()
            playlistsForImport(active.listPlaylists()).also { persistSession(active.session) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logFailure("Loading playlists failed", error)
            invalidateExpiredSession(error)
            throw error
        }

    suspend fun loadPlaylist(
        playlist: PlaylistMeta,
        onTrackProgress: ((loaded: Int, total: Int) -> Unit)? = null,
    ): LoadedPlaylist =
        try {
            val active = authenticatedClient()
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
            persistSession(active.session)
            val importTracks = tracksForImport(tracks.take(MAX_IMPORT_TRACKS))
            check(importTracks.isNotEmpty()) { "The Spotify playlist has no importable tracks" }
            LoadedPlaylist(
                uri = playlist.uri,
                name = playlist.name,
                image = playlist.image,
                tracks = importTracks,
                reachedTrackLimit = tracks.size > MAX_IMPORT_TRACKS,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logFailure("Loading playlist failed", error)
            invalidateExpiredSession(error)
            throw error
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
        check(!closed) { "Spotify import is closed" }
        if (client != null) return true
        val stored = loadStoredSession()?.takeIf(String::isNotBlank) ?: return false
        val session =
            runCatching { sessionJson.decodeFromString<SpotifySession>(stored) }
                .getOrElse {
                    saveStoredSession("")
                    return false
                }
        if (!session.hasLoginCookie) {
            saveStoredSession("")
            return false
        }
        val candidate = clientFactory()
        try {
            candidate.attachSession(session)
            candidate.ensureAuth()
            val previous =
                synchronized(this) {
                    check(!closed) { "Spotify import is closed" }
                    client.also { client = candidate }
                }
            previous?.close()
            persistSession(candidate.session)
            return true
        } catch (error: CancellationException) {
            candidate.close()
            throw error
        } catch (error: SpotifySessionExpiredException) {
            candidate.close()
            saveStoredSession("")
            return false
        } catch (error: Exception) {
            candidate.close()
            return false
        }
    }

    fun logout() {
        val previous = synchronized(this) { client.also { client = null } }
        previous?.close()
        saveStoredSession("")
    }

    override fun close() {
        val closingClient =
            synchronized(this) {
                closed = true
                client.also { client = null }
            }
        closingClient?.close()
    }

    private suspend fun authenticatedClient(): SpotifyClient =
        client ?: restorePersistedSession().let { client } ?: error("Sign in to Spotify first")

    private fun invalidateExpiredSession(error: Throwable) {
        if (error is SpotifySessionExpiredException) {
            val expiredClient = synchronized(this) { client.also { client = null } }
            expiredClient?.close()
            saveStoredSession("")
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

    private fun persistSession(session: SpotifySession) {
        saveStoredSession(sessionJson.encodeToString(session))
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

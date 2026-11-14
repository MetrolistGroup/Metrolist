package meow.nyx.spotlib

import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
enum class PlaylistSource { PLAYLIST, LIKED_SONGS }

@Serializable
data class PlaylistMeta(
    val uri: String,
    val name: String,
    val owner: String? = null,
    val description: String = "",
    val image: String? = null,
    val folderPath: List<String> = emptyList(),
    val source: PlaylistSource = PlaylistSource.PLAYLIST,
    val trackCount: Int = 0,
)

@Serializable
data class TrackItem(
    val uri: String,
    val name: String,
    val type: String = "Track",
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumUri: String? = null,
    val albumArtists: List<String> = emptyList(),
    val releaseDate: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val explicit: Boolean? = null,
    val isLocal: Boolean = false,
    val isPlayable: Boolean? = null,
    val addedAt: String? = null,
    val addedBy: String? = null,
    val position: Int = 0,
)

@Serializable
data class AccountProfile(
    val name: String? = null,
    val username: String? = null,
    val uri: String? = null,
    val avatar: String? = null,
)

@Serializable
data class ExportedPlaylist(
    val uri: String,
    val name: String,
    val owner: String? = null,
    val description: String = "",
    val image: String? = null,
    val folderPath: List<String> = emptyList(),
    val source: PlaylistSource = PlaylistSource.PLAYLIST,
    val tracks: List<TrackItem> = emptyList(),
)

@Serializable
data class LibraryExport(
    val generatedAt: String,
    val account: AccountProfile = AccountProfile(),
    val totalPlaylists: Int = 0,
    val totalTracks: Int = 0,
    val playlists: List<ExportedPlaylist> = emptyList(),
)

@Serializable
data class SpotifySession(
    val cookies: Map<String, String> = emptyMap(),
    val accessToken: String? = null,
    val accessTokenExpiresAtMs: Long? = null,
    val clientToken: String? = null,
    val clientTokenFetchedAtMs: Long? = null,
) {
    val hasLoginCookie: Boolean get() = cookies.containsKey("sp_dc")
    val accessTokenValid: Boolean
        get() =
            accessToken != null &&
                (accessTokenExpiresAtMs == null || Clock.System.now().toEpochMilliseconds() < accessTokenExpiresAtMs - 60_000)
    val clientTokenValid: Boolean
        get() =
            clientToken != null &&
                (clientTokenFetchedAtMs == null || Clock.System.now().toEpochMilliseconds() - clientTokenFetchedAtMs < 13L * 24 * 3600_000)
}

open class SpotifyException(
    message: String,
    cause: Throwable? = null,
    internal val httpFailureLogged: Boolean = false,
) : Exception(message, cause)

open class SpotifyAuthException(
    message: String,
    cause: Throwable? = null,
    httpFailureLogged: Boolean = false,
) : SpotifyException(message, cause, httpFailureLogged)

class SpotifySessionExpiredException(
    message: String,
    cause: Throwable? = null,
    httpFailureLogged: Boolean = false,
) : SpotifyAuthException(message, cause, httpFailureLogged)

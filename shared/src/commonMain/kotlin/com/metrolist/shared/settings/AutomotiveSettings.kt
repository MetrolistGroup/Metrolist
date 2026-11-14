package com.metrolist.shared.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val MAX_AUTOMOTIVE_PINNED_PLAYLISTS = 6

@Serializable
enum class AutomotivePlaylistSource {
    LOCAL,
    YOUTUBE,
    AUTO,
}

@Serializable
enum class AutomotiveAutoPlaylist(
    val id: String,
) {
    LIKED("liked"),
    DOWNLOADED("downloaded"),
    TOP("top100"),
    CACHED("cached"),
    UPLOADED("uploaded"),
    PODCASTS("podcasts"),
    ;

    companion object {
        fun fromId(id: String): AutomotiveAutoPlaylist? = entries.firstOrNull { it.id == id }
    }
}

@Serializable
data class AutomotivePinnedPlaylist(
    val source: AutomotivePlaylistSource,
    val id: String,
    val title: String,
    val thumbnail: String? = null,
)

enum class AutomotivePinnedPlaylistPlaybackMode {
    IN_ORDER,
    SHUFFLE,
}

private val automotiveSettingsJson = Json { ignoreUnknownKeys = true }

fun Settings.getAutomotivePinnedPlaylists(): List<AutomotivePinnedPlaylist> =
    runCatching {
        automotiveSettingsJson.decodeFromString<List<AutomotivePinnedPlaylist>>(androidAutoPinnedPlaylistsJson)
    }.getOrDefault(emptyList()).let(::normalizeAutomotivePinnedPlaylists)

fun normalizeAutomotivePinnedPlaylists(playlists: List<AutomotivePinnedPlaylist>): List<AutomotivePinnedPlaylist> =
    playlists
        .filter { it.id.isNotBlank() && it.title.isNotBlank() }
        .distinctBy { it.source to it.id }
        .take(MAX_AUTOMOTIVE_PINNED_PLAYLISTS)

fun Settings.setAutomotivePinnedPlaylists(playlists: List<AutomotivePinnedPlaylist>) {
    androidAutoPinnedPlaylistsJson =
        automotiveSettingsJson.encodeToString(
            normalizeAutomotivePinnedPlaylists(playlists),
        )
}

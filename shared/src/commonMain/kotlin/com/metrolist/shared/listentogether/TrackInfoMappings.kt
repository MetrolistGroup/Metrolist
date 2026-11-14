/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.listentogether

import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import io.ktor.http.URLBuilder
import io.ktor.http.takeFrom

fun TrackInfo.toSongItem(
    fallbackArtist: String? = null,
    omitNonPositiveDuration: Boolean = false,
): SongItem {
    val durationSeconds = duration / 1000L
    return SongItem(
        id = id,
        title = title,
        thumbnail = thumbnail?.takeIf(::isAllowedRemoteArtworkUrl),
        artists = listOf(ArtistTiny(id = null, name = artist.ifBlank { fallbackArtist ?: artist })),
        album = album?.let { AlbumTiny(id = null, name = it) },
        duration = durationSeconds.takeUnless { omitNonPositiveDuration && it <= 0L },
    )
}

internal fun isAllowedRemoteArtworkUrl(value: String): Boolean {
    val url = runCatching { URLBuilder().takeFrom(value) }.getOrNull() ?: return false
    if (url.protocol.name != "https" || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return false
    if (url.port != 0 && url.port != 443) return false
    val host = url.host.lowercase()
    return REMOTE_ARTWORK_HOSTS.any { host == it || host.endsWith(".$it") }
}

private val REMOTE_ARTWORK_HOSTS =
    setOf(
        "ytimg.com",
        "ggpht.com",
        "googleusercontent.com",
        "mzstatic.com",
        "scdn.co",
        "spotifycdn.com",
    )

internal fun TrackInfo.mergeInto(local: SongItem?): SongItem {
    val remote = toSongItem(omitNonPositiveDuration = true)
    if (local == null) return remote
    val artists =
        if (local.artists.joinToString(", ") { it.name } == artist) {
            local.artists
        } else {
            artist.split(", ").map { name -> local.artists.firstOrNull { it.name == name } ?: ArtistTiny(null, name) }
        }
    return local.copy(
        title = remote.title,
        artists = artists,
        album = if (local.album?.name == album) local.album else remote.album,
        thumbnail = remote.thumbnail,
        duration = remote.duration,
    )
}

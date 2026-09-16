/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.extensions

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.ArtistNameAliases

val MediaItem.metadata: MediaMetadata?
    get() = localConfiguration?.tag as? MediaMetadata

fun Song.toMediaItem() = toMediaMetadata().toMediaItem()

fun SongItem.toMediaItem() = toMediaMetadata().toMediaItem()

fun MediaMetadata.toMediaItem(): MediaItem {
    val resolvedMetadata = withResolvedArtistNameAliases()
    val artistNames = resolvedMetadata.artists.joinToString { it.name }
    return MediaItem.Builder()
        .setMediaId(resolvedMetadata.id)
        .setUri(resolvedMetadata.id)
        .setCustomCacheKey(resolvedMetadata.id)
        .setTag(resolvedMetadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(resolvedMetadata.title)
                .setSubtitle(artistNames)
                .setArtist(artistNames)
                .setArtworkUri(resolvedMetadata.thumbnailUrl?.toUri())
                .setAlbumTitle(resolvedMetadata.album?.title)
                .setAlbumArtist(resolvedMetadata.artists.firstOrNull()?.name)
                .setDisplayTitle(resolvedMetadata.title)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(Bundle().apply {
                    resolvedMetadata.thumbnailUrl?.let { putString("artwork_uri", it) }
                })
                .build(),
        ).build()
}

fun MediaMetadata.withResolvedArtistNameAliases(): MediaMetadata {
    val resolvedArtists =
        artists.map { artist ->
            artist.copy(name = ArtistNameAliases.resolve(artist.id, artist.name))
        }
    return if (resolvedArtists == artists) this else copy(artists = resolvedArtists)
}

fun MediaItem.withUpdatedMetadata(updatedMetadata: MediaMetadata): MediaItem {
    val resolvedMetadata = updatedMetadata.withResolvedArtistNameAliases()
    val artistNames = resolvedMetadata.artists.joinToString { it.name }
    return buildUpon()
        .setTag(resolvedMetadata)
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setTitle(resolvedMetadata.title)
                .setDisplayTitle(resolvedMetadata.title)
                .setSubtitle(artistNames)
                .setArtist(artistNames)
                .setArtworkUri(resolvedMetadata.thumbnailUrl?.toUri())
                .setAlbumTitle(resolvedMetadata.album?.title)
                .setAlbumArtist(resolvedMetadata.artists.firstOrNull()?.name)
                .setExtras(Bundle().apply {
                    resolvedMetadata.thumbnailUrl?.let { putString("artwork_uri", it) }
                })
                .build(),
        ).build()
}

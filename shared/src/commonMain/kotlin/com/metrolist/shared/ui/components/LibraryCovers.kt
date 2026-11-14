package com.metrolist.shared.ui.components

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.library_cover_cached
import com.metrolist.shared.generated.resources.library_cover_downloaded
import com.metrolist.shared.generated.resources.library_cover_episodes_for_later
import com.metrolist.shared.generated.resources.library_cover_liked
import com.metrolist.shared.generated.resources.library_cover_podcasts
import com.metrolist.shared.generated.resources.library_cover_top100
import com.metrolist.shared.generated.resources.library_cover_uploaded
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.settings.Settings
import org.jetbrains.compose.resources.DrawableResource

fun libraryCover(identifier: String?): DrawableResource? =
    when (identifier?.let { BuiltinPlaylist.fromId(it.removePrefix("local:"))?.icon } ?: identifier) {
        AUTO_PLAYLIST_ICON_LIKED -> Res.drawable.library_cover_liked
        AUTO_PLAYLIST_ICON_DOWNLOADED -> Res.drawable.library_cover_downloaded
        AUTO_PLAYLIST_ICON_TOP100 -> Res.drawable.library_cover_top100
        AUTO_PLAYLIST_ICON_CACHED -> Res.drawable.library_cover_cached
        AUTO_PLAYLIST_ICON_UPLOADED -> Res.drawable.library_cover_uploaded
        "auto_playlist_icon_podcasts" -> Res.drawable.library_cover_podcasts
        "auto_playlist_icon_episodes" -> Res.drawable.library_cover_episodes_for_later
        else -> null
    }

fun libraryCover(playlist: PlaylistItem): DrawableResource? {
    if (!playlist.customThumbnail.isNullOrBlank()) return null
    val builtin = BuiltinPlaylist.fromId(playlist.id.removePrefix("local:")) ?: return null
    if (builtin.collectionType?.let { Settings.shared.collectionCovers[it] } != null) return null
    return libraryCover(builtin.icon)
}

fun libraryArtwork(
    id: String,
    thumbnail: String? = null,
    customThumbnail: String? = null,
): String? {
    val builtin = BuiltinPlaylist.fromId(id.removePrefix("local:")) ?: return customThumbnail ?: thumbnail
    return customThumbnail ?: builtin.collectionType?.let { Settings.shared.collectionCovers[it] } ?: builtin.icon
}

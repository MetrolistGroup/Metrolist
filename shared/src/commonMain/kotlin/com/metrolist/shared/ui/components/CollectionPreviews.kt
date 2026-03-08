/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.components

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.YTItem

/** Access only from the UI thread. */
object CollectionPreviews {
    private const val CAPACITY = 32

    private data class Preview(
        val item: YTItem,
        val otherVersions: List<AlbumItem> = emptyList(),
    )

    private val items = LinkedHashMap<String, Preview>()

    fun record(item: YTItem) {
        if (item !is AlbumItem && item !is PlaylistItem && item !is ArtistItem) return
        val key = previewKey(item.id)
        val previous = items.remove(key)
        val album = item as? AlbumItem
        val knownAlbum = previous?.item as? AlbumItem
        val preview =
            if (album != null && knownAlbum != null) {
                album.copy(
                    artists = album.artists.ifEmpty { knownAlbum.artists },
                    year = album.year ?: knownAlbum.year,
                    type = album.type ?: knownAlbum.type,
                    songCount = album.songCount ?: knownAlbum.songCount,
                    duration = album.duration ?: knownAlbum.duration,
                )
            } else {
                item
            }
        items[key] = Preview(preview, previous?.otherVersions.orEmpty())
        if (items.size > CAPACITY) items.remove(items.keys.first())
    }

    fun record(page: AlbumPage) {
        record(page.album)
        items[previewKey(page.album.id)] = Preview(page.album, page.otherVersions.take(CAPACITY))
    }

    fun record(artist: ArtistTiny) {
        val id = artist.id?.takeIf(String::isNotBlank) ?: return
        val known = this.artist(id)
        record(
            (known ?: ArtistItem(id, artist.name, artist.thumbnail)).copy(
                title = artist.name.takeIf(String::isNotBlank) ?: known?.title.orEmpty(),
                thumbnail = artist.thumbnail ?: known?.thumbnail,
            ),
        )
    }

    fun artist(id: String): ArtistItem? = items[previewKey(id)]?.item as? ArtistItem

    fun albumPage(id: String): AlbumPage? {
        val preview = items[previewKey(id)] ?: return null
        val album = preview.item as? AlbumItem ?: return null
        return AlbumPage(album, emptyList(), otherVersions = preview.otherVersions)
    }

    fun album(id: String): AlbumItem? = items[previewKey(id)]?.item as? AlbumItem

    fun playlist(id: String): PlaylistItem? = items[previewKey(id)]?.item as? PlaylistItem

    private fun previewKey(id: String) = id.removePrefix("VL")
}

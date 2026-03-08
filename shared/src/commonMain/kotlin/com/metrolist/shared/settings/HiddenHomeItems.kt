package com.metrolist.shared.settings

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isPodcast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object HiddenHomeItems {
    private val store =
        HiddenLibraryItemStore(
            read = { Settings.shared.hiddenHomeItemsJson },
            write = { Settings.shared.hiddenHomeItemsJson = it },
        )
    private val _hidden = MutableSharedFlow<HiddenLibraryItem>(extraBufferCapacity = 1)
    val hidden = _hidden.asSharedFlow()

    fun all(): List<HiddenLibraryItem> = store.all()

    fun hide(item: YTItem) {
        require(item is ArtistItem || item is PodcastItem)
        val hiddenItem = HiddenLibraryItem.from(item)
        store.hide(hiddenItem)
        _hidden.tryEmit(hiddenItem)
    }

    fun restore(key: HiddenLibraryItemKey) {
        store.restore(key)
    }
}

class HomeContentFilter(
    items: List<HiddenLibraryItem>,
) {
    private val artists = items.filter { it.type == HiddenLibraryItemType.ARTIST }
    private val artistIds = artists.mapTo(mutableSetOf()) { it.id }
    private val artistNames = artists.mapTo(mutableSetOf()) { it.title.trim().lowercase() }
    private val podcastIds =
        items.filter { it.type == HiddenLibraryItemType.PODCAST }.mapTo(mutableSetOf()) { it.id.removePrefix("VL") }

    private fun hidesArtist(artist: ArtistTiny?): Boolean =
        artist != null &&
            if (artist.id.isNullOrBlank()) {
                artist.name.isNotBlank() && artist.name.trim().lowercase() in artistNames
            } else {
                artist.id in artistIds
            }

    private fun hidesPodcast(id: String?): Boolean = id != null && id.removePrefix("VL") in podcastIds

    fun hides(item: YTItem): Boolean =
        when (item) {
            is ArtistItem -> {
                item.id in artistIds || item.channelId in artistIds
            }

            is PodcastItem -> {
                hidesPodcast(item.id) || item.channelId in artistIds || hidesArtist(item.author?.let { ArtistTiny(item.channelId, it) })
            }

            is SongItem -> {
                hidesArtist((item.canonicalArtists?.takeIf { it.isNotEmpty() } ?: item.artists).firstOrNull()) ||
                    hidesPodcast(item.podcastId) ||
                    (item.isPodcast && (hidesPodcast(item.album?.id) || hidesPodcast(item.artists.firstOrNull()?.id)))
            }

            is AlbumItem -> {
                hidesArtist(item.artists.firstOrNull())
            }

            is EpisodeItem -> {
                hidesPodcast(item.podcastId) || hidesPodcast(item.author?.id) || hidesArtist(item.author)
            }

            else -> {
                false
            }
        }

    fun <T : YTItem> filterItems(items: List<T>): List<T> = items.filterNot(::hides)

    fun filterSections(sections: List<HomeSection>): List<HomeSection> =
        sections.mapNotNull { section ->
            if (section.browseId in artistIds || section.headerBrowseId in artistIds ||
                hidesPodcast(section.browseId) || hidesPodcast(section.headerBrowseId)
            ) {
                return@mapNotNull null
            }
            val items = filterItems(section.items)
            if (items.isEmpty() && section.continuation == null) null else section.copy(items = items)
        }
}

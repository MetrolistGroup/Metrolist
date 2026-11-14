package com.metrolist.shared.viewmodels

import com.metrolist.shared.db.daos.SpeedDialDao
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId

internal const val SPEED_DIAL_ITEM_LIMIT = SpeedDialDao.MAX_PINNED_ITEMS

fun shouldShowSpeedDialSurprise(
    mediaItemCount: Int,
    isPinned: Boolean,
): Boolean = isPinned || mediaItemCount > 0

internal fun speedDialRecommendations(
    quickPicks: List<SongItem>,
    otherShelves: List<List<YTItem>>,
    localFallback: List<SongItem>,
    excludedPlaylistIds: Set<String> = emptySet(),
): List<YTItem> {
    val balancedHomeItems =
        buildList {
            repeat(otherShelves.maxOfOrNull { it.size } ?: 0) { index ->
                otherShelves.forEach { shelf -> shelf.getOrNull(index)?.let(::add) }
            }
        }
    return (quickPicks + balancedHomeItems)
        .filterNot { it is PlaylistItem && (it.isEditable || canonicalHomeItemId(it) in excludedPlaylistIds) }
        .distinctBy(::canonicalHomeItemId)
        .ifEmpty { localFallback.distinctBy(::canonicalHomeItemId) }
}

internal fun <T> personalizedSection(
    apiItems: List<T>,
    localFallback: List<T>,
    isFiltered: Boolean,
): List<T> = apiItems.takeIf { it.isNotEmpty() } ?: localFallback.takeUnless { isFiltered }.orEmpty()

internal fun mergeSpeedDialItems(
    pinned: List<YTItem>,
    recommendations: List<YTItem>,
): List<YTItem> {
    val pins = enrichSpeedDialPins(pinned, recommendations.associateBy { it.id }).distinctBy(::canonicalHomeItemId)
    val mediaItemLimit = maxOf(SPEED_DIAL_ITEM_LIMIT - 1, pins.size)
    return (pins + recommendations)
        .distinctBy(::canonicalHomeItemId)
        .take(mediaItemLimit)
}

internal fun enrichSpeedDialPins(
    pinned: List<YTItem>,
    metadata: Map<String, YTItem>,
): List<YTItem> =
    pinned.map { pin ->
        val stored = metadata[pin.id]
        when {
            pin is SongItem && stored is SongItem -> {
                stored.withFallbackMetadata(pin)
            }

            pin is AlbumItem && stored is AlbumItem -> {
                stored.copy(
                    title = stored.title.ifBlank { pin.title },
                    thumbnail = stored.thumbnail.orFallback(pin.thumbnail),
                    artists = stored.artists.withFallbackMetadata(pin.artists),
                    year = stored.year.orFallback(pin.year),
                    playlistId = stored.playlistId.orFallback(pin.playlistId),
                    isExplicit = stored.isExplicit || pin.isExplicit,
                    type = stored.type.orFallback(pin.type),
                    isNew = stored.isNew || pin.isNew,
                )
            }

            pin is ArtistItem && stored is ArtistItem -> {
                stored.copy(
                    title = stored.title.ifBlank { pin.title },
                    thumbnail = stored.thumbnail.orFallback(pin.thumbnail),
                    subscribers = stored.subscribers.orFallback(pin.subscribers),
                    banner = stored.banner.orFallback(pin.banner),
                    description = stored.description.orFallback(pin.description),
                    monthlyListeners = stored.monthlyListeners.orFallback(pin.monthlyListeners),
                    viewCount = stored.viewCount.orFallback(pin.viewCount),
                    radioEndpointParams = stored.radioEndpointParams.orFallback(pin.radioEndpointParams),
                    isSubscribed = stored.isSubscribed || pin.isSubscribed,
                )
            }

            pin is PlaylistItem && stored is PlaylistItem -> {
                stored.copy(
                    title = stored.title.ifBlank { pin.title },
                    thumbnail = pin.thumbnail.orFallback(stored.thumbnail),
                    author = stored.author.orFallback(pin.author),
                    songCount = stored.songCount.orFallback(pin.songCount),
                    playEndpointParams = stored.playEndpointParams.orFallback(pin.playEndpointParams),
                    radioEndpointParams = stored.radioEndpointParams.orFallback(pin.radioEndpointParams),
                    isEditable = stored.isEditable || pin.isEditable,
                    description = stored.description.orFallback(pin.description),
                )
            }

            pin is PodcastItem && stored is PodcastItem -> {
                stored.copy(
                    title = stored.title.ifBlank { pin.title },
                    thumbnail = stored.thumbnail.orFallback(pin.thumbnail),
                    author = stored.author.orFallback(pin.author),
                    episodeCount = stored.episodeCount.orFallback(pin.episodeCount),
                    channelId = stored.channelId.orFallback(pin.channelId),
                )
            }

            else -> {
                pin
            }
        }
    }

private fun String?.orFallback(fallback: String?): String? = takeUnless { it.isNullOrBlank() } ?: fallback

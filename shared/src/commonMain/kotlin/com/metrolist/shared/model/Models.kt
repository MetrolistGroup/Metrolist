package com.metrolist.shared.model

import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongMetadataState
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
sealed class YTItem {
    abstract val id: String
    abstract val title: String
    abstract val thumbnail: String?
    open val libraryAddedAt: Long? get() = null
    open val libraryUpdatedAt: Long? get() = null
    open val isUnavailable: Boolean get() = false
}

@Serializable
data class SongItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val artists: List<ArtistTiny>,
    val album: AlbumTiny?,
    val duration: Long? = null,
    val isExplicit: Boolean = false,
    val endpointParams: String? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val musicVideoType: String? = null,
    val isUploaded: Boolean = isUploadedType(musicVideoType),
    val isLocal: Boolean = false,
    val uploadEntityId: String? = null,
    val playlistSetVideoId: String? = null,
    val playlistAddedOrder: Int? = null,
    val podcastId: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val totalTrackCount: Int? = null,
    val genre: String? = null,
    val queueSourceTitle: String? = null,
    val playCountText: String? = null,
    val isNew: Boolean = false,
    val customTitle: String? = null,
    val customArtist: String? = null,
    val canonicalTitle: String? = null,
    val canonicalArtists: List<ArtistTiny>? = null,
    val explicitness: Boolean? = null,
    override val libraryAddedAt: Long? = null,
    override val libraryUpdatedAt: Long? = null,
    val playlistLocalMappingId: Int? = null,
    val playbackPositionMs: Long? = null,
    val playbackProgressPercent: Int? = null,
    override val isUnavailable: Boolean = false,
) : YTItem()

val SongItem.artworkExplicitness: Boolean?
    get() = if (isExplicit) true else explicitness

fun SongItem.matchesSongQuery(term: String): Boolean {
    val displayed = SongMetadataState.apply(this)
    return displayed.title.contains(term, ignoreCase = true) ||
        displayed.artists.any { it.name.contains(term, ignoreCase = true) } ||
        displayed.canonicalTitle?.contains(term, ignoreCase = true) == true ||
        displayed.canonicalArtists?.any { it.name.contains(term, ignoreCase = true) } == true ||
        album?.name?.contains(term, ignoreCase = true) == true
}

@Serializable
data class AlbumItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val artists: List<ArtistTiny>,
    val year: String? = null,
    val playlistId: String? = null,
    val isExplicit: Boolean = false,
    val type: String? = null,
    val isNew: Boolean = false,
    val songCount: Int? = null,
    val duration: Long? = null,
    val playtime: Long? = null,
    override val libraryAddedAt: Long? = null,
    override val libraryUpdatedAt: Long? = null,
    val isDownloaded: Boolean = false,
) : YTItem()

fun albumTotalDuration(
    album: AlbumItem,
    songs: List<SongItem>,
): Long? =
    album.duration?.takeIf { it > 0 } ?: songs
        .takeIf { album.songCount != null && it.size == album.songCount && it.isNotEmpty() }
        ?.takeIf { it.all { song -> song.duration != null && song.duration > 0 } }
        ?.sumOf { requireNotNull(it.duration) }

@Serializable
data class ArtistItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val subscribers: String? = null,
    val banner: String? = null,
    val description: String? = null,
    val monthlyListeners: String? = null,
    val viewCount: String? = null,
    val radioEndpointParams: String? = null,
    val isSubscribed: Boolean = false,
    val songCount: Int? = null,
    val playtime: Long? = null,
    val isBlocked: Boolean = false,
    override val libraryAddedAt: Long? = null,
    override val libraryUpdatedAt: Long? = null,
    val channelId: String? = null,
) : YTItem()

fun ArtistItem.forArtistScreenMenu(): ArtistItem = copy(thumbnail = banner ?: thumbnail)

@Serializable
data class PlaylistItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val author: String? = null,
    val songCount: String? = null,
    val playEndpointParams: String? = null,
    val radioEndpointParams: String? = null,
    val isEditable: Boolean = false,
    val description: String? = null,
    val isLocal: Boolean = false,
    val artworkThumbnails: List<String> = emptyList(),
    val playtime: Long? = null,
    override val libraryAddedAt: Long? = null,
    override val libraryUpdatedAt: Long? = null,
    val isDownloaded: Boolean = false,
    val customThumbnail: String? = null,
    val authorId: String? = null,
    val authorThumbnail: String? = null,
    val viewCount: String? = null,
    val durationText: String? = null,
    val subtitle: String? = null,
) : YTItem()

fun PlaylistItem.withNavigationThumbnail(thumbnail: String?): PlaylistItem =
    if (isLocal || thumbnail.isNullOrBlank()) this else copy(thumbnail = thumbnail)

fun PlaylistItem.withLocalArtwork(songs: List<SongItem>): PlaylistItem =
    if (isLocal && customThumbnail.isNullOrBlank()) {
        copy(
            artworkThumbnails =
                songs
                    .asSequence()
                    .mapNotNull { it.thumbnail?.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(4)
                    .toList(),
        )
    } else {
        this
    }

@Serializable
data class PodcastItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val author: String? = null,
    val episodeCount: String? = null,
    val channelId: String? = null,
    override val libraryAddedAt: Long? = null,
    override val libraryUpdatedAt: Long? = null,
) : YTItem()

@Serializable
data class EpisodeItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val author: ArtistTiny? = null,
    val publishDateText: String? = null,
    val viewCountText: String? = null,
    val duration: Long? = null,
    val description: String? = null,
    val podcastId: String? = null,
    val saved: Boolean = false,
    val playlistSetVideoId: String? = null,
    override val libraryAddedAt: Long? = null,
    override val libraryUpdatedAt: Long? = null,
    val subtitle: String? = null,
    val durationText: String? = null,
    val playbackProgressPercent: Int? = null,
    val playbackPositionMs: Long? = null,
    override val isUnavailable: Boolean = false,
) : YTItem()

fun YTItem.isAvailableOffline(cachedSongIds: Set<String>): Boolean =
    when (this) {
        is SongItem -> isLocal || id in cachedSongIds
        is EpisodeItem -> id in cachedSongIds
        else -> true
    }

fun EpisodeItem.toSongItem(podcastTitle: String? = null): SongItem =
    SongItem(
        id = id,
        title = title,
        thumbnail = thumbnail,
        artists = listOfNotNull(author),
        album = podcastId?.let { AlbumTiny(it, podcastTitle ?: author?.name.orEmpty()) },
        duration = duration,
        musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
        playlistSetVideoId = playlistSetVideoId,
        podcastId = podcastId,
        libraryAddedAt = libraryAddedAt,
        libraryUpdatedAt = libraryUpdatedAt,
        playbackPositionMs = playbackPositionMs,
        playbackProgressPercent = playbackProgressPercent,
        isUnavailable = isUnavailable,
    )

@Serializable
data class BrowseItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String? = null,
    val params: String? = null,
    val subtitle: String? = null,
) : YTItem()

@Serializable
data class ArtistTiny(
    val id: String?,
    val name: String,
    val thumbnail: String? = null,
)

@Serializable
data class AlbumTiny(
    val id: String?,
    val name: String,
)

@Serializable
data class HomeSection(
    val title: String,
    val items: List<YTItem>,
    val subtitle: String? = null,
    val browseId: String? = null,
    val params: String? = null,
    val isListStyle: Boolean = false,
    val isCategoryComponent: Boolean = false,
    val isMultiRow: Boolean = false,
    val continuation: String? = null,
    val hasTitle: Boolean = true,
    val isPersonal: Boolean = false,
    val thumbnail: String? = null,
    val headerBrowseId: String? = null,
    val numItemsPerColumn: Int? = null,
    val isResponsiveList: Boolean = false,
)

@Serializable
data class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val otherVersions: List<AlbumItem> = emptyList(),
    val artistAlbums: List<AlbumItem> = emptyList(),
    val description: String? = null,
    @Transient val playlistPage: PlaylistPage? = null,
)

@Serializable
data class PodcastPage(
    val podcast: PodcastItem,
    val episodes: List<EpisodeItem>,
    val description: String? = null,
    val saved: Boolean = false,
    val continuations: List<String> = emptyList(),
    internal val consumedContinuations: Set<String> = emptySet(),
) {
    val continuation: String? get() = continuations.firstOrNull()
}

fun List<YTItem>.isVideoShelf(allowUntypedSongs: Boolean = false): Boolean =
    isNotEmpty() &&
        all {
            it is SongItem && !it.isUploaded &&
                (it.isVideoSong || (allowUntypedSongs && it.musicVideoType.isNullOrBlank()))
        }

data class ArtistPageResult(
    val sections: Map<String, List<YTItem>>,
    val sectionBrowseIds: Map<String, Pair<String?, String?>> = emptyMap(),
) {
    val librarySection: String?
        get() = sections.keys.firstOrNull { sectionBrowseIds[it]?.first?.startsWith("MPLA") == true }

    // These section hints include untyped items; isVideoShelf checks for mixed content before using a video-only layout.
    val videoSections: Set<String>
        get() =
            sections
                .filter { (title, items) ->
                    title != librarySection && title != topTracksSection && items.isNotEmpty() &&
                        items.all { it is SongItem } &&
                        (items.any { (it as SongItem).isVideoSong } || sectionBrowseIds[title]?.first?.startsWith("VLOL") == true)
                }.keys

    // Video shelves can use the same playlist browse-ID prefix as the primary track shelf.
    val topTracksSection: String?
        get() =
            sections.entries
                .firstOrNull { (title, items) ->
                    sectionBrowseIds[title]?.first?.startsWith("VLOL") == true &&
                        items.isNotEmpty() && items.all { it is SongItem && !it.isVideoSong }
                }?.key
}

@Serializable
data class Chip(
    val title: String,
    val params: String? = null,
    val browseId: String? = null,
)

fun String?.isPodcastBrowseId(): Boolean =
    this != null &&
        (startsWith("FEmusic_podcast") || startsWith("FEmusic_episode") || startsWith("MPSP"))

val Chip.isPodcastChip: Boolean
    get() = browseId.isPodcastBrowseId()

fun List<Chip>.filterPodcastChips(hidePodcasts: Boolean): List<Chip> = if (hidePodcasts) filterNot { it.isPodcastChip } else this

@Serializable
data class HomePageData(
    val chips: List<Chip> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    val continuation: String? = null,
)

@Serializable
data class BrowsePageData(
    val title: String? = null,
    val sections: List<HomeSection> = emptyList(),
    val continuation: String? = null,
)

@Serializable
data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
    val isTopResult: Boolean = false,
)

@Serializable
data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
    val onlineSearchFailed: Boolean = false,
)

@Serializable
enum class SearchFilter(
    val value: String,
    val label: String,
) {
    SONG("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D", "Songs"),
    VIDEO("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D", "Videos"),
    ALBUM("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D", "Albums"),
    ARTIST("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D", "Artists"),
    FEATURED_PLAYLIST("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D", "Playlists"),
    COMMUNITY_PLAYLIST("EgeKAQQoAEABagoQAxAEEAoQCRAF", "Community Playlists"),
    PODCAST("EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D", "Podcasts"),
    EPISODE("EgWKAQJIAWoKEAkQChAFEAMQBA%3D%3D", "Episodes"),
    PROFILE("EgWKAQJYAWoSEAUQCRADEAQQEBAVEAoQDhAR", "Profiles"),
}

fun List<SearchFilter>.filterAvailableSearchFilters(
    hideVideoSongs: Boolean,
    hidePodcasts: Boolean,
): List<SearchFilter> =
    filterNot {
        (hideVideoSongs && it == SearchFilter.VIDEO) ||
            (hidePodcasts && (it == SearchFilter.PODCAST || it == SearchFilter.EPISODE))
    }

fun List<YTItem>.filterEpisodeSearchResults(filter: SearchFilter): List<YTItem> =
    if (filter == SearchFilter.EPISODE) {
        filter { it is EpisodeItem || (it is SongItem && it.isPodcast) }
    } else {
        this
    }

@Serializable
data class SearchResultPage(
    val items: List<YTItem>,
    val continuation: String? = null,
    val onlineSearchFailed: Boolean = false,
)

@Serializable
data class SearchSuggestions(
    val queries: List<String>,
    val recommendedItems: List<YTItem>,
)

const val MUSIC_VIDEO_TYPE_OMV = "MUSIC_VIDEO_TYPE_OMV"
const val MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO = "MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO"
const val MUSIC_VIDEO_TYPE_VIDEO = "MUSIC_VIDEO_TYPE_VIDEO"
const val MUSIC_VIDEO_TYPE_LIVE = "MUSIC_VIDEO_TYPE_LIVE"

val SongItem.isVideoSong: Boolean
    get() = !isUploaded && isVideoType(musicVideoType)

val SongItem.isLive: Boolean
    get() = isLiveType(musicVideoType)

val SongItem.isOfficialMusicVideo: Boolean
    get() = isOfficialVideoType(musicVideoType)

val SongItem.isNonMusicalVideo: Boolean
    get() =
        isVideoSong &&
            musicVideoType != MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO &&
            !isOfficialMusicVideo

val SongItem.canSwitchToMusicVideo: Boolean
    get() =
        !isVideoSong &&
            !isUploaded &&
            !isUploadedType(musicVideoType) &&
            !isLocal &&
            !id.startsWith("local_") &&
            !isPodcast

val SongItem.canSwitchToAudioTrack: Boolean
    get() =
        isVideoSong &&
            !isUploaded &&
            !isUploadedType(musicVideoType) &&
            !isLocal &&
            !id.startsWith("local_") &&
            !isPodcast

val SongItem.previewThumbnailUrl: String?
    get() =
        if (isVideoSong && id.isNotBlank() && (thumbnail == null || thumbnail.contains("i.ytimg.com/vi/"))) {
            "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        } else {
            thumbnail
        }

val SongItem.previewAspectRatio: Float
    get() = 1f

val YTItem.thumbnailAspectRatio: Float
    get() = (this as? SongItem)?.previewAspectRatio ?: 1f

val YTItem.displayThumbnailUrl: String?
    get() = (this as? SongItem)?.previewThumbnailUrl ?: thumbnail

val YTItem.isPodcast: Boolean
    get() =
        when (this) {
            is PodcastItem, is EpisodeItem -> {
                true
            }

            is BrowseItem -> {
                id.isPodcastBrowseId()
            }

            is PlaylistItem -> {
                id.isPodcastBrowseId()
            }

            is SongItem -> {
                !podcastId.isNullOrBlank() ||
                    parseMusicVideoType(musicVideoType) == MusicVideoType.PODCAST_EPISODE
            }

            else -> {
                false
            }
        }

fun List<YTItem>.filterPodcasts(hidePodcasts: Boolean): List<YTItem> = if (hidePodcasts) filterNot { it.isPodcast } else this

enum class LyricsProvider {
    AUTO,
    USER,
    LYPRIO,
    BETTERLYRICS,
    LRCRED,
    UNISON,
    SIMPMUSIC,
    LRCLIB,
    KUGOU,
    PAXSENIX,
    YOUTUBESUBTITLE,
    YOUTUBE,
    ;

    companion object {
        val alwaysLastFallbacks: List<LyricsProvider> = listOf(YOUTUBESUBTITLE, YOUTUBE)

        val userConfigurable: List<LyricsProvider> =
            listOf(LYPRIO, BETTERLYRICS, LRCRED, UNISON, LRCLIB, KUGOU, PAXSENIX)

        private val legacyDefaultUserOrders =
            listOf(
                listOf("LYRICSPLUS", "BETTERLYRICS", "LRCLIB", "KUGOU"),
                listOf("BETTERLYRICS", "UNISON", "LRCLIB", "KUGOU", "PAXSENIX", "LYRICSPLUS", "ZEMER"),
                listOf("LYPRIO", "BETTERLYRICS", "UNISON", "LRCLIB", "KUGOU", "PAXSENIX", "LYRICSPLUS", "ZEMER"),
            )

        val afterUserLyricsFallbacks: List<LyricsProvider> = listOf(YOUTUBESUBTITLE, YOUTUBE)

        fun parseTokenFromSettings(token: String): LyricsProvider? {
            val t = token.trim()
            if (t.isEmpty()) return null
            entries.find { it.name == t }?.let { return it }
            return when (t) {
                "User" -> USER
                "Lyprio" -> LYPRIO
                "BetterLyrics" -> BETTERLYRICS
                "lrc.red" -> LRCRED
                "Unison" -> UNISON
                "LrcLib" -> LRCLIB
                "KuGou" -> KUGOU
                "Paxsenix" -> PAXSENIX
                "SimpMusic" -> SIMPMUSIC
                "YouTubeSubtitle" -> YOUTUBESUBTITLE
                "YouTube Music", "YouTube" -> YOUTUBE
                else -> null
            }
        }

        fun parseUserOrderFromStoredCsv(csv: String): List<LyricsProvider> {
            val parsed =
                csv.split(",").mapNotNull { parseTokenFromSettings(it) }.filter { it != AUTO }
            if (parsed.isEmpty() || isLegacyDefaultUserOrder(csv, parsed)) {
                return userConfigurable
            }
            val withoutFallbacks = parsed.filter { it !in alwaysLastFallbacks }
            val seen = mutableSetOf<LyricsProvider>()
            for (p in withoutFallbacks) {
                if (p in userConfigurable) seen.add(p)
            }
            val ordered = seen.toMutableList()
            for (p in userConfigurable) {
                if (p !in ordered) ordered.add(p)
            }
            return ordered
        }

        fun parseEnabledFromStoredCsv(csv: String): Set<LyricsProvider> {
            val trimmed = csv.trim()
            if (trimmed.equals("NONE", ignoreCase = true)) return emptySet()
            if (trimmed.isBlank()) return userConfigurable.toSet()
            val parsed =
                trimmed.split(",").mapNotNull { parseTokenFromSettings(it) }.filter { it in userConfigurable }
            if (isLegacyDefaultUserOrder(csv, parsed)) {
                return userConfigurable.toSet()
            }
            return parsed.toSet()
        }

        private fun isLegacyDefaultUserOrder(
            csv: String,
            parsed: List<LyricsProvider>,
        ): Boolean =
            csv.split(",").mapNotNull {
                when (val token = it.trim()) {
                    "LyricsPlus", "LYRICSPLUS" -> "LYRICSPLUS"
                    "Zemer", "ZEMER" -> "ZEMER"
                    else -> parseTokenFromSettings(token)?.takeIf { provider -> provider in parsed }?.name
                }
            } in legacyDefaultUserOrders

        fun preferredProvidersForFetch(
            userOrder: List<LyricsProvider>,
            enabled: Set<LyricsProvider>,
            preferVideoProviders: Boolean = false,
        ): List<LyricsProvider> {
            val userEnabled = userOrder.filter { it in enabled }
            if (userEnabled.isEmpty()) {
                return afterUserLyricsFallbacks
            }
            if (!preferVideoProviders) {
                return userEnabled + afterUserLyricsFallbacks
            }
            return (afterUserLyricsFallbacks + userEnabled).distinct()
        }
    }
}

enum class ArtistSource { YOUTUBE, ITUNES, LASTFM, CUSTOM }

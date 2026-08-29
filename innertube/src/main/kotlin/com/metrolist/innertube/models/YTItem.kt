package com.metrolist.innertube.models

import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV

sealed class YTItem {
    abstract val id: String
    abstract val title: String
    abstract val thumbnail: String?
    abstract val explicit: Boolean
    abstract val shareLink: String
}

data class Artist(
    val name: String,
    val id: String?,
)

data class Album(
    val name: String,
    val id: String,
)

data class SongItem(
    override val id: String,
    override val title: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val duration: Int? = null,
    val musicVideoType: String? = null,
    val chartPosition: Int? = null,
    val chartChange: String? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
    val endpoint: WatchEndpoint? = null,
    val setVideoId: String? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val historyRemoveToken: String? = null,
    val isEpisode: Boolean = false,
    val uploadEntityId: String? = null
) : YTItem() {
    val isVideoSong: Boolean
        get() = musicVideoType != null && musicVideoType != MUSIC_VIDEO_TYPE_ATV

    override val shareLink: String
        get() = "https://music.youtube.com/watch?v=$id"
}

data class AlbumItem(
    val browseId: String,
    val playlistId: String,
    override val id: String = browseId,
    override val title: String,
    val artists: List<Artist>?,
    val year: Int? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
) : YTItem() {
    override val shareLink: String
        get() = "https://music.youtube.com/playlist?list=$playlistId"
}

data class PlaylistItem(
    override val id: String,
    override val title: String,
    val author: Artist?,
    val songCountText: String?,
    override val thumbnail: String?,
    val playEndpoint: WatchEndpoint?,
    val shuffleEndpoint: WatchEndpoint?,
    val radioEndpoint: WatchEndpoint?,
    val isEditable: Boolean = false,
    val isPodcast: Boolean = false,
    val description: String? = null,
    val authorAvatarUrl: String? = null,
) : YTItem() {
    override val explicit: Boolean
        get() = false
    override val shareLink: String
        get() = "https://music.youtube.com/playlist?list=$id"
}

data class ArtistItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val channelId: String? = null,
    val playEndpoint: WatchEndpoint? = null,
    val shuffleEndpoint: WatchEndpoint?,
    val radioEndpoint: WatchEndpoint?,
    val isProfile: Boolean = false,
) : YTItem() {
    override val explicit: Boolean
        get() = false
    override val shareLink: String
        get() = "https://music.youtube.com/channel/$id"
}

data class PodcastItem(
    override val id: String,
    override val title: String,
    val author: Artist?,
    val episodeCountText: String?,
    override val thumbnail: String?,
    val playEndpoint: WatchEndpoint?,
    val shuffleEndpoint: WatchEndpoint?,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val channelId: String? = null,
) : YTItem() {
    override val explicit: Boolean
        get() = false
    override val shareLink: String
        get() = "https://music.youtube.com/playlist?list=$id"

    fun asPlaylistItem() = PlaylistItem(
        id = id,
        title = title,
        author = author,
        songCountText = episodeCountText,
        thumbnail = thumbnail,
        playEndpoint = playEndpoint,
        shuffleEndpoint = shuffleEndpoint,
        radioEndpoint = null,
        isEditable = false,
        isPodcast = true
    )
}

data class EpisodeItem(
    override val id: String,
    override val title: String,
    val author: Artist?,
    val podcast: Album? = null,
    val duration: Int? = null,
    val publishDateText: String? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
    val endpoint: WatchEndpoint? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val markAsPlayedToken: String? = null,
    val markAsUnplayedToken: String? = null,
) : YTItem() {
    override val shareLink: String
        get() = "https://music.youtube.com/watch?v=$id"

    fun asSongItem() = SongItem(
        id = id,
        title = title,
        artists = listOfNotNull(author),
        album = podcast,
        duration = duration,
        thumbnail = thumbnail,
        explicit = explicit,
        endpoint = endpoint,
        isEpisode = true,
        libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken,
    )
}

fun <T : YTItem> List<T>.filterExplicit(enabled: Boolean = true) =
    if (enabled) {
        filter { !it.explicit }
    } else {
        this
    }

fun <T : YTItem> List<T>.filterVideoSongs(disableVideos: Boolean = false) =
    if (disableVideos) {
        filterNot { it is SongItem && it.isVideoSong }
    } else {
        this
    }

fun <T : YTItem> List<T>.filterYoutubeShorts(enabled: Boolean = false) =
    if (enabled) {
        filterNot { it is PlaylistItem && it.id.startsWith("SS") }
    } else {
        this
    }

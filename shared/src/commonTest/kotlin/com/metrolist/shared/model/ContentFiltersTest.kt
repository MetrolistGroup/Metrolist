package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentFiltersTest {
    private val cleanSong = SongItem("clean", "Clean", null, artists = emptyList(), album = null)
    private val artistVideo =
        SongItem("atv", "Artist video", null, artists = emptyList(), album = null, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
    private val musicVideo = SongItem("omv", "Video", null, artists = emptyList(), album = null, musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
    private val podcastEpisode =
        SongItem("episode", "Episode", null, artists = emptyList(), album = null, musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
    private val podcastSong = SongItem("podcast-song", "Podcast", null, artists = emptyList(), album = null, podcastId = "podcast")
    private val podcast = PodcastItem("podcast", "Podcast", null)
    private val episode = EpisodeItem("episode-item", "Episode", null)
    private val explicitAlbum = AlbumItem("album", "Album", null, emptyList(), isExplicit = true)

    @Test
    fun isVideoSongRecognizesOfficialUgcAndPlainVideoTypes() {
        val ugcVideo = SongItem("ugc", "UGC", null, artists = emptyList(), album = null, musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        val userGeneratedVideo =
            SongItem("ugc2", "UGC2", null, artists = emptyList(), album = null, musicVideoType = "MUSIC_VIDEO_TYPE_USER_GENERATED")
        val plainVideo = SongItem("plain", "Plain", null, artists = emptyList(), album = null, musicVideoType = "MUSIC_VIDEO_TYPE_VIDEO")
        val rawVideo = SongItem("raw-video", "Raw Video", null, artists = emptyList(), album = null, musicVideoType = "VIDEO")
        val rawVideos = SongItem("raw-videos", "Raw Videos", null, artists = emptyList(), album = null, musicVideoType = "VIDEOS")
        val officialSourceVideo =
            SongItem(
                "osmv",
                "Official Source",
                null,
                artists = emptyList(),
                album = null,
                musicVideoType = "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC_VIDEO",
            )

        assertFalse(artistVideo.isVideoSong)
        assertTrue(musicVideo.isVideoSong)
        assertTrue(officialSourceVideo.isVideoSong)
        assertTrue(ugcVideo.isVideoSong)
        assertTrue(userGeneratedVideo.isVideoSong)
        assertTrue(plainVideo.isVideoSong)
        assertTrue(rawVideo.isVideoSong)
        assertTrue(rawVideos.isVideoSong)
        assertFalse(cleanSong.isVideoSong)
        assertFalse(podcastEpisode.isVideoSong)
    }

    @Test
    fun squareArtworkDoesNotChangeVideoShelfClassificationOrThumbnailSources() {
        val video = musicVideo.copy(thumbnail = "https://i.ytimg.com/vi/omv/hqdefault.jpg")
        val portrait = cleanSong.copy(thumbnail = "https://example.com/portrait.jpg")
        val items = listOf(video, portrait, artistVideo, podcastEpisode, podcast, episode, explicitAlbum)

        for (item in items) {
            assertEquals(1f, item.thumbnailAspectRatio, item.id)
            if (item is SongItem) assertEquals(1f, item.previewAspectRatio, item.id)
        }
        assertEquals("https://i.ytimg.com/vi/omv/mqdefault.jpg", video.displayThumbnailUrl)
        assertEquals(portrait.thumbnail, portrait.displayThumbnailUrl)
        assertTrue(listOf(video).isVideoShelf())
        assertFalse(items.isVideoShelf())
    }

    @Test
    fun musicVideoSwitchAllowsArtistTracksAndSongsButNotVideosOrPodcasts() {
        val lowerCaseArtistTrack =
            SongItem("lower-atv", "Lower ATV", null, artists = emptyList(), album = null, musicVideoType = "music_video_type_atv")
        val localSong =
            SongItem("local_123", "Local Track", null, artists = emptyList(), album = null, isLocal = true)

        assertTrue(artistVideo.canSwitchToMusicVideo)
        assertTrue(lowerCaseArtistTrack.canSwitchToMusicVideo)
        assertTrue(cleanSong.canSwitchToMusicVideo)
        assertFalse(musicVideo.canSwitchToMusicVideo)
        assertFalse(podcastEpisode.canSwitchToMusicVideo)
        assertFalse(localSong.canSwitchToMusicVideo)

        assertTrue(musicVideo.canSwitchToAudioTrack)
        assertFalse(artistVideo.canSwitchToAudioTrack)
        assertFalse(cleanSong.canSwitchToAudioTrack)
        assertFalse(podcastEpisode.canSwitchToAudioTrack)
        assertFalse(localSong.canSwitchToAudioTrack)
    }

    @Test
    fun podcastFilterRemovesEpisodesShowsAndPodcastBrowseCategories() {
        val podcastBrowse = BrowseItem("FEmusic_podcasts_category", "Localized category")
        val podcastPlaylist = PlaylistItem("MPSP123", "Localized show", null)
        val items: List<YTItem> =
            listOf(
                cleanSong,
                artistVideo,
                musicVideo,
                podcastSong,
                podcastEpisode,
                podcast,
                episode,
                podcastBrowse,
                podcastPlaylist,
                explicitAlbum,
            )

        assertEquals(listOf(cleanSong, artistVideo, musicVideo, explicitAlbum), items.filterPodcasts(hidePodcasts = true))
        assertEquals(items, items.filterPodcasts(hidePodcasts = false))
    }

    @Test
    fun hiddenContentRemovesMatchingSearchFilters() {
        val filters = SearchFilter.entries.toList()

        assertFalse(SearchFilter.VIDEO in filters.filterAvailableSearchFilters(hideVideoSongs = true, hidePodcasts = false))
        assertFalse(SearchFilter.PODCAST in filters.filterAvailableSearchFilters(hideVideoSongs = false, hidePodcasts = true))
        assertFalse(SearchFilter.EPISODE in filters.filterAvailableSearchFilters(hideVideoSongs = false, hidePodcasts = true))
        assertTrue(SearchFilter.SONG in filters.filterAvailableSearchFilters(hideVideoSongs = true, hidePodcasts = true))
    }

    @Test
    fun episodeSearchFilterDropsUnrelatedNestedRenderers() {
        val items: List<YTItem> = listOf(episode, podcastEpisode, podcast, explicitAlbum)

        assertEquals(listOf(episode, podcastEpisode), items.filterEpisodeSearchResults(SearchFilter.EPISODE))
        assertEquals(items, items.filterEpisodeSearchResults(SearchFilter.PODCAST))
    }

    @Test
    fun podcastChipFilterUsesBrowseIdentityInsteadOfLocalizedTitle() {
        val podcastChip = Chip("Localized podcast title", browseId = "FEmusic_podcasts")
        val episodeChip = Chip("Localized episode title", browseId = "FEmusic_episodes_latest")
        val moodChip = Chip("Localized mood title", browseId = "FEmusic_home", params = "mood")
        val chips = listOf(podcastChip, episodeChip, moodChip)

        assertEquals(listOf(moodChip), chips.filterPodcastChips(hidePodcasts = true))
        assertEquals(chips, chips.filterPodcastChips(hidePodcasts = false))
    }
}

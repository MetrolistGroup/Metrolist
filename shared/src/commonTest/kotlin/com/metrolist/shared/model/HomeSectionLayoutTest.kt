package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSectionLayoutTest {
    @Test
    fun collectionsAndArtistsUseGridCards() {
        val cards =
            listOf(
                PlaylistItem("mix", "Mix", null),
                AlbumItem("album", "Album", null, emptyList()),
                PodcastItem("show", "Show", null),
                ArtistItem("artist", "Artist", null),
            )
        cards.forEach { item -> assertEquals(HomeSectionLayout.GRID, listOf(item).homeSectionLayout()) }
        assertEquals(HomeSectionLayout.GRID, cards.homeSectionLayout())
    }

    @Test
    fun videoOnlyShelfUsesWideVideoCards() {
        val videos =
            listOf(song("one").copy(musicVideoType = MUSIC_VIDEO_TYPE_OMV), song("two").copy(musicVideoType = MUSIC_VIDEO_TYPE_VIDEO))
        assertEquals(HomeSectionLayout.VIDEOS, videos.homeSectionLayout())
    }

    @Test
    fun songsEpisodesMixedContentAndEmptyShelvesUseListRows() {
        val video = song("video").copy(musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        val audio = song("audio")
        val episode = EpisodeItem("episode", "Episode", null)
        val playlist = PlaylistItem("mix", "Mix", null)
        listOf(
            emptyList(),
            listOf(audio),
            listOf(episode),
            listOf(video, audio),
            listOf(video, playlist),
            listOf(playlist, episode),
            listOf(audio.copy(musicVideoType = MUSIC_VIDEO_TYPE_OMV, isUploaded = true)),
        ).forEach { items -> assertEquals(HomeSectionLayout.LIST, items.homeSectionLayout()) }
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}

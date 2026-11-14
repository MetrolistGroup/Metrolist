package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeShuffleTest {
    @Test
    fun usesOnlyAlreadyLoadedMusic() {
        val state =
            HomeViewModel.HomeUiState(
                quickPicks =
                    listOf(
                        song("quick"),
                        song("official-video", "MUSIC_VIDEO_TYPE_OMV"),
                        song("unspecified-video", MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO),
                    ),
                albumCharts = listOf(AlbumItem("album", "Album", null, emptyList())),
                speedDialItems = listOf(EpisodeItem("episode", "Episode", null)),
                sections =
                    listOf(
                        HomeSection(
                            title = "Section",
                            items =
                                listOf(
                                    PlaylistItem("playlist", "Playlist", null),
                                    song("regular-video", "MUSIC_VIDEO_TYPE_VIDEO"),
                                    song("section"),
                                ),
                        ),
                    ),
                data = listOf(HomeSection("Raw", listOf(song("quick")))),
            )

        assertEquals(
            listOf("quick", "official-video", "unspecified-video", "section"),
            state.loadedHomeSongs().map(SongItem::id),
        )
    }

    private fun song(
        id: String,
        musicVideoType: String? = null,
    ) = SongItem(id, id, null, emptyList(), null, musicVideoType = musicVideoType)
}

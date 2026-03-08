package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.withStoredDownloadMetadata
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.state.PodcastPlaybackProgress
import com.metrolist.shared.state.podcastResumePosition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadedSongMetadataTest {
    @Test
    fun downloadedEpisodeKeepsLocalArtworkAndPodcastCheckpoints() =
        runTest {
            val stored =
                Song(
                    SongEntity(
                        id = "episode",
                        title = "Episode",
                        duration = 7200,
                        albumId = "podcast",
                        thumbnailUrl = "https://example.com/remote.jpg",
                        isEpisode = true,
                        playbackPosition = 120_000L,
                    ),
                    artists = emptyList(),
                )
            val catalog = SongItem("episode", "Episode", "file:/downloads/episode.jpg", emptyList(), null)
            val song = catalog.withStoredDownloadMetadata(stored)
            assertTrue(song.isPodcast)
            assertEquals("podcast", song.podcastId)
            assertEquals(catalog.thumbnail, song.thumbnail)
            assertEquals(120_000L, podcastResumePosition(song, stored.song.playbackPosition))

            val positions = mutableListOf<Long>()
            val progress = PodcastPlaybackProgress(backgroundScope, { _, position, _, _ -> positions += position }, { throw it })
            progress.update(song, 180_000L, 7_200_000L, playing = false)
            progress.flush()?.join()
            assertEquals(listOf(180_000L), positions)
            assertSame(catalog, catalog.withStoredDownloadMetadata(null))
        }
}

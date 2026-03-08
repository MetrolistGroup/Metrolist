package com.metrolist.android.viewmodels

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.viewmodels.DownloadPlaylistFilterState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlaylistPresentationTest {
    private val songs =
        listOf(
            SongItem("z", "Zulu", null, emptyList(), null),
            SongItem("a", "Alpha", null, emptyList(), null),
            SongItem("a", "Alpha", null, emptyList(), null, playlistSetVideoId = "duplicate"),
        )
    private val input =
        AutoPlaylistPresentationInput(
            type = "liked",
            songs = songs,
            metadataRevision = 0L,
            collectionEntries = emptyList(),
            query = "",
            collectionSort = CollectionSort.NEWEST,
            collectionDirection = SortDirection.DESCENDING,
            playCounts = emptyMap(),
            playlistSort = PlaylistSongSort.TITLE,
            playlistDirection = SortDirection.ASCENDING,
            topDirection = SortDirection.DESCENDING,
            reorder = false,
        )

    @Test
    fun likedSortingAndSearchPreserveDuplicatesAndManualOrder() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            assertEquals(listOf(songs[1], songs[2], songs[0]), prepareAutoPlaylistSongs(input, dispatcher))
            assertEquals(songs.drop(1), prepareAutoPlaylistSongs(input.copy(query = " ALPHA "), dispatcher))
            assertSame(songs, prepareAutoPlaylistSongs(input.copy(reorder = true), dispatcher))
            assertSame(
                songs,
                prepareAutoPlaylistSongs(
                    input.copy(playlistSort = PlaylistSongSort.DATE_ADDED, playlistDirection = SortDirection.DESCENDING),
                    dispatcher,
                ),
            )
            assertEquals(
                songs.reversed(),
                prepareAutoPlaylistSongs(input.copy(type = "top100", topDirection = SortDirection.ASCENDING), dispatcher),
            )
            assertEquals(songs.take(1), prepareAutoPlaylistSongs(input.copy(type = "uploaded", query = "Zulu"), dispatcher))
        }

    @Test
    fun likedDateSortUsesRecencyWhileReorderingUsesPlaylistOrder() =
        runTest {
            val recent =
                input.copy(
                    playlistSort = PlaylistSongSort.DATE_ADDED,
                    playlistDirection = SortDirection.DESCENDING,
                    likedSongRecency = listOf("a", "z"),
                )
            val dispatcher = StandardTestDispatcher(testScheduler)
            assertEquals(listOf(songs[1], songs[2], songs[0]), prepareAutoPlaylistSongs(recent, dispatcher))
            assertEquals(songs, prepareAutoPlaylistSongs(recent.copy(reorder = true), dispatcher))
        }

    @Test
    fun storedCollectionsKeepMetadataSortingAndSearch() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val entries =
                songs.distinctBy { it.id }.mapIndexed { index, song ->
                    CachedAudioEntity(
                        songId = song.id,
                        title = song.title,
                        artist = "",
                        filePath = song.id,
                        fileSize = (index + 1L) * 100L,
                        cachedAt = index.toLong(),
                        lastAccessedAt = index.toLong(),
                        cacheLocation = "test",
                    )
                }
            for (type in listOf("cached", "downloaded")) {
                val stored = input.copy(type = type, collectionEntries = entries)
                assertEquals(listOf(songs[1], songs[2], songs[0]), prepareAutoPlaylistSongs(stored, dispatcher))
                assertEquals(songs.take(1), prepareAutoPlaylistSongs(stored.copy(query = " ZULU "), dispatcher))
                assertEquals(
                    songs,
                    prepareAutoPlaylistSongs(
                        stored.copy(collectionSort = CollectionSort.MOST_PLAYED, playCounts = mapOf("z" to 2)),
                        dispatcher,
                    ),
                )
            }
        }

    @Test
    fun downloadMembershipFiltersBeforeSortingAndSearchWithoutAffectingCache() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val filter = DownloadPlaylistFilterState(PlaylistEntity(id = "p", name = "Playlist"), songIds = setOf("a"))
            val downloaded =
                input.copy(
                    type = "downloaded",
                    downloadFilter = filter,
                    collectionSort = CollectionSort.TITLE,
                    collectionDirection = SortDirection.ASCENDING,
                )
            assertEquals(songs.drop(1), prepareAutoPlaylistSongs(downloaded, dispatcher))
            assertEquals(
                songs.take(1),
                prepareAutoPlaylistSongs(downloaded.copy(downloadFilter = filter.copy(exclude = true)), dispatcher),
            )
            assertTrue(prepareAutoPlaylistSongs(downloaded.copy(query = "Zulu"), dispatcher).isEmpty())
            for (failed in listOf(false, true)) {
                for (exclude in listOf(false, true)) {
                    assertEquals(
                        listOf(songs[1], songs[2], songs[0]),
                        prepareAutoPlaylistSongs(
                            downloaded.copy(downloadFilter = filter.copy(songIds = null, exclude = exclude, failed = failed)),
                            dispatcher,
                        ),
                    )
                }
            }
            assertEquals(listOf(songs[1], songs[2], songs[0]), prepareAutoPlaylistSongs(downloaded.copy(type = "cached"), dispatcher))
        }

    @Test
    fun cancellationDuringPreparationCannotPublishAnObsoleteResult() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var published = prepareAutoPlaylistSongs(input, dispatcher)
            val previous = published
            lateinit var preparation: Job
            val source =
                object : AbstractList<SongItem>() {
                    override val size = songs.size

                    override fun get(index: Int): SongItem {
                        if (index == 1) preparation.cancel()
                        return songs[index]
                    }
                }
            preparation = launch { published = prepareAutoPlaylistSongs(input.copy(songs = source, query = "Alpha"), dispatcher) }
            preparation.join()

            assertTrue(preparation.isCancelled)
            assertSame(previous, published)
            assertEquals(songs.take(1), prepareAutoPlaylistSongs(input.copy(query = "Zulu"), dispatcher))
        }
}

package com.metrolist.android.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.moveCachedSong
import com.metrolist.shared.model.reconcileCachedCustomOrder
import com.metrolist.shared.settings.SavedPlaylistCollectionSorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.DownloadPlaylistFilterState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun runningPreparationObservesListeningTimeSelectionAndLaterTotals() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            for (type in listOf("liked", "uploaded")) {
                val settings = Settings(MapSettings())
                var sort by mutableStateOf(PlaylistSongSort.TITLE)
                var times by mutableStateOf(mapOf("a" to 10L, "z" to 20L))
                val readInput = {
                    input.copy(type = type, playlistSort = sort).withSongSorting(
                        locked = settings.playlistEditLock,
                        listeningTimes = { times },
                    )
                }
                val observed = mutableListOf<AutoPlaylistPresentationInput>()
                var prepared = emptyList<SongItem>()
                val job =
                    launch {
                        snapshotFlow(readInput).collectLatest { current ->
                            observed += current
                            prepared = prepareAutoPlaylistSongs(current, dispatcher)
                        }
                    }
                try {
                    runCurrent()
                    assertTrue(observed.single().listeningTimes.isEmpty())
                    assertEquals(listOf(songs[1], songs[2], songs[0]), prepared)

                    sort = PlaylistSongSort.LISTENING_TIME
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    assertEquals(times, observed.last().listeningTimes)
                    assertEquals(listOf(songs[1], songs[2], songs[0]), prepared)

                    times = mapOf("a" to 30L, "z" to 5L)
                    Snapshot.sendApplyNotifications()
                    runCurrent()
                    assertEquals(times, observed.last().listeningTimes)
                    assertEquals(songs, prepared)
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    @Test
    fun runningPreparationObservesLockingAfterReopeningUnlockedLikedSongs() =
        runTest {
            val settings = Settings(MapSettings()).apply { playlistEditLock = false }
            var sort by mutableStateOf(PlaylistSongSort.ORIGINAL)
            val readInput = {
                input.copy(playlistSort = sort).withSongSorting(
                    locked = settings.playlistEditLock,
                    listeningTimes = { emptyMap() },
                )
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val observed = mutableListOf<AutoPlaylistPresentationInput>()
            var prepared = emptyList<SongItem>()
            val job =
                launch {
                    snapshotFlow(readInput).collectLatest { current ->
                        observed += current
                        prepared = prepareAutoPlaylistSongs(current, dispatcher)
                    }
                }
            try {
                runCurrent()
                assertTrue(observed.single().reorder)
                assertEquals(songs, prepared)

                settings.playlistEditLock = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(false, observed.last().reorder)

                sort = PlaylistSongSort.TITLE
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(false, observed.last().reorder)
                assertEquals(listOf(songs[1], songs[2], songs[0]), prepared)
            } finally {
                job.cancelAndJoin()
            }
        }

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

    @Test
    fun cachedManualSortAppliesCustomOrderAndPlacesNewSongsFirst() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bravo = SongItem("b", "Bravo", null, emptyList(), null)
            val cachedSongs = listOf(songs[0], bravo, songs[1])
            val cachedInput =
                input.copy(
                    type = "cached",
                    songs = cachedSongs,
                    collectionSort = CollectionSort.MANUAL,
                    collectionDirection = SortDirection.ASCENDING,
                    cachedCustomOrder = listOf("deleted", "a", "z"),
                )

            assertEquals(
                listOf(bravo, songs[1], songs[0]),
                prepareAutoPlaylistSongs(cachedInput, dispatcher),
            )
            assertFalse(cachedInput.withSongSorting(locked = true) { emptyMap() }.reorder)
            assertTrue(cachedInput.withSongSorting(locked = false) { emptyMap() }.reorder)
            assertFalse(
                cachedInput
                    .copy(collectionSort = CollectionSort.NEWEST)
                    .withSongSorting(locked = false) { emptyMap() }
                    .reorder,
            )
        }

    @Test
    fun cachedManualSortSearchFiltersWithoutLosingCustomOrderForPlayback() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bravo = SongItem("b", "Alpha Remix", null, emptyList(), null)
            val cachedSongs = listOf(songs[0], bravo, songs[1])
            val cachedInput =
                input.copy(
                    type = "cached",
                    songs = cachedSongs,
                    collectionSort = CollectionSort.MANUAL,
                    collectionDirection = SortDirection.DESCENDING,
                    cachedCustomOrder = listOf("b", "z", "a"),
                )

            assertEquals(
                listOf(bravo, songs[1]),
                prepareAutoPlaylistSongs(cachedInput.copy(query = " alpha "), dispatcher),
            )
            assertEquals(
                listOf(bravo, songs[0], songs[1]),
                prepareAutoPlaylistSongs(cachedInput.copy(query = ""), dispatcher),
            )
        }

    @Test
    fun switchingCachedSortToBuiltinPreservesMetadataSortingAndLeavesDownloadedUnchanged() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val bravo = SongItem("b", "Bravo", null, emptyList(), null)
            val cachedSongs = listOf(songs[0], bravo, songs[1])
            val timestamps = mapOf("z" to 30L, "b" to 10L, "a" to 20L)
            val entries =
                cachedSongs.mapIndexed { index, song ->
                    CachedAudioEntity(
                        songId = song.id,
                        title = song.title,
                        artist = "",
                        filePath = song.id,
                        fileSize = (index + 1L) * 100L,
                        cachedAt = timestamps.getValue(song.id),
                        lastAccessedAt = index.toLong(),
                        cacheLocation = "test",
                    )
                }
            val base =
                input.copy(
                    type = "cached",
                    songs = cachedSongs,
                    collectionEntries = entries,
                    cachedCustomOrder = listOf("b", "z", "a"),
                )

            assertEquals(
                listOf(songs[1], bravo, songs[0]),
                prepareAutoPlaylistSongs(
                    base.copy(collectionSort = CollectionSort.TITLE, collectionDirection = SortDirection.ASCENDING),
                    dispatcher,
                ),
            )
            assertEquals(
                listOf(songs[0], songs[1], bravo),
                prepareAutoPlaylistSongs(
                    base.copy(collectionSort = CollectionSort.NEWEST, collectionDirection = SortDirection.DESCENDING),
                    dispatcher,
                ),
            )
            assertEquals(
                listOf(bravo, songs[0], songs[1]),
                prepareAutoPlaylistSongs(
                    base.copy(collectionSort = CollectionSort.MOST_PLAYED, playCounts = mapOf("b" to 5, "z" to 2)),
                    dispatcher,
                ),
            )

            val downloadedInput =
                base.copy(
                    type = "downloaded",
                    collectionSort = CollectionSort.MANUAL,
                    collectionDirection = SortDirection.DESCENDING,
                )
            assertFalse(downloadedInput.withSongSorting(locked = false) { emptyMap() }.reorder)
            assertEquals(
                listOf(songs[0], songs[1], bravo),
                prepareAutoPlaylistSongs(downloadedInput, dispatcher),
            )
        }

    @Test
    fun moveCachedSongSupportsSingleAndMultiSelectionMoves() {
        val initial = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), moveCachedSong(initial, "a", "c"))
        assertEquals(initial, moveCachedSong(initial, "a", "c", selectedSongIds = setOf("a", "c")))
        assertEquals(listOf("c", "a", "b", "d"), moveCachedSong(initial, "a", "c", selectedSongIds = setOf("a", "b")))
        assertEquals(listOf("a", "d", "b", "c"), moveCachedSong(initial, "d", "b", selectedSongIds = setOf("a", "d")))
        assertEquals(initial, moveCachedSong(initial, "missing", "c"))

        assertEquals(
            listOf("e", "a", "c"),
            reconcileCachedCustomOrder(currentIds = listOf("c", "e", "a"), savedOrder = listOf("a", "b", "c", "d")),
        )

        val settings = Settings(MapSettings())
        settings.setCachedSongsOrder(listOf("b", "a", "c", "b"))
        settings.setPlaylistCollectionSorting(
            "special:cached",
            SavedPlaylistCollectionSorting(CollectionSort.MANUAL, SortDirection.ASCENDING),
        )
        assertEquals(listOf("b", "a", "c"), settings.cachedSongsOrder())
        assertEquals(
            SavedPlaylistCollectionSorting(CollectionSort.MANUAL, SortDirection.ASCENDING),
            settings.playlistCollectionSorting("special:cached", CollectionSort.NEWEST, SortDirection.DESCENDING),
        )
    }

    @Test
    fun prepareCachedCollectionSongsAppliesStoredSortingAndCustomOrder() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val entries =
                listOf(
                    CachedAudioEntity(
                        songId = "c",
                        title = "Charlie",
                        artist = "Artist C",
                        filePath = "c",
                        fileSize = 300L,
                        cachedAt = 30L,
                        lastAccessedAt = 30L,
                        cacheLocation = "internal",
                    ),
                    CachedAudioEntity(
                        songId = "a",
                        title = "Alpha",
                        artist = "Artist A",
                        filePath = "a",
                        fileSize = 100L,
                        cachedAt = 10L,
                        lastAccessedAt = 10L,
                        cacheLocation = "internal",
                    ),
                    CachedAudioEntity(
                        songId = "b",
                        title = "Bravo",
                        artist = "Artist B",
                        filePath = "b",
                        fileSize = 200L,
                        cachedAt = 20L,
                        lastAccessedAt = 20L,
                        cacheLocation = "internal",
                    ),
                )
            val storedSongs =
                mapOf(
                    "a" to Song(SongEntity(id = "a", title = "Alpha Override", duration = 123), emptyList()),
                )

            val manualOrdered =
                prepareCachedCollectionSongs(
                    entries = entries,
                    storedSongs = storedSongs,
                    playCounts = emptyMap(),
                    sorting = SavedPlaylistCollectionSorting(CollectionSort.MANUAL, SortDirection.ASCENDING),
                    customOrder = listOf("a", "b"),
                    dispatcher = dispatcher,
                )
            assertEquals(listOf("c", "a", "b"), manualOrdered.map { it.id })
            assertEquals("Alpha Override", manualOrdered[1].title)

            val mostPlayed =
                prepareCachedCollectionSongs(
                    entries = entries,
                    storedSongs = storedSongs,
                    playCounts = mapOf("b" to 10, "a" to 4),
                    sorting = SavedPlaylistCollectionSorting(CollectionSort.MOST_PLAYED, SortDirection.DESCENDING),
                    customOrder = listOf("a", "b"),
                    dispatcher = dispatcher,
                )
            assertEquals(listOf("b", "a", "c"), mostPlayed.map { it.id })

            assertTrue(
                prepareCachedCollectionSongs(
                    entries = emptyList(),
                    storedSongs = emptyMap(),
                    playCounts = emptyMap(),
                    sorting = SavedPlaylistCollectionSorting(CollectionSort.MANUAL, SortDirection.ASCENDING),
                    customOrder = listOf("a", "b"),
                    dispatcher = dispatcher,
                ).isEmpty(),
            )

            val availableOnly =
                prepareCachedCollectionSongs(
                    entries = entries.filter { it.songId != "a" },
                    storedSongs = storedSongs,
                    playCounts = emptyMap(),
                    sorting = SavedPlaylistCollectionSorting(CollectionSort.MANUAL, SortDirection.ASCENDING),
                    customOrder = listOf("a", "b", "c"),
                    dispatcher = dispatcher,
                )
            assertEquals(listOf("b", "c"), availableOnly.map { it.id })
        }
}

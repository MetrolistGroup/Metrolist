package com.metrolist.shared.db

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LIBRARY_SEARCH_PAGE_SIZE
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.metrolist.shared.viewmodels.contentMatchedSongs
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class LibraryContentSearchTest {
    @Test
    fun tracksLoadSavedSongsAndObserveLibraryMembershipChanges() =
        runTest {
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply { lastLibraryRefreshEpoch = Clock.System.now().toEpochMilliseconds() },
            )
            val path = Files.createTempFile("library-tracks", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            val job = Job()
            try {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val saved = SongEntity("saved", "Original", customTitle = "Renamed", inLibrary = now)
                database.withDatabaseTransaction {
                    val dao = database.dao()
                    dao.upsert(saved)
                    dao.upsert(SongEntity("history", "History only"))
                    dao.upsert(SongEntity("episode", "Podcast episode", isEpisode = true, inLibrary = now))
                }
                val viewModel = LibraryViewModel(CoroutineScope(coroutineContext + job), database)
                viewModel.setActive(true)
                val initial =
                    viewModel.uiState
                        .first { state ->
                            state.items.any { it is SongItem && it.id == "saved" }
                        }.items
                        .filterIsInstance<SongItem>()
                assertEquals(listOf("saved"), initial.map { it.id })
                assertEquals("Renamed", initial.single().title)
                assertEquals(now.toInstant(TimeZone.UTC).toEpochMilliseconds(), initial.single().libraryAddedAt)
                assertEquals(initial.single().libraryAddedAt, initial.single().libraryUpdatedAt)

                database.dao().upsert(SongEntity("added", "Newly saved", isLocal = true, inLibrary = now))
                viewModel.uiState.first { state -> state.items.filterIsInstance<SongItem>().size == 2 }
                database.dao().upsert(saved.copy(inLibrary = null))
                val remaining =
                    viewModel.uiState.first { state -> state.items.filterIsInstance<SongItem>().map { it.id } == listOf("added") }
                assertEquals(listOf("added"), remaining.items.filterIsInstance<SongItem>().map { it.id })
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun viewModelLoadsOnlyRequestedPagesAndRestartsSupersededSearches() =
        runTest {
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply { lastLibraryRefreshEpoch = Clock.System.now().toEpochMilliseconds() },
            )
            val path = Files.createTempFile("library-demand-pages", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            val job = Job()
            try {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                database.withDatabaseTransaction {
                    repeat(405) { index ->
                        val id = index.toString().padStart(3, '0')
                        database.dao().upsert(SongEntity(id, "Matching $id", inLibrary = now))
                    }
                }
                val viewModel = LibraryViewModel(CoroutineScope(coroutineContext + job), database)
                viewModel.setActive(true)
                viewModel.setSearchQuery("Matching")
                viewModel.contentMatchedSongs.first { it.size == 200 }
                delay(400)
                assertEquals(200, viewModel.contentMatchedSongs.value.size)
                viewModel.loadMoreContentMatches()
                viewModel.loadMoreContentMatches()
                viewModel.contentMatchedSongs.first { it.size == 400 }
                viewModel.loadMoreContentMatches()
                viewModel.contentMatchedSongs.first { it.size == 405 }
                assertEquals(false, viewModel.contentSearchHasMore.value)
                viewModel.setSearchDescending(true)
                val descending = viewModel.contentMatchedSongs.first { it.size == 200 }
                assertEquals("404", descending.first().id)
                viewModel.loadMoreContentMatches()
                viewModel.setSearchQuery("missing")
                viewModel.setSearchQuery("Matching")
                val restarted = viewModel.contentMatchedSongs.first { it.size == 200 }
                assertEquals(descending, restarted)
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun pagesUseTheDisplayedTitleInBothDirectionsWithoutDuplicatingArtistMatches() =
        runTest {
            val path = Files.createTempFile("library-sorted-pages", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                database.withDatabaseTransaction {
                    val dao = database.dao()
                    dao.upsert(ArtistEntity("artist-one", "Matching artist"))
                    dao.upsert(ArtistEntity("artist-two", "Matching artist"))
                    repeat(405) { index ->
                        val id = "song-${index.toString().padStart(3, '0')}"
                        dao.upsert(
                            SongEntity(
                                id = id,
                                title = "Original",
                                customTitle = "Title ${index.toString().padStart(3, '0')}",
                                inLibrary = now,
                            ),
                        )
                        dao.upsert(SongArtistMap(id, "artist-one", 0))
                        dao.upsert(SongArtistMap(id, "artist-two", 1))
                    }
                }
                for (descending in listOf(false, true)) {
                    val ids =
                        buildList {
                            while (true) {
                                val page = contentMatchedSongs(database, "Matching", offset = size, descending = descending)
                                if (page.isEmpty()) break
                                addAll(page.map { it.id })
                            }
                        }
                    assertEquals(405, ids.size)
                    assertEquals(if (descending) ids.sortedDescending() else ids.sorted(), ids)
                    assertEquals(405, ids.toSet().size)
                }
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun allMatchingSongsRemainAvailableBeyondFiveHundred() =
        runTest {
            val path = Files.createTempFile("library-many-matches", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                database.withDatabaseTransaction {
                    repeat(
                        1200,
                    ) { index -> database.dao().upsert(SongEntity(id = "song-$index", title = "Matching track", inLibrary = now)) }
                }
                val first = contentMatchedSongs(database, "Matching")
                assertEquals(LIBRARY_SEARCH_PAGE_SIZE, first.size)
                val songs =
                    buildList {
                        addAll(first)
                        while (true) {
                            val page = contentMatchedSongs(database, "Matching", offset = size)
                            if (page.isEmpty()) break
                            addAll(page)
                        }
                    }
                assertEquals(1200, songs.size)
                assertEquals(songs.map { it.id }.sorted(), songs.map { it.id })
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun contentSearchMatchesSongTitles() =
        runTest {
            val path = Files.createTempFile("metrolist-library-search", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val dao = database.dao()
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                dao.upsert(SongEntity(id = "s1", title = "Golden Hour", albumId = "al1", albumName = "Golden Album", inLibrary = now))
                dao.upsert(SongEntity(id = "s2", title = "Other Song", inLibrary = now))

                assertEquals(listOf("s1"), contentMatchedSongs(database, "golden").map { it.id })
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun contentSearchMatchesByArtistAndAlbumName() =
        runTest {
            val path = Files.createTempFile("metrolist-library-search-name", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val dao = database.dao()
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                dao.upsert(SongEntity(id = "s1", title = "Golden Hour", albumId = "al1", albumName = "Golden Album", inLibrary = now))
                dao.upsert(ArtistEntity(id = "ar1", name = "Artist X"))
                dao.upsert(SongArtistMap(songId = "s1", artistId = "ar1", position = 0))

                assertEquals(listOf("s1"), contentMatchedSongs(database, "artist").map { it.id })
                assertEquals(listOf("s1"), contentMatchedSongs(database, "album").map { it.id })
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun editedTitleAndArtistMatchBothDatabaseSearches() =
        runTest {
            val path = Files.createTempFile("metrolist-library-search-edited", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val dao = database.dao()
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                dao.upsert(
                    SongEntity(
                        id = "edited",
                        title = "Original title",
                        customTitle = "Updated title",
                        customArtist = "Updated artist",
                        inLibrary = now,
                    ),
                )
                dao.upsert(
                    SongEntity(id = "not-in-library", title = "Other", customTitle = "Updated title", customArtist = "Updated artist"),
                )

                for (query in listOf("Updated title", "Updated artist")) {
                    assertEquals(listOf("edited"), dao.searchSongs(query).first().map { it.id })
                    assertEquals(listOf("edited"), contentMatchedSongs(database, query).map { it.id })
                }
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun blankAndSingleCharacterQueriesReturnNoContentMatches() =
        runTest {
            val path = Files.createTempFile("metrolist-library-search-empty", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                assertEquals(emptyList(), contentMatchedSongs(database, "   "))
                assertEquals(emptyList(), contentMatchedSongs(database, "a"))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}

package com.metrolist.shared.db

import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.ui.screens.LibrarySortType
import com.metrolist.shared.ui.screens.sortLibraryItems
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryAlbumDateSortingTest {
    @Test
    fun albumsShownThroughSavedTracksUseTheirFirstTrackAddedDate() =
        withDatabase { database ->
            val earliest = LocalDateTime(2025, 1, 1, 0, 0)
            val bookmarked = LocalDateTime(2025, 1, 15, 0, 0)
            val latest = LocalDateTime(2025, 3, 1, 0, 0)
            database.dao().upsert(AlbumEntity("older", title = "Older", songCount = 1, duration = 0))
            database.dao().upsert(AlbumEntity("bookmarked", title = "Bookmarked", songCount = 1, duration = 0, bookmarkedAt = bookmarked))
            database.dao().upsert(AlbumEntity("newer", title = "Newer", songCount = 1, duration = 0))
            database.dao().upsert(SongEntity("older-track", "Older track", albumId = "older", inLibrary = earliest))
            database.dao().upsert(SongEntity("bookmarked-track", "Bookmarked track", albumId = "bookmarked", inLibrary = latest))
            database.dao().upsert(SongEntity("newer-track", "Newer track", albumId = "newer", inLibrary = latest))

            val albums = database.dao().albumsLikedByCreateDateAsc(includeSaved = true).first()
            val items =
                albums.map { album ->
                    AlbumItem(
                        id = album.id,
                        title = album.title,
                        thumbnail = null,
                        artists = emptyList(),
                        libraryAddedAt = album.libraryAddedAt?.toInstant(TimeZone.UTC)?.toEpochMilliseconds(),
                    )
                }

            assertEquals(earliest, albums.single { it.id == "older" }.libraryAddedAt)
            assertEquals(bookmarked, albums.single { it.id == "bookmarked" }.libraryAddedAt)
            assertEquals(
                listOf("newer", "bookmarked", "older"),
                sortLibraryItems(items, LibrarySortType.RECENTLY_ADDED, "", SortDirection.DESCENDING).map { it.id },
            )
        }

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) =
        runBlocking {
            val path = Files.createTempFile("library-album-date", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                block(database)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}

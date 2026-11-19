package com.metrolist.shared.db

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class LibrarySongOrderingTest {
    @Test
    fun externalDownloadDatesUpdateLibrarySongsWithoutChangingMembership() =
        runBlocking {
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply { lastLibraryRefreshEpoch = Clock.System.now().toEpochMilliseconds() },
            )
            val path = Files.createTempFile("library-download-order", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val job = Job(coroutineContext[Job])
            try {
                val date = LocalDateTime(2026, 11, 16, 12, 0)
                database.dao().upsert(SongEntity("saved", "Song", inLibrary = date, isDownloaded = true, dateDownload = date))
                val dates = MutableStateFlow(emptyMap<String, Long>())
                val model = LibraryViewModel(CoroutineScope(coroutineContext + job), database, downloadedSongDates = dates)
                model.setActive(true)

                suspend fun awaitDate(expected: Long?) {
                    withTimeout(5_000) {
                        model.uiState.first { state ->
                            val song = state.items.filterIsInstance<SongItem>().singleOrNull()
                            song != null && song.downloadedAt == expected
                        }
                    }
                }
                awaitDate(null)
                dates.value = mapOf("saved" to 20L)
                awaitDate(20L)
                dates.value = emptyMap()
                awaitDate(null)
                assertEquals(
                    listOf("saved"),
                    model.uiState.value.items
                        .filterIsInstance<SongItem>()
                        .map { it.id },
                )
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun nameQueriesAndPagesUseTheDisplayedTitle() =
        runBlocking {
            val directory = Files.createTempDirectory("library-song-ordering")
            val database = getDesktopDatabaseBuilder(directory.resolve("music.db").toFile()).build()
            try {
                val dao = database.dao()
                val date = LocalDateTime(2026, 1, 1, 0, 0)
                dao.upsert(ArtistEntity("artist", "Artist"))
                val songs =
                    listOf(
                        SongEntity("renamed", "Zebra", customTitle = "Alpha"),
                        SongEntity("canonical", "Beta"),
                        SongEntity("last", "Alpha", customTitle = "Zulu"),
                    )
                songs.forEachIndexed { index, song ->
                    dao.upsert(song.copy(inLibrary = date, liked = true, isDownloaded = true, isUploaded = true))
                    dao.upsert(SongArtistMap(song.id, "artist", index))
                }
                val expected = songs.map { it.id }
                for (query in listOf(
                    dao.songsByNameAsc(),
                    dao.likedSongsByNameAsc(),
                    dao.artistSongsByNameAsc("artist"),
                    dao.downloadedSongsByNameAsc(),
                    dao.uploadedSongsByNameAsc(),
                )) {
                    assertEquals(expected, query.first().map { it.id })
                }
                assertEquals(expected.take(2), dao.songsByNameAsc(limit = 2).first().map { it.id })
                assertEquals(expected.drop(2), dao.songsByNameAsc(limit = 2, offset = 2).first().map { it.id })
                assertEquals(emptyList(), dao.songsByNameAsc(limit = 2, offset = 4).first())
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
            }
        }
}

package com.metrolist.shared.db

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LibrarySongOrderingTest {
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

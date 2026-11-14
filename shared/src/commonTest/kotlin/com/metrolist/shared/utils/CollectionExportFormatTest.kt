package com.metrolist.shared.utils

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CollectionExportFormatTest {
    private val song =
        SongItem(
            id = "track-id",
            title = "A \"quoted\", title\nwith a newline",
            thumbnail = null,
            artists = listOf(ArtistTiny("a", "First"), ArtistTiny("b", "Second")),
            album = AlbumTiny("album", "An, album"),
            duration = 123,
        )

    @Test
    fun csvPreservesEscapingOrderAndDuplicateOccurrences() {
        val row =
            "\"A \"\"quoted\"\", title\nwith a newline\",\"First, Second\",\"An, album\"," +
                "\"https://music.youtube.com/watch?v=track-id\"\n"
        assertEquals("Title,Artist,Album,URL\n$row$row", CollectionExportFormat.CSV.render(listOf(song, song)))
    }

    @Test
    fun missingMetadataAndEmptyCollectionsRemainValid() {
        assertEquals("Title,Artist,Album,URL\n", CollectionExportFormat.CSV.render(emptyList()))
        assertEquals("#EXTM3U\n", CollectionExportFormat.M3U.render(emptyList()))
        assertEquals(
            "Title,Artist,Album,URL\n\"Title\",\"\",\"\",\"https://music.youtube.com/watch?v=track-id\"\n",
            CollectionExportFormat.CSV.render(listOf(song.copy(title = "Title", artists = emptyList(), album = null))),
        )
    }

    @Test
    fun m3uWritesDurationsArtistsAndUrlsInOrder() {
        assertEquals(
            "#EXTM3U\n#EXTINF:123,First, Second - Title\nhttps://music.youtube.com/watch?v=track-id\n" +
                "#EXTINF:-1,First, Second - Next\nhttps://music.youtube.com/watch?v=next-id\n",
            CollectionExportFormat.M3U.render(
                listOf(song.copy(title = "Title"), song.copy(id = "next-id", title = "Next", duration = null)),
            ),
        )
    }

    @Test
    fun mixedExportsPreserveLocalUrisAndRemoteUrls() {
        val local = song.copy(id = "file:///music/song.flac", isLocal = true)
        for (format in CollectionExportFormat.entries) {
            val output = format.render(listOf(local, song, local))
            assertEquals(2, Regex("file:///music/song.flac").findAll(output).count())
            assertEquals(1, Regex("https://music.youtube.com/watch", RegexOption.LITERAL).findAll(output).count())
            assertFalse("watch?v=file:" in output)
        }
    }
}

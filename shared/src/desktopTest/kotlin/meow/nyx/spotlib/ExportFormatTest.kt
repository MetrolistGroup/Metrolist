package meow.nyx.spotlib

import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatTest {
    @Test
    fun `csv escapes playlist and track metadata`() {
        val export =
            LibraryExport(
                generatedAt = "2026-08-05T00:00:00Z",
                playlists =
                    listOf(
                        ExportedPlaylist(
                            uri = "spotify:playlist:one",
                            name = "Road, trip",
                            tracks =
                                listOf(
                                    TrackItem(
                                        uri = "spotify:track:one",
                                        name = "A \"quoted\" song",
                                        artists = listOf("Artist"),
                                        position = 1,
                                    ),
                                ),
                        ),
                    ),
            )

        val csv = ExportFormat.toCsv(export)

        assertTrue(csv.contains("\"Road, trip\""))
        assertTrue(csv.contains("\"A \"\"quoted\"\" song\""))
    }
}

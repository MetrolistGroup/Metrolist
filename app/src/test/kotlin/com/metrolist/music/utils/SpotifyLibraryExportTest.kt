package com.metrolist.music.utils

import org.junit.Assert.*
import org.junit.Test

class SpotifyLibraryExportTest {
    @Test fun readsSavedTracksAndSkipsEpisodesMissingNamesAndDuplicates() {
        val result = SpotifyLibraryExportParser.parse("""{"tracks":[
            {"track":"Song","artist":"Artist","uri":"spotify:track:1"},
            {"track":"Song","artist":"Artist","uri":"spotify:track:1"},
            {"track":"Podcast","artist":"Host","uri":"spotify:episode:2"},
            {"track":"","artist":"Artist"}]}""")
        assertEquals(1, result.tracks.size)
        assertEquals(3, result.skipped)
        assertNull(result.tracks.single().explicit)
    }
    @Test fun readsCsvQuotesCommasBomAndMetadata() {
        val result = SpotifyLibraryExportParser.parse("\uFEFFTrack URI,Track Name,Artist Name(s),Duration (ms),Explicit\r\n" +
            "spotify:track:1,\"Hello, \"\"World\"\"\",Artist;Guest,200000,True\r\n")
        val song = result.tracks.single()
        assertEquals("Hello, \"World\"", song.title)
        assertEquals(listOf("Artist", "Guest"), song.artists)
        assertEquals(200, song.duration)
        assertEquals(true, song.explicit)
    }
    @Test fun readsSemicolonSeparatedCsvAndMultilineFields() {
        val result = SpotifyLibraryExportParser.parse("Title;Artist\n\"A\nSong\";Artist\n")
        assertEquals("A\nSong", result.tracks.single().title)
    }
    @Test fun deduplicatesRowsWithoutSpotifyIds() {
        val result = SpotifyLibraryExportParser.parse("title,artist\nSong,Artist\nsong,artist\n,Artist\n")
        assertEquals(1, result.tracks.size)
        assertEquals(2, result.skipped)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsStreamingHistoryJson() {
        SpotifyLibraryExportParser.parse("""{"streamingHistory":[{"trackName":"Song"}]}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCsvWithoutArtists() {
        SpotifyLibraryExportParser.parse("title,url\nSong,https://example.com\n")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnclosedQuotes() {
        SpotifyLibraryExportParser.parse("title,artist\n\"Song,Artist")
    }
}

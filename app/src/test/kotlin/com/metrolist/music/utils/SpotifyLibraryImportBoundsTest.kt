package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotifyLibraryImportBoundsTest {
    @Test(expected = IllegalArgumentException::class) fun rejectsDeepJsonBeforeParsing() {
        SpotifyLibraryExportParser.parse("""{"tracks":[],"other":""" + "[".repeat(33) + "0" + "]".repeat(33) + "}")
    }
    @Test fun ignoresJsonBracketsInsideSongNames() {
        val songs = SpotifyLibraryExportParser.parse("""{"tracks":[{"track":"[[[[Song]]]]","artist":"Artist"}]}""")
        assertEquals("[[[[Song]]]]", songs.tracks.single().title)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsLargeCsvFields() {
        SpotifyLibraryExportParser.parse("title,artist\n" + "x".repeat(10001) + ",Artist")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsManyCsvRows() {
        SpotifyLibraryExportParser.parse("title,artist\n" + "Song,Artist\n".repeat(50001))
    }

    @Test fun skipsOversizedMetadataWithoutLosingValidSongs() {
        val oversized = "x".repeat(1001)
        val result = SpotifyLibraryExportParser.parse("""{"tracks":[{"track":"$oversized","artist":"Artist"},{"track":"Valid song","artist":"Artist"}]}""")
        assertEquals("Valid song", result.tracks.single().title)
        assertEquals(1, result.skipped)
    }
}

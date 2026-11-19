package com.metrolist.shared.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistEmptyJsonTest {
    @Test
    fun emptyJsonArraysDoNotBecomeSearchTracks() {
        for (text in listOf("[]", "{\"tracks\":[]}", "{\"songs\":[]}", "{\"items\":[]}", "{\"data\":[]}")) {
            assertTrue(UniversalPlaylistParser.parse(text).isEmpty(), text)
            assertTrue(UniversalPlaylistParser.prepareFile(text, "playlist.json", "Playlist", "File").tracks.isEmpty(), text)
        }
    }

    @Test
    fun jsonEntriesWithoutRecognizableTracksDoNotBecomeSearchTracks() {
        for (text in listOf("[null]", "[{}]", "{\"tracks\":[{\"title\":null}]}")) {
            assertTrue(UniversalPlaylistParser.parse(text).isEmpty(), text)
        }
    }

    @Test
    fun bracketedPlainTitlesAreNotConsumedAsLenientJsonArrays() {
        for (title in listOf("[Intro]", "[Live]")) {
            assertEquals(title, UniversalPlaylistParser.parse(title).single().title)
        }
    }

    @Test
    fun recognizedLenientJsonTracksRemainSupported() {
        assertEquals("Song", UniversalPlaylistParser.parse("[{title:Song,artist:Artist}]").single().title)
    }

    @Test
    fun bracketNumberedPlainTextStillParses() {
        val tracks = UniversalPlaylistParser.parse("[1] Artist - Song\n[2] Other - Title")
        assertEquals(listOf("Song", "Title"), tracks.map { it.title })
        assertEquals(listOf("Artist", "Other"), tracks.map { it.artist })
    }
}

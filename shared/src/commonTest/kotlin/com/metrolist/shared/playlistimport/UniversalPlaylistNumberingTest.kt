package com.metrolist.shared.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals

class UniversalPlaylistNumberingTest {
    @Test
    fun punctuationJoinedToDigitsIsPartOfTheTextFileTitle() {
        val tracks =
            UniversalPlaylistParser
                .prepareFile(
                    "4:44 by JAY-Z\n1.000 Dances by Example Artist",
                    "playlist.txt",
                    "Playlist",
                    "File",
                ).tracks
        val titles = listOf("4:44", "1.000 Dances")
        assertEquals(titles, tracks.map { it.title })
        assertEquals(titles, tracks.map { it.customTitle })
        assertEquals(listOf("JAY-Z", "Example Artist"), tracks.map { it.artist })
    }

    @Test
    fun explicitNumberingStillAllowsNumericTitles() {
        val tracks =
            UniversalPlaylistParser
                .prepareFile(
                    "1. 4:44 by JAY-Z\n[2] 1.000 Dances by Example Artist",
                    "playlist.txt",
                    "Playlist",
                    "File",
                ).tracks
        assertEquals(listOf("4:44", "1.000 Dances"), tracks.map { it.title })
    }

    @Test
    fun ordinaryAndCompactListMarkersKeepTheirExistingMeaning() {
        val tracks =
            UniversalPlaylistParser
                .prepareFile(
                    "1. Queen - Bohemian Rhapsody\n2 Eagles - Hotel California\n3)Respect by Aretha Franklin\n4.Angel by Massive Attack\n5.",
                    "playlist.txt",
                    "Playlist",
                    "File",
                ).tracks
        assertEquals(listOf("Bohemian Rhapsody", "Hotel California", "Respect", "Angel"), tracks.map { it.title })
    }
}

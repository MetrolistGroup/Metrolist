package com.metrolist.shared.playlistimport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistJsonNullTest {
    @Test
    fun nullFieldsDoNotHideFallbackMetadataAndVideoReference() {
        val track =
            UniversalPlaylistParser
                .parse(
                    """[{"title":null,"name":"Song","artist":null,"artistName":"Artist","videoId":null,"url":"https://youtu.be/abcdefghijk"}]""",
                ).single()
        assertEquals("Song", track.title)
        assertEquals("Song", track.customTitle)
        assertEquals("Artist", track.artist)
        assertEquals("abcdefghijk", track.videoId)
    }

    @Test
    fun nullVideoIdDoesNotHideValidUrl() {
        val track =
            UniversalPlaylistParser
                .parse(
                    """[{"title":"Song","videoId":null,"url":"https://youtu.be/abcdefghijk"}]""",
                ).single()
        assertEquals("abcdefghijk", track.videoId)
    }

    @Test
    fun nullTitleDoesNotBecomeCustomTitle() {
        val track =
            UniversalPlaylistParser
                .parse(
                    """[{"title":null,"videoId":"abcdefghijk"}]""",
                ).single()
        assertEquals("", track.title)
        assertNull(track.customTitle)
    }

    @Test
    fun nullArtistsAreOmittedFromArrays() {
        val track =
            UniversalPlaylistParser
                .parse(
                    """[{"title":"Song","artists":[null,{"name":null},{"name":"Artist"},"Guest"]}]""",
                ).single()
        assertEquals("Artist, Guest", track.artist)
    }

    @Test
    fun literalNullTextIsPreserved() {
        val track =
            UniversalPlaylistParser
                .parse(
                    """[{"title":"null","artist":"null","videoId":"abcdefghijk"}]""",
                ).single()
        assertEquals("null", track.title)
        assertEquals("null", track.artist)
    }
}

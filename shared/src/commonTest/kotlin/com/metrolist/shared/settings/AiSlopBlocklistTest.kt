package com.metrolist.shared.settings

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSlopBlocklistTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        AiSlopBlocklist.replaceForTest(emptySet())
    }

    @Test
    fun parsesOnlySupportedChannelEntries() {
        val parsed =
            AiSlopBlocklist.parse(
                """
                ! comment
                @AiMusic
                UCabcdefghijklmnopqrstuv
                malformed entry
                """.trimIndent(),
            )

        assertTrue("@aimusic" in parsed)
        assertTrue("ucabcdefghijklmnopqrstuv" in parsed)
        assertFalse("malformed entry" in parsed)
    }

    @Test
    fun matchesSongArtistByChannelIdOrExactHandle() {
        Settings.shared.blockAiSlopArtists = true
        AiSlopBlocklist.replaceForTest(setOf("uclisted", "@listedhandle"))

        assertTrue(AiSlopBlocklist.matches(song("UCListed", "Artist")))
        assertTrue(AiSlopBlocklist.matches(song(null, "ListedHandle")))
        assertFalse(AiSlopBlocklist.matches(song(null, "Listed Handle")))
        Settings.shared.blockAiSlopArtists = false
    }

    private fun song(
        artistId: String?,
        artistName: String,
    ) = SongItem(
        id = "song",
        title = "Song",
        thumbnail = null,
        artists = listOf(ArtistTiny(artistId, artistName)),
        album = null,
    )
}

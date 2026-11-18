package com.metrolist.shared.settings

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun acceptsDownloadedListLargerThanOneMegabyte() {
        val handles = (0 until 70_000).map { "@aislophandle$it" }
        val body = (listOf("! Title: AISList - Blocklist") + handles).joinToString("\n")
        assertTrue(body.length > 1_048_576)

        AiSlopBlocklist.checkDeclaredSize(body.length.toLong())
        val parsed = AiSlopBlocklist.parseDownloaded(body)

        assertEquals(handles.size, parsed.size)
        assertTrue("@aislophandle69999" in parsed)
    }

    @Test
    fun rejectsDownloadedListOverTheSizeLimit() {
        val limit = AiSlopBlocklist.MAX_RESPONSE_CHARACTERS

        assertFailsWith<IllegalStateException> { AiSlopBlocklist.checkDeclaredSize(limit + 1L) }
        assertFailsWith<IllegalStateException> { AiSlopBlocklist.parseDownloaded("@" + "a".repeat(limit)) }
    }

    @Test
    fun rejectsDownloadedListWithoutEntries() {
        assertFailsWith<IllegalStateException> {
            AiSlopBlocklist.parseDownloaded("! comment only\nmalformed entry")
        }
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

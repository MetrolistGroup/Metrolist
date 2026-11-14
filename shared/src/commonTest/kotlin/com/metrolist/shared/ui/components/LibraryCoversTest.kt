package com.metrolist.shared.ui.components

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibraryCoversTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun collectionTypesIdsAndThumbnailIdentifiersResolveTheSameCover() {
        BuiltinPlaylist.entries.forEach { builtin ->
            val expected = libraryCover(builtin.icon)
            if (builtin == BuiltinPlaylist.HISTORY) assertNull(expected) else assertNotNull(expected)
            assertEquals(expected, libraryCover(builtin.id))
            builtin.collectionType?.let { assertEquals(expected, libraryCover(it)) }
        }
        listOf("LM", "VLLM", "LM:account", PlaylistEntity.LIKED_PLAYLIST_ID, "local:liked").forEach {
            assertEquals(libraryCover("liked"), libraryCover(it))
        }
        assertEquals(libraryCover("downloaded"), libraryCover(PlaylistEntity.DOWNLOADED_PLAYLIST_ID))
        listOf("RDPN", "VLRDPN").forEach { assertEquals(libraryCover("podcasts"), libraryCover(it)) }
        assertEquals(libraryCover("SE"), libraryCover("VLSE"))
        assertEquals(libraryCover("top100"), libraryCover(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
        assertEquals(libraryCover("top100"), libraryCover(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID))
        assertNull(libraryCover(null))
        assertNull(libraryCover("PLordinary"))
    }

    @Test
    fun customCoversOverrideBuiltinsAndOrdinaryPlaylistsKeepTheirArtwork() {
        val liked = PlaylistItem("VLLM", "Liked", "https://example.com/remote.jpg")
        assertEquals(BuiltinPlaylist.LIKED.icon, libraryArtwork(liked.id, liked.thumbnail))
        assertNotNull(libraryCover(liked))

        Settings.shared.setCollectionCover("liked", "file:///liked.jpg")
        assertNull(libraryCover(liked))
        assertEquals("file:///liked.jpg", libraryArtwork(liked.id, liked.thumbnail))
        assertEquals("file:///override.jpg", libraryArtwork(liked.id, liked.thumbnail, "file:///override.jpg"))
        assertNull(libraryCover(liked.copy(customThumbnail = "file:///override.jpg")))

        val weekly = PlaylistItem(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID, "Weekly", "song.jpg")
        assertEquals(BuiltinPlaylist.WEEKLY.icon, libraryArtwork(weekly.id, weekly.thumbnail))
        assertEquals("custom.jpg", libraryArtwork(weekly.id, weekly.thumbnail, "custom.jpg"))
        assertNull(libraryCover(weekly.copy(customThumbnail = "custom.jpg")))
        assertEquals("remote.jpg", libraryArtwork("PLordinary", "remote.jpg"))
        assertEquals("custom.jpg", libraryArtwork("PLordinary", "remote.jpg", "custom.jpg"))
    }
}

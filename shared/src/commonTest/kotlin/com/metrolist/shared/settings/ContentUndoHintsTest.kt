package com.metrolist.shared.settings

import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentUndoHintsTest {
    private var previousSettings: Settings? = null
    private lateinit var backing: MapSettings

    @BeforeTest
    fun setUp() =
        runTest {
            previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            ContentUndoHints.pending.value.forEach(ContentUndoHints::markShown)
            backing = MapSettings()
            Settings.replaceSharedForTest(Settings(backing))
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
        }

    @AfterTest
    fun tearDown() =
        runTest {
            SongLikeState.awaitPersistence()
            ContentUndoHints.pending.value.forEach(ContentUndoHints::markShown)
            Settings.replaceSharedForTest(previousSettings)
            if (previousSettings != null) SongLikeState.refreshFromSettings()
        }

    @Test
    fun eachUndoPageQueuesOnceAndPersistsOnlyWhenDisplayed() {
        ContentUndoPage.entries.forEach { page ->
            repeat(2) { ContentUndoHints.request(page) }
        }
        assertEquals(ContentUndoPage.entries, ContentUndoHints.pending.value)
        assertFalse(Settings(backing).hiddenLibraryUndoHintShown)
        assertFalse(Settings(backing).blockedSongsUndoHintShown)

        ContentUndoPage.entries.forEach { page ->
            ContentUndoHints.markShown(page)
            Settings.replaceSharedForTest(Settings(backing))
            ContentUndoHints.request(page)
            assertFalse(page in ContentUndoHints.pending.value)
        }
        assertTrue(ContentUndoHints.pending.value.isEmpty())
    }

    @Test
    fun existingHiddenLibraryItemsStillGetAHintOnTheNextHideAndShareOneFlag() {
        Settings.shared.hiddenLibraryPlaylistsJson = """[{"id":"existing","title":"Existing"}]"""
        assertEquals(1, HiddenLibraryItems.all().size)
        assertTrue(ContentUndoHints.pending.value.isEmpty())
        HiddenLibraryItems.hide(PlaylistItem(id = "new", title = "Playlist", thumbnail = null, author = null))
        HiddenLibraryItems.hide(HiddenLibraryItem(HiddenLibraryItemType.AUTO, "liked", "Liked"))
        assertEquals(listOf(ContentUndoPage.HIDDEN_LIBRARY_ITEMS), ContentUndoHints.pending.value)
        ContentUndoHints.markShown(ContentUndoPage.HIDDEN_LIBRARY_ITEMS)
        HiddenLibraryItems.restore(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "new"))
        HiddenLibraryItems.hide(PlaylistItem(id = "new", title = "Playlist", thumbnail = null, author = null))
        assertTrue(ContentUndoHints.pending.value.isEmpty())
    }

    @Test
    fun homeArtistsAndPodcastsShareAHintButArtistBlocksHaveTheirOwn() {
        HiddenHomeItems.hide(ArtistItem(id = "artist", title = "Artist", thumbnail = null))
        HiddenHomeItems.hide(PodcastItem(id = "podcast", title = "Podcast", thumbnail = null, author = null))
        BlockedContent.blockArtist("blocked", "Blocked")
        assertEquals(
            listOf(ContentUndoPage.HIDDEN_HOME_ITEMS, ContentUndoPage.BLOCKED_ARTISTS),
            ContentUndoHints.pending.value,
        )
        ContentUndoHints.markShown(ContentUndoPage.BLOCKED_ARTISTS)
        BlockedContent.unblockArtist("blocked")
        BlockedContent.blockArtist("blocked", "Blocked")
        assertEquals(listOf(ContentUndoPage.HIDDEN_HOME_ITEMS), ContentUndoHints.pending.value)
    }

    @Test
    fun importedDislikesAndInvalidActionsDoNotUseTheHint() =
        runTest {
            val song = SongItem(id = "song", title = "Song", thumbnail = null, artists = emptyList(), album = null)
            BlockedContent.rememberDislikedSong(song)
            BlockedContent.blockArtist("", "Invalid")
            assertFalse(SongLikeState.dislike(song.copy(id = "")))
            assertTrue(ContentUndoHints.pending.value.isEmpty())

            assertTrue(SongLikeState.dislike(song))
            assertTrue(BlockedContent.isSongBlocked(song.id))
            assertEquals(listOf(ContentUndoPage.BLOCKED_SONGS), ContentUndoHints.pending.value)
            assertFalse(SongLikeState.dislike(song))
            ContentUndoHints.markShown(ContentUndoPage.BLOCKED_SONGS)
            assertTrue(SongLikeState.removeDislike(song.id, sync = false))
            assertFalse(BlockedContent.isSongBlocked(song.id))
            assertTrue(SongLikeState.dislike(song))
            assertTrue(ContentUndoHints.pending.value.isEmpty())
        }
}

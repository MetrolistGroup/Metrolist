package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Playlist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemStore
import com.metrolist.shared.settings.HiddenLibraryItemType
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSelectionTest {
    @Test
    fun hiddenDestinationsMatchNormalizedBrowseIdsAndLocalIdsWithoutHidingOtherTypes() {
        var hiddenJson = """[{"id":"VLPLhidden","title":"Hidden"}]"""
        val hidden = HiddenLibraryItemStore({ hiddenJson }, { hiddenJson = it })
        hidden.hide(HiddenLibraryItem(HiddenLibraryItemType.PLAYLIST, "LPlocal", "Local"))
        hidden.hide(HiddenLibraryItem(HiddenLibraryItemType.ALBUM, "PLvisible", "Album"))
        val remote = PlaylistEntity(id = "LPstored", name = "Hidden", browseId = "PLhidden")
        val prefixed = remote.copy(id = "LPduplicate", browseId = "VLPLhidden")
        val local = PlaylistEntity(id = "LPlocal", name = "Local", isLocal = true)
        val visible = PlaylistEntity(id = "LPvisible", name = "Visible", browseId = "PLvisible")
        val entries = listOf(remote, prefixed, local, visible).map { Playlist(it, 7) }

        assertEquals(listOf(visible), entries.map { it.playlist }.selectablePlaylists(hidden.keys()))
        assertEquals(listOf("PLvisible"), entries.selectablePlaylistItems(true, hidden.keys()).map { it.id })
        assertEquals(emptyList(), entries.selectablePlaylistDetails(false, hidden.keys()))

        hidden.restore(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, "VLPLhidden"))
        assertEquals(listOf(remote, visible), entries.selectablePlaylistDetails(true, hidden.keys()).map { it.playlist })
        hidden.restore(HiddenLibraryItemKey(HiddenLibraryItemType.PLAYLIST, local.id))
        assertEquals(listOf(local), entries.selectablePlaylistDetails(false, hidden.keys()).map { it.playlist })
    }

    @Test
    fun storedPickerChoicesIncludeSyncedPlaylistsOnlyWhileSignedIn() {
        val local = PlaylistEntity(id = "LPlocal", name = "Local", isLocal = true)
        val synced = PlaylistEntity(id = "LPsynced", name = "Synced", browseId = "PLremote", customThumbnailUrl = "cover")
        val readonly = synced.copy(id = "LPother", browseId = "PLother", isEditable = false)
        val entries = listOf(local, synced, readonly, synced.copy(id = "LPduplicate")).map { Playlist(it, 7) }
        assertEquals(listOf("LPlocal"), entries.selectablePlaylistItems(false).map { it.id })
        val choices = entries.selectablePlaylistItems(true)
        assertEquals(listOf("LPlocal", "PLremote"), choices.map { it.id })
        assertEquals(listOf(true, false), choices.map { it.isLocal })
    }

    @Test
    fun selectablePlaylistDetailsPreservesCreationOrderAndSongCounts() {
        val first = Playlist(PlaylistEntity(id = "LPfirst", name = "Z", browseId = "PLsame"), 3)
        val duplicate = Playlist(PlaylistEntity(id = "LPduplicate", name = "Duplicate", browseId = "VLPLsame"), 5)
        val last = Playlist(PlaylistEntity(id = "LPlast", name = "A", isLocal = true), 7)
        assertEquals(listOf(first, last), listOf(first, duplicate, last).selectablePlaylistDetails(true))
    }

    @Test
    fun signedOutDetailsKeepLocalAndLegacyDestinations() {
        val local = Playlist(PlaylistEntity(id = "local", name = "Local", isLocal = true), 3)
        val legacy = Playlist(PlaylistEntity(id = "legacy", name = "Legacy"), 5)
        val remote = Playlist(PlaylistEntity(id = "remote", name = "Remote", browseId = "PLremote"), 7)
        val entries = listOf(local, legacy, remote)
        assertEquals(listOf(local, legacy), entries.selectablePlaylistDetails(false))
        assertEquals(entries, entries.selectablePlaylistDetails(true))
    }

    @Test
    fun selectablePlaylistsExcludesNonEditableUnbookmarkedAndSystemPlaylists() {
        val editable = PlaylistEntity(id = "LPeditable", name = "Editable", browseId = "PLeditable")
        val nonEditable = PlaylistEntity(id = "LPreadonly", name = "Readonly", browseId = "PLreadonly", isEditable = false)
        val unbookmarked =
            PlaylistEntity(
                id = "LPcache",
                name = "Cache",
                browseId = "PLcache",
                bookmarkedAt = null,
            )
        val liked = PlaylistEntity(id = PlaylistEntity.LIKED_PLAYLIST_ID, name = "Liked")
        val downloaded = PlaylistEntity(id = PlaylistEntity.DOWNLOADED_PLAYLIST_ID, name = "Downloaded")
        val weekly = PlaylistEntity(id = PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID, name = "Weekly")
        val monthly = PlaylistEntity(id = PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID, name = "Monthly")

        val invalidRemote =
            listOf("OLAK5uy_album", "MPREb_album", "VLLM", "RDmix").map {
                editable.copy(id = "local-$it", browseId = it)
            }
        val result =
            (listOf(editable, nonEditable, unbookmarked, liked, downloaded, weekly, monthly) + invalidRemote).selectablePlaylists()

        assertEquals(listOf(editable), result)
    }

    @Test
    fun selectablePlaylistsDeduplicatesSameBrowseIdWithDifferentIdForms() {
        val first = PlaylistEntity(id = "LPaaaa1111", name = "Trip", browseId = "PLtrip")
        val second = PlaylistEntity(id = "VLPLtrip", name = "Trip", browseId = "PLtrip")
        val third = PlaylistEntity(id = "LPbbbb2222", name = "Trip", browseId = "VLPLtrip")
        val local = PlaylistEntity(id = "LPcccc3333", name = "Local", isLocal = true)
        val other = PlaylistEntity(id = "LPdddd4444", name = "Other", browseId = "PLother")

        val result = listOf(first, second, third, local, other).selectablePlaylists()

        assertEquals(listOf(first, local, other), result)
    }

    @Test
    fun localPlaylistsKeepsOnlyEditableBookmarkedLocalEntries() {
        val local = PlaylistEntity(id = "LPgggg7777", name = "Local", isLocal = true)
        val legacyLocal = PlaylistEntity(id = "LPhhhh8888", name = "Legacy")
        val synced = PlaylistEntity(id = "LPiiii9999", name = "Synced", browseId = "PLsynced")
        val readonlyLocal =
            PlaylistEntity(id = "LPjjjj0000", name = "Readonly", isLocal = true, isEditable = false)
        val unbookmarkedLocal =
            PlaylistEntity(id = "LPkkkk1111", name = "Removed", isLocal = true, bookmarkedAt = null)

        val result = listOf(local, legacyLocal, synced, readonlyLocal, unbookmarkedLocal).localPlaylists()

        assertEquals(listOf(local, legacyLocal), result)
    }
}

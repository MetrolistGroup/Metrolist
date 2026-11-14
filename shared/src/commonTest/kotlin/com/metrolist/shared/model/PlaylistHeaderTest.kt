package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaylistHeaderTest {
    @Test
    fun onlyOwnedRemoteAndEditableLocalPlaylistsCanBeEdited() {
        val community = PlaylistItem("PLcommunity", "Community", null, libraryAddedAt = 1)
        assertFalse(community.canEdit)
        assertTrue(community.copy(isEditable = true).canEdit)
        assertTrue(community.copy(id = "VLPLowned", isEditable = true).canEdit)
        assertTrue(community.copy(id = "LPlocal", isLocal = true, isEditable = true).canEdit)
        assertFalse(community.copy(id = "LPlocal", isLocal = true).canEdit)
        for (id in listOf("RDmix", "VLRDmix", "OLAKalbum", "liked", "downloaded", "LM")) {
            assertFalse(community.copy(id = id, isEditable = true).canEdit, id)
        }
    }

    @Test
    fun navigationArtworkSurvivesBrowseMetadataAndRefreshes() {
        val mix = PlaylistItem("RDmix", "Energy Mix 1", "browse-cover")
        val selected = mix.withNavigationThumbnail("home-cover")
        assertEquals("home-cover", selected.thumbnail)
        assertEquals("home-cover", selected.copy(thumbnail = "refreshed-cover").withNavigationThumbnail("home-cover").thumbnail)
        assertSame(mix, mix.withNavigationThumbnail(null))
        assertSame(mix, mix.withNavigationThumbnail(" "))
        assertEquals("home-cover", mix.copy(thumbnail = null).withNavigationThumbnail("home-cover").thumbnail)
    }

    @Test
    fun navigationArtworkPreservesCustomCoversAndLocalMosaics() {
        val custom = PlaylistItem("PLowned", "Owned", "browse-cover", isEditable = true, customThumbnail = "custom-cover")
        val selected = custom.withNavigationThumbnail("home-cover")
        assertEquals("custom-cover", selected.customThumbnail)
        assertEquals("home-cover", selected.thumbnail)
        val local = custom.copy(id = "LPlocal", isLocal = true, artworkThumbnails = listOf("song-cover"))
        assertSame(local, local.withNavigationThumbnail("home-cover"))
    }
}

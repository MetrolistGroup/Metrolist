package com.metrolist.android.ui.screens.settings

import com.metrolist.shared.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAutoPinnedChoicesTest {
    @Test
    fun libraryAliasIsShownOnlyThroughTheStoredPlaylistChoice() {
        val duplicate = PlaylistItem("VLPLsaved", "Saved from YouTube", null)
        val youtubeOnly = PlaylistItem("VLPLother", "Not saved locally", null)
        assertEquals(
            listOf(youtubeOnly),
            availableYoutubeAutomotivePinChoices(listOf("PLsaved"), listOf(duplicate, youtubeOnly)),
        )
    }

    @Test
    fun aliasComparisonDoesNotRewriteAvailableIdsAndHandlesLegacyStoredPrefix() {
        val duplicate = PlaylistItem("PLsaved", "Saved from YouTube", null)
        val youtubeOnly = listOf(PlaylistItem("VLPLother", "Not stored", null), PlaylistItem("PLanother", "Another", null))
        assertEquals(
            youtubeOnly,
            availableYoutubeAutomotivePinChoices(listOf("VLPLsaved", null, ""), listOf(duplicate) + youtubeOnly),
        )
    }
}

package com.metrolist.shared.ui.menu

import kotlin.test.Test
import kotlin.test.assertEquals

class SongMenuActionGroupsTest {
    @Test
    fun playerPlacesDetailsWithNavigationBeforeDownloadsAndAudio() {
        val groups =
            songMenuActionGroups(
                queue = emptyList(),
                removal = emptyList(),
                library = listOf("download", "dislike", "block"),
                navigation = listOf("artist", "album", "details"),
                additional = listOf("equalizer", "tempo", "sleep"),
                isPlayerMenu = true,
            )
        assertEquals(listOf("artist", "album", "details", "download", "dislike", "block", "equalizer", "tempo", "sleep"), groups.flatten())
    }

    @Test
    fun itemMenusKeepQueueRemovalAndLibraryBeforeNavigation() {
        val groups =
            songMenuActionGroups(
                queue = listOf("play next", "add to queue"),
                removal = listOf("remove"),
                library = listOf("pin", "download"),
                navigation = listOf("artist", "album"),
                additional = listOf("details"),
                isPlayerMenu = false,
            )
        assertEquals(listOf("play next", "add to queue", "remove", "pin", "download", "artist", "album", "details"), groups.flatten())
    }
}

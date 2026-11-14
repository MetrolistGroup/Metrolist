package com.metrolist.desktop.utils

import dev.nucleusframework.media.control.MediaMetadata
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemMediaMetadataUpdatesTest {
    private val song = MediaMetadata(title = "Song", artist = "Artist", album = "Album", duration = 180_000L)
    private val corrected = song.copy(duration = 180_001L, coverUrl = "file:///cover.jpg")
    private val empty = MediaMetadata()

    @Test
    fun linuxPublishesOncePerSongIncludingIdenticalMetadataAndClearing() =
        runTest {
            val changes =
                listOf(
                    "first" to song,
                    "first" to corrected,
                    "first" to corrected.copy(album = "Updated album"),
                    "second" to song,
                    "second" to corrected,
                    null to empty,
                    null to empty,
                    "first" to corrected,
                ).asFlow()

            assertEquals(
                listOf(song, song, empty, corrected),
                changes.systemMediaMetadataUpdates(isLinux = true).toList(),
            )
            assertEquals(
                listOf(song, song, empty, corrected),
                changes.systemMediaMetadataUpdates(isLinux = true).toList(),
                "A new observer must publish its initial metadata",
            )
        }

    @Test
    fun otherPlatformsKeepMetadataCorrections() =
        runTest {
            val changes =
                listOf(
                    "first" to song,
                    "first" to song,
                    "first" to corrected,
                    "second" to corrected,
                    null to empty,
                ).asFlow()

            assertEquals(
                listOf(song, corrected, corrected, empty),
                changes.systemMediaMetadataUpdates(isLinux = false).toList(),
            )
        }
}

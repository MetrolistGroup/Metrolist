package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutomotiveLibraryPagerTest {
    @Test
    fun unavailableRowsDoNotEnterBrowsePagesOrLeavePagingGaps() =
        runBlocking {
            val songs = List(250) { SongItem("song-$it", "Song $it", null, emptyList(), null, isUnavailable = it < 150) }
            val pager = AutomotiveLibraryPager { limit, offset -> songs.drop(offset).take(limit) }
            assertEquals(songs.subList(150, 200), pager.page(0, 50, emptyList(), BlockedContent.snapshot()))
            assertEquals(songs.subList(200, 250), pager.page(1, 50, emptyList(), BlockedContent.snapshot()))
            assertEquals(emptyList<SongItem>(), pager.page(2, 50, emptyList(), BlockedContent.snapshot()))
        }

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun successiveAndRepeatedPagesDoNotReadEarlierBatchesAgain() =
        runBlocking {
            val songs = List(10_000) { SongItem("song-$it", "Song $it", null, emptyList(), null) }
            val offsets = mutableListOf<Int>()
            val pager =
                AutomotiveLibraryPager { limit, offset ->
                    offsets += offset
                    songs.drop(offset).take(limit)
                }
            repeat(100) { page ->
                assertEquals(songs.subList(page * 100, (page + 1) * 100), pager.page(page, 100, emptyList(), BlockedContent.snapshot()))
            }
            assertEquals((0 until 10_000 step 100).toList(), offsets)
            assertEquals(songs.subList(9800, 9900), pager.page(98, 100, emptyList(), BlockedContent.snapshot()))
            assertEquals(100, offsets.size)
        }

    @Test
    fun blockedRowsDoNotLeaveGapsAndFilterChangesResetTheCursor() =
        runBlocking {
            val songs = List(250) { SongItem("song-$it", "Song $it", null, emptyList(), null) }
            Settings.shared.blockedSongIds = songs.take(150).joinToString(",") { it.id }
            var queries = 0
            val pager =
                AutomotiveLibraryPager { limit, offset ->
                    queries++
                    songs.drop(offset).take(limit)
                }
            assertEquals(songs.subList(150, 200), pager.page(0, 50, emptyList(), BlockedContent.snapshot()))
            assertEquals(songs.subList(200, 250), pager.page(1, 50, emptyList(), BlockedContent.snapshot()))
            assertEquals(3, queries)
            Settings.shared.blockedSongIds = ""
            assertEquals(songs.subList(50, 100), pager.page(1, 50, emptyList(), BlockedContent.snapshot()))
            assertEquals(4, queries)
            assertEquals(songs.take(50), pager.page(0, 50, emptyList(), BlockedContent.snapshot()))
            assertEquals(5, queries)
        }
}

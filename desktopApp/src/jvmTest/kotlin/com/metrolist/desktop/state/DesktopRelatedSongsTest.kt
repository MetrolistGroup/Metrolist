package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopRelatedSongs
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopRelatedSongsTest {
    @Test
    fun aSlowOldRequestCannotReplaceTheLatestTracksSuggestions() =
        runTest {
            var current = "a"
            val old = CompletableDeferred<List<SongItem>>()
            val latest = CompletableDeferred<List<SongItem>>()
            val related =
                DesktopRelatedSongs(backgroundScope, { current }) {
                    withContext(NonCancellable) { if (it == "a") old.await() else latest.await() }
                }
            related.fetch("a")
            runCurrent()
            current = "b"
            related.fetch("b")
            runCurrent()
            old.complete(listOf(song("old")))
            runCurrent()
            assertTrue(related.loading)
            assertTrue(related.songs.isEmpty())
            latest.complete(listOf(song("new")))
            runCurrent()
            assertFalse(related.loading)
            assertEquals(listOf(song("new")), related.songs)
        }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}

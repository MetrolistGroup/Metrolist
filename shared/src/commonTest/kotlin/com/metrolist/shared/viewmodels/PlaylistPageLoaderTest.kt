package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.ContinuationBranch
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.model.SongItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPageLoaderTest {
    @Test
    fun firstPageIsImmediateAndConcurrentActionsResumeTheSameCursor() =
        runTest {
            val client = HttpClient(MockEngine { error("No HTTP") })
            try {
                val song = SongItem("song", "Song", null, emptyList(), null, playlistSetVideoId = "first")
                val initial =
                    PlaylistPage(
                        mapOf("Tracks" to listOf(song)),
                        listOf(ContinuationBranch("next")),
                        emptySet(),
                        InnerTube(client).sessionSnapshot(),
                    )
                val gate = CompletableDeferred<Unit>()
                var firstRequests = 0
                var continuationRequests = 0
                val loader =
                    PlaylistPageLoader({
                        firstRequests++
                        initial
                    }) { cursor ->
                        continuationRequests++
                        gate.await()
                        cursor.copy(
                            sections = mapOf("Tracks" to listOf(song, song.copy(playlistSetVideoId = "second"))),
                            pending = emptyList(),
                        )
                    }
                loader.loadFirst()
                assertEquals(1, firstRequests)
                assertEquals(0, continuationRequests)
                assertTrue(loader.page.value!!.hasMore)
                val scrolling = async { loader.loadMore() }
                runCurrent()
                val queue = async { loader.complete() }
                runCurrent()
                gate.complete(Unit)
                scrolling.await()
                val complete = queue.await()
                assertEquals(1, continuationRequests)
                assertEquals(listOf("first", "second"), complete.songs.map { it.playlistSetVideoId })
                assertFalse(complete.hasMore)
                assertFalse(loader.loading.value)
            } finally {
                client.close()
            }
        }

    @Test
    fun failuresAndCancellationKeepTheLastSuccessfulCursor() =
        runTest {
            val client = HttpClient(MockEngine { error("No HTTP") })
            try {
                val initial = PlaylistPage(emptyMap(), listOf(ContinuationBranch("next")), emptySet(), InnerTube(client).sessionSnapshot())
                var failure: Exception? = IllegalStateException()
                val loader =
                    PlaylistPageLoader({ initial }) { cursor ->
                        failure?.let { throw it }
                        cursor.copy(pending = emptyList())
                    }
                loader.loadFirst()
                assertFailsWith<IllegalStateException> { loader.complete() }
                assertEquals(initial, loader.page.value)
                failure = CancellationException()
                assertFailsWith<CancellationException> { loader.loadMore() }
                assertEquals(initial, loader.page.value)
                assertFalse(loader.loading.value)
                failure = null
                assertFalse(loader.complete().hasMore)
            } finally {
                client.close()
            }
        }
}

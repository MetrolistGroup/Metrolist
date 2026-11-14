package com.metrolist.android.playback

import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.buildRadioQueueRecommendations
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioQueueBootstrapTest {
    @Test
    fun bootstrapContinuesAfterPagesConsumedWhileSelectingFreshSongs() =
        runTest {
            val requests = mutableListOf<String?>()
            var selectedContinuation: String? = null
            bootstrapRadioQueue(
                loadPage = { token ->
                    requests += token
                    page(listOf("song"), "original")
                },
                isCurrent = { true },
                applyPage = { _, _ ->
                    selectedContinuation = "after-fresh-page"
                    true
                },
                nextContinuation = { selectedContinuation },
            )
            assertEquals(listOf(null, "after-fresh-page"), requests)
        }

    @Test
    fun ordinaryPlaybackRespectsAutoLoadWhileExplicitRadioCanBootstrap() {
        assertFalse(shouldBootstrapQueueRecommendations(finiteQueue = true, autoLoadMore = false, hasMorePlaylistPages = false))
        assertTrue(shouldBootstrapQueueRecommendations(finiteQueue = true, autoLoadMore = true, hasMorePlaylistPages = false))
        assertTrue(shouldBootstrapQueueRecommendations(finiteQueue = false, autoLoadMore = false, hasMorePlaylistPages = false))
    }

    @Test
    fun finiteCollectionDoesNotFetchOrAppendRecommendationsWithAutoLoadDisabled() =
        runTest {
            val songs = listOf(song("downloaded-first"), song("downloaded-last"))
            val queue = MusicQueue().apply { setQueue(songs, 1) }
            var requests = 0

            bootstrapRadioQueue(
                loadPage = {
                    requests++
                    page(listOf("not-downloaded"), null)
                },
                isCurrent = {
                    shouldBootstrapQueueRecommendations(
                        finiteQueue = true,
                        autoLoadMore = false,
                        hasMorePlaylistPages = false,
                    )
                },
                applyPage = { page, _ ->
                    queue.addToQueue(page.songs)
                    page.songs.isNotEmpty()
                },
            )

            assertEquals(0, requests)
            assertEquals(songs, queue.items.value)
            assertEquals(1, queue.currentIndex.value)
        }

    @Test
    fun playlistPagesPrecedeGeneratedSongsAndRetainTheirOccurrences() =
        runTest {
            val queue = MusicQueue().apply { setQueue(listOf(song("first"), song("repeat"))) }
            var hasMorePages = true
            var requests = 0

            suspend fun bootstrap() =
                bootstrapRadioQueue(
                    loadPage = {
                        requests++
                        page(listOf("recommendation"), null)
                    },
                    isCurrent = {
                        shouldBootstrapQueueRecommendations(
                            finiteQueue = true,
                            autoLoadMore = true,
                            hasMorePlaylistPages = hasMorePages,
                        )
                    },
                    applyPage = { page, _ ->
                        val additions =
                            buildRadioQueueRecommendations(
                                page.songs,
                                queue.items.value.mapTo(mutableSetOf(), SongItem::id),
                                QUEUE_SOURCE_AUTO_MIX,
                                { false },
                            )
                        queue.addToQueue(additions)
                        additions.isNotEmpty()
                    },
                )
            bootstrap()
            assertEquals(0, requests)
            queue.appendPage(listOf(song("repeat"), song("last")))
            hasMorePages = false
            bootstrap()
            assertEquals(listOf("first", "repeat", "repeat", "last", "recommendation"), queue.items.value.map(SongItem::id))
            assertEquals(
                QUEUE_SOURCE_AUTO_MIX,
                queue.items.value
                    .last()
                    .queueSourceTitle,
            )
        }

    @Test
    fun seedOnlyAndFilteredPagesStillLoadTheMixAndSuggestions() =
        runTest {
            val queue = mutableListOf(song("seed"))
            val requests = mutableListOf<String?>()
            val pages =
                mapOf(
                    null to page(listOf("seed"), "first"),
                    "first" to page(listOf("blocked", "seed"), "second"),
                    "second" to page(listOf("mix", "mix"), "third"),
                    "third" to page(listOf("mix", "suggestion"), "unused"),
                )
            bootstrapRadioQueue(
                loadPage = {
                    requests += it
                    pages.getValue(it)
                },
                isCurrent = { true },
                applyPage = { page, initial ->
                    val additions =
                        buildRadioQueueRecommendations(
                            page.songs,
                            queue.mapTo(mutableSetOf(), SongItem::id),
                            if (initial) "mix" else "suggestion",
                            { it.id == "blocked" },
                        )
                    queue += additions
                    additions.isNotEmpty()
                },
            )
            assertEquals(listOf(null, "first", "second", "third"), requests)
            assertEquals(listOf("seed", "mix", "suggestion"), queue.map(SongItem::id))
            assertEquals(listOf(null, "mix", "suggestion"), queue.map(SongItem::queueSourceTitle))
        }

    @Test
    fun exhaustedAndRepeatedContinuationsStopWithoutLooping() =
        runTest {
            for (continuation in listOf(null, "same")) {
                var requests = 0
                bootstrapRadioQueue(
                    loadPage = {
                        requests++
                        page(emptyList(), continuation)
                    },
                    isCurrent = { true },
                    applyPage = { _, _ -> false },
                )
                assertEquals(if (continuation == null) 1 else 2, requests)
            }
        }

    @Test
    fun endlessFilteredPagesHaveABoundedRequestCount() =
        runTest {
            var requests = 0
            bootstrapRadioQueue(
                loadPage = { page(emptyList(), (++requests).toString()) },
                isCurrent = { true },
                applyPage = { _, _ -> false },
            )
            assertEquals(10, requests)
        }

    @Test
    fun replacedQueueRejectsAnInFlightPage() =
        runTest {
            var current = true
            bootstrapRadioQueue(
                loadPage = {
                    current = false
                    page(listOf("stale"), "next")
                },
                isCurrent = { current },
                applyPage = { _, _ -> error("Stale recommendations must not replace the new queue") },
            )
            bootstrapRadioQueue(
                loadPage = { error("An obsolete bootstrap must not make another request") },
                isCurrent = { false },
                applyPage = { _, _ -> error("No page to apply") },
            )
        }

    @Test
    fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            runTest {
                bootstrapRadioQueue(
                    loadPage = { throw CancellationException() },
                    isCurrent = { true },
                    applyPage = { _, _ -> error("No page to apply") },
                )
            }
        }
    }

    private fun page(
        ids: List<String>,
        continuation: String?,
    ) = RadioQueuePage(ids.map(::song), emptyList(), continuation)

    private fun song(id: String) = SongItem(id = id, title = id, thumbnail = null, artists = emptyList(), album = null)
}

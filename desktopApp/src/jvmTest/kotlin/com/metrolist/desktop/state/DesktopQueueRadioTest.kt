package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopQueueRadio
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopQueueRadioTest {
    private fun song(id: String) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = null, album = null)

    private fun page(
        songs: List<SongItem>,
        continuation: String? = null,
    ) = RadioQueuePage(songs, emptyList(), continuation)

    @Test
    fun replacingQueueRetainsHistoryAndFollowsContinuationForTheSameSeed() =
        runTest {
            val seed = song("seed")
            val queue = MusicQueue().apply { setQueue(listOf(seed)) }
            val requests = mutableListOf<String>()
            val radio =
                DesktopQueueRadio(
                    this,
                    queue,
                    {},
                    { page(listOf(seed, song("old")), "more") },
                    { false },
                    continueRadio = { token ->
                        requests += token
                        page(listOf(song("new")), "later")
                    },
                    autoLoadMore = { true },
                )
            radio.syncQueue()
            radio.load(4, seed, false) { true }
            assertEquals(listOf("seed", "old"), queue.items.value.map(SongItem::id))
            queue.setQueue(listOf(seed))
            radio.reset()
            radio.syncQueue()
            radio.load(4, seed, false) { true }
            assertEquals(listOf("seed", "new"), queue.items.value.map(SongItem::id))
            assertEquals(listOf("more"), requests)
        }

    @Test
    fun artistQueueFiltersVideosFromEveryRecommendationBatch() =
        runBlocking {
            val seed = song("seed")
            val audio = song("audio")
            val video = song("video").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
            val queue = MusicQueue().apply { setQueue(listOf(seed), recommendationsSongsOnly = true) }
            val radio = DesktopQueueRadio(this, queue, {}, { page(listOf(video, audio)) }, { false }, autoLoadMore = { true })
            radio.syncQueue()
            radio.load(4, seed, false) { true }
            radio.load(4, seed, false) { true }
            assertEquals(listOf("seed", "audio"), queue.items.value.map { it.id })
        }

    @Test
    fun staleRadioResponseDoesNotMutateQueueOrHistory() =
        runBlocking {
            val seed = song("seed")
            val next = song("next").copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX)
            val response = CompletableDeferred<List<SongItem>>()
            val queue = MusicQueue().apply { setQueue(listOf(seed)) }
            var changes = 0
            var current = true
            val radio = DesktopQueueRadio(this, queue, { changes++ }, { page(response.await()) }, { false }, autoLoadMore = { true })
            radio.syncQueue()
            val request = async { radio.load(4, seed, false) { current } }
            yield()
            current = false
            response.complete(listOf(next))
            assertNull(request.await())
            assertEquals(listOf(seed), queue.items.value)
            assertEquals(0, changes)

            assertNull(radio.load(4, seed, false) { true })
            assertEquals(listOf(seed, next), queue.items.value)
            assertEquals(1, changes)
            assertEquals(next, radio.load(4, seed, true) { true })
            assertEquals(next, queue.currentItem())
            assertEquals(2, changes)
            assertNull(radio.load(4, seed, false) { true })
            assertEquals(listOf(seed, next), queue.items.value)
            assertEquals(2, changes)
        }

    @Test
    fun disabledAutoLoadDoesNotFetchOrAppendIncludingAnInFlightResponse() =
        runBlocking {
            val seed = song("seed")
            val queue = MusicQueue().apply { setQueue(listOf(seed)) }
            val response = CompletableDeferred<RadioQueuePage>()
            var enabled = false
            var requests = 0
            var changes = 0
            val radio =
                DesktopQueueRadio(
                    this,
                    queue,
                    { changes++ },
                    {
                        requests++
                        response.await()
                    },
                    { false },
                    continueRadio = { error("Disabled responses must not advance the continuation") },
                    autoLoadMore = { enabled },
                )
            radio.syncQueue()
            radio.prefetch(seed) { true }
            yield()
            assertNull(radio.load(4, seed, true) { true })
            assertEquals(0, requests)

            enabled = true
            val request = async { radio.load(4, seed, true) { true } }
            yield()
            enabled = false
            response.complete(page(listOf(song("unwanted")), "stale"))
            assertNull(request.await())
            assertEquals(listOf(seed), queue.items.value)
            assertEquals(seed, queue.currentItem())
            assertEquals(0, changes)
            enabled = true
            assertEquals("unwanted", radio.load(4, seed, true) { true }?.id)
            assertEquals(2, requests)
        }

    @Test
    fun duplicateAndFilteredRadioPagesContinueUntilANewSongCanPlay() =
        runBlocking {
            val seed = song("seed")
            val next = song("next").copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX)
            val queue = MusicQueue().apply { setQueue(listOf(seed)) }
            val tokens = mutableListOf<String>()
            var starts = 0
            val radio =
                DesktopQueueRadio(
                    this,
                    queue,
                    {},
                    {
                        starts++
                        page(listOf(seed), "duplicate")
                    },
                    { it.id == "blocked" },
                    continueRadio = { token ->
                        tokens += token
                        when (token) {
                            "duplicate" -> page(listOf(seed, song("blocked")), "fresh")
                            "fresh" -> page(listOf(next), "later")
                            "later" -> page(listOf(song("last")))
                            else -> error("Unexpected continuation: $token")
                        }
                    },
                    autoLoadMore = { true },
                )
            radio.syncQueue()
            assertEquals(next, radio.load(4, seed, true) { true })
            assertEquals(listOf("duplicate", "fresh"), tokens)
            assertEquals(next, queue.currentItem())
            assertEquals(listOf(seed, next), queue.items.value)

            radio.load(4, next, false) { true }
            assertEquals(listOf("duplicate", "fresh", "later"), tokens)
            assertEquals(1, starts)
            assertEquals(listOf("seed", "next", "last"), queue.items.value.map { it.id })

            radio.reset()
            radio.load(4, next, false) { true }
            assertEquals(2, starts)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelledPrefetchReleasesTheQueueForTheNewPlaybackRequest() =
        runTest {
            val seed = song("seed")
            val next = song("next")
            val queue = MusicQueue().apply { setQueue(listOf(seed)) }
            val pending = CompletableDeferred<RadioQueuePage>()
            var requests = 0
            val radio =
                DesktopQueueRadio(
                    this,
                    queue,
                    {},
                    {
                        requests++
                        if (requests == 1) pending.await() else page(listOf(next))
                    },
                    { false },
                    autoLoadMore = { true },
                )
            radio.syncQueue()
            radio.prefetch(seed) { true }
            runCurrent()
            assertEquals(1, requests)

            radio.cancelPrefetch()
            val replacement = async { radio.load(4, seed, true) { true } }
            runCurrent()
            assertEquals(2, requests)
            assertEquals("next", replacement.await()?.id)
            assertEquals(listOf("seed", "next"), queue.items.value.map { it.id })
        }

    @Test
    fun repeatedEmptyContinuationsStopWithoutLooping() =
        runBlocking {
            val seed = song("seed")
            val queue = MusicQueue().apply { setQueue(listOf(seed)) }
            var continuations = 0
            val radio =
                DesktopQueueRadio(
                    this,
                    queue,
                    {},
                    { page(listOf(seed), "same") },
                    { false },
                    continueRadio = {
                        continuations++
                        page(emptyList(), "same")
                    },
                    autoLoadMore = { true },
                )
            radio.syncQueue()
            assertNull(radio.load(4, seed, true) { true })
            assertEquals(1, continuations)
            assertEquals(listOf(seed), queue.items.value)
        }
}

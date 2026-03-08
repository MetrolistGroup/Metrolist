package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.PodcastPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastPagerTest {
    private fun page(
        id: String,
        continuation: String?,
    ) = PodcastPage(PodcastItem("show", "Show", null), listOf(EpisodeItem(id, id, null)), continuations = listOfNotNull(continuation))

    @Test
    fun slowMembershipDoesNotDelayFirstPage() =
        runTest {
            val pager =
                PodcastPager(this) {
                    flow {
                        delay(100)
                        emit(page("one", "next"))
                    }
                }
            launch {
                delay(500)
                pager.updateSavedEpisodes(listOf(EpisodeItem("one", "One", null, saved = true)))
            }
            pager.loadMore()
            advanceTimeBy(100)
            runCurrent()
            assertNotNull(pager.state.value.page)
            assertFalse(
                pager.state.value.page!!
                    .episodes
                    .single()
                    .saved,
            )
            assertEquals(100L, testScheduler.currentTime)
            advanceUntilIdle()
            assertTrue(
                pager.state.value.page!!
                    .episodes
                    .single()
                    .saved,
            )
            assertEquals(500L, testScheduler.currentTime)
        }

    @Test
    fun firstPageDoesNotPrefetchAndDuplicateDemandCannotStartConcurrentRequests() =
        runTest {
            val requests = mutableListOf<String?>()
            val pager =
                PodcastPager(this) { initial ->
                    flow {
                        if (initial != null) emit(initial)
                        requests += initial?.continuation
                        delay(100)
                        emit(if (initial == null) page("one", "next") else page("two", null))
                    }
                }
            pager.loadMore()
            pager.loadMore()
            runCurrent()
            assertTrue(pager.state.value.isLoading)
            assertEquals(listOf<String?>(null), requests)
            advanceUntilIdle()
            assertEquals(
                listOf("one"),
                pager.state.value.page
                    ?.episodes
                    ?.map { it.id },
            )
            assertEquals(100L, testScheduler.currentTime)
            pager.loadMore()
            pager.loadMore()
            advanceUntilIdle()
            assertEquals(listOf(null, "next"), requests)
            assertEquals(
                listOf("one", "two"),
                pager.state.value.page
                    ?.episodes
                    ?.map { it.id },
            )
            pager.loadMore()
            advanceUntilIdle()
            assertEquals(2, requests.size)
        }

    @Test
    fun failedContinuationKeepsRowsAndRetriesOnlyTheFailedPage() =
        runTest {
            val requests = mutableListOf<String?>()
            var fail = true
            val pager =
                PodcastPager(this) { initial ->
                    flow {
                        if (initial != null) emit(initial)
                        requests += initial?.continuation
                        if (initial != null && fail) error("offline")
                        emit(
                            if (initial ==
                                null
                            ) {
                                page("one", "next")
                            } else {
                                page("two", null).copy(episodes = listOf(EpisodeItem("one", "one", null), EpisodeItem("two", "two", null)))
                            },
                        )
                    }
                }
            pager.loadMore()
            advanceUntilIdle()
            pager.loadMore()
            advanceUntilIdle()
            assertNotNull(pager.state.value.error)
            assertFalse(pager.state.value.isLoading)
            assertEquals(
                listOf("one"),
                pager.state.value.page
                    ?.episodes
                    ?.map { it.id },
            )
            pager.loadMore()
            advanceUntilIdle()
            assertEquals(2, requests.size)
            fail = false
            pager.retry()
            advanceUntilIdle()
            assertEquals(listOf(null, "next", "next"), requests)
            assertEquals(
                listOf("one", "two"),
                pager.state.value.page
                    ?.episodes
                    ?.map { it.id },
            )
            assertNull(pager.state.value.error)
        }

    @Test
    fun emptyPagesAdvanceAndTokenCyclesStop() =
        runTest {
            var requests = 0
            val pager =
                PodcastPager(this) { initial ->
                    flow {
                        if (initial != null) emit(initial)
                        requests++
                        emit(
                            page(
                                "one",
                                when (initial?.continuation) {
                                    null -> "a"
                                    "a" -> "b"
                                    else -> "a"
                                },
                            ).copy(episodes = emptyList()),
                        )
                    }
                }
            repeat(5) {
                pager.loadMore()
                advanceUntilIdle()
            }
            assertEquals(3, requests)
            assertNull(
                pager.state.value.page
                    ?.continuation,
            )
        }

    @Test
    fun membershipCanArriveAfterFirstPageAndApplyToFuturePages() =
        runTest {
            val pager =
                PodcastPager(this) { initial ->
                    flow {
                        if (initial != null) emit(initial)
                        emit(if (initial == null) page("one", "next") else page("two", null))
                    }
                }
            pager.loadMore()
            advanceUntilIdle()
            pager.updateSavedEpisodes(listOf(EpisodeItem("two", "two", null, saved = true, playlistSetVideoId = "entry")))
            pager.loadMore()
            advanceUntilIdle()
            val episodes =
                pager.state.value.page!!
                    .episodes
            assertFalse(episodes.first().saved)
            assertTrue(episodes.last().saved)
            assertEquals("entry", episodes.last().playlistSetVideoId)
            pager.updateSavedEpisodes(emptyList())
            assertFalse(
                pager.state.value.page!!
                    .episodes
                    .last()
                    .saved,
            )
        }

    @Test
    fun cancellingInFlightContinuationDoesNotPublishItsResult() =
        runTest {
            val pager =
                PodcastPager(this) { initial ->
                    flow {
                        if (initial != null) {
                            emit(initial)
                            delay(1_000)
                        }
                        emit(if (initial == null) page("one", "next") else page("two", null))
                    }
                }
            pager.loadMore()
            advanceUntilIdle()
            pager.loadMore()
            runCurrent()
            pager.cancel()
            advanceUntilIdle()
            assertEquals(
                listOf("one"),
                pager.state.value.page
                    ?.episodes
                    ?.map { it.id },
            )
            assertNull(pager.state.value.error)
            assertFalse(pager.state.value.isLoading)
        }
}

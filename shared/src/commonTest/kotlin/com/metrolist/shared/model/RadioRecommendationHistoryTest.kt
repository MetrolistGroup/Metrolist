package com.metrolist.shared.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RadioRecommendationHistoryTest {
    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    private fun page(
        vararg ids: String,
        continuation: String? = null,
    ) = RadioQueuePage(ids.map(::song), emptyList(), continuation)

    @Test
    fun repeatedSeedWithIdenticalFirstPageSelectsDifferentSongs() =
        runTest {
            val history = RadioRecommendationHistory()
            val firstPage = page("seed", "a", "b", continuation = "next")
            val filter: (List<SongItem>) -> List<SongItem> = { songs -> songs.filterNot { it.id == "seed" } }
            val first = history.select(firstPage, filter, { true }) { error("Fresh first page must not prefetch") }!!
            history.remember(first.songs)
            val requests = mutableListOf<String>()
            val second =
                history.select(firstPage, filter, { true }) {
                    requests += it
                    page("a", "c", "d", continuation = "later")
                }!!
            assertEquals(setOf("a", "b"), first.songs.map(SongItem::id).toSet())
            assertEquals(setOf("c", "d"), second.songs.map(SongItem::id).toSet())
            assertEquals(listOf("next"), requests)
            assertEquals("later", second.continuation)
        }

    @Test
    fun mixedPagesPreferUnseenAndFilterBeforeLookingForVariety() =
        runTest {
            val history = RadioRecommendationHistory()
            history.remember(listOf(song("recent")))
            val selected =
                history.select(
                    page("seed", "blocked", "", "recent", continuation = "next"),
                    { songs -> buildRadioQueueRecommendations(songs, setOf("seed"), "generated", { it.id == "blocked" }) },
                    { true },
                ) { page("recent", "new", "new", "blocked") }!!
            assertEquals(listOf("new"), selected.songs.map(SongItem::id))
            assertEquals("generated", selected.songs.single().queueSourceTitle)
        }

    @Test
    fun boundedSearchReusesOldestCandidatesAndPreservesCursorAndChips() =
        runTest {
            val history = RadioRecommendationHistory()
            history.remember(listOf(song("older"), song("newer")))
            val chip = RadioQueueChip("chip", "chip", "playlist", null, true)
            var requests = 0
            val selected =
                history.select(
                    page("newer", "older", continuation = "0").copy(chips = listOf(chip)),
                    { it },
                    { true },
                ) { page("newer", continuation = (++requests).toString()) }!!
            assertEquals(2, requests)
            assertEquals(listOf("older", "newer"), selected.songs.map(SongItem::id))
            assertEquals("2", selected.continuation)
            assertEquals(listOf(chip), selected.chips)
        }

    @Test
    fun repeatedTokensAndFailedSearchesKeepUsableCandidates() =
        runTest {
            for (fail in listOf(false, true)) {
                val history = RadioRecommendationHistory()
                history.remember(listOf(song("recent")))
                var requests = 0
                val selected =
                    history.select(page("recent", continuation = "same"), { it }, { true }) {
                        requests++
                        if (fail) error("offline")
                        page("recent", continuation = "same")
                    }!!
                assertEquals(1, requests)
                assertEquals(listOf("recent"), selected.songs.map(SongItem::id))
                assertEquals(if (fail) "same" else null, selected.continuation)
            }
        }

    @Test
    fun discardedResultsDoNotEnterHistoryAndStaleSearchesStop() =
        runTest {
            val history = RadioRecommendationHistory()
            history.select(page("uncommitted"), { it }, { true }) { error("No continuation") }
            val selected = history.select(page("uncommitted", "other"), { it }, { true }) { error("No continuation") }!!
            assertEquals(setOf("uncommitted", "other"), selected.songs.map(SongItem::id).toSet())
            history.remember(selected.songs)
            var current = true
            assertNull(
                history.select(page("other", continuation = "next"), { it }, { current }) {
                    current = false
                    page("stale", continuation = "unused")
                },
            )
            assertNull(history.select(page("other"), { error("Stale filter") }, { false }) { error("Stale request") })
        }

    @Test
    fun filteredPagesStillReachFreshSongsAndStopAtThePageLimit() =
        runTest {
            for (freshPage in listOf(5, 20)) {
                var requests = 0
                val selected =
                    RadioRecommendationHistory().select(
                        page("seed", continuation = "0"),
                        { songs -> songs.filterNot { it.id == "seed" } },
                        { true },
                    ) {
                        requests++
                        page(if (requests == freshPage) "fresh" else "seed", continuation = requests.toString())
                    }!!
                assertEquals(minOf(freshPage, 9), requests)
                assertEquals(if (freshPage == 5) listOf("fresh") else emptyList(), selected.songs.map(SongItem::id))
            }
        }

    @Test
    fun cancellationPropagates() =
        runTest {
            val history = RadioRecommendationHistory()
            history.remember(listOf(song("recent")))
            assertFailsWith<CancellationException> {
                history.select(page("recent", continuation = "next"), { it }, { true }) { throw CancellationException() }
            }
        }

    @Test
    fun historyIsBoundedAndRememberingRefreshesRecency() =
        runTest {
            val history = RadioRecommendationHistory()
            history.remember((0 until 500).map { song(it.toString()) })
            history.remember(listOf(song("0")))
            history.remember(listOf(song("500")))
            val selected = history.select(page("0", "1", "500"), { it }, { true }) { error("No continuation") }!!
            assertEquals(listOf("1"), selected.songs.map(SongItem::id))
        }
}

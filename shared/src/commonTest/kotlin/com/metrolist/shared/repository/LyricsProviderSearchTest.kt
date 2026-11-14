package com.metrolist.shared.repository

import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsProviderSearchTest {
    @Test
    fun searchesAllProvidersInParallelOnOpenIncludingDisabledOnes() =
        runTest {
            val calls = mutableListOf<LyricsProvider>()
            val pending = CompletableDeferred<Unit>()
            val search =
                LyricsProviderSearch(this, "LRCLIB,BETTERLYRICS") { provider ->
                    calls += provider
                    pending.await()
                    listOf(LyricsProviderResult(provider, provider.name, "Lyrics", LyricsTimingKind.UNSYNCED))
                }
            val expected =
                listOf(LyricsProvider.USER) + LyricsProvider.parseUserOrderFromStoredCsv("LRCLIB,BETTERLYRICS") +
                    LyricsProvider.afterUserLyricsFallbacks
            assertEquals(expected, search.groups.value.map { it.provider })
            assertTrue(search.groups.value.all { it.loading && !it.hasResults })
            runCurrent()
            assertEquals(expected, calls)
            assertTrue(search.groups.value.all { it.loading && !it.hasResults })
            pending.complete(Unit)
            runCurrent()
            assertTrue(search.groups.value.all { !it.loading && it.hasResults && !it.expanded })
            search.cancel()
        }

    @Test
    fun showsOnlyCompletedNonemptyGroupsInSettingsOrderAsTheyFinish() =
        runTest {
            val pending = mutableMapOf<LyricsProvider, CompletableDeferred<List<LyricsProviderResult>>>()
            val search =
                LyricsProviderSearch(this, "LRCLIB,BETTERLYRICS") { provider ->
                    pending.getOrPut(provider) { CompletableDeferred() }.await()
                }
            runCurrent()
            assertTrue(search.groups.value.none { it.hasResults })
            pending.getValue(LyricsProvider.USER).complete(emptyList())
            pending.getValue(LyricsProvider.KUGOU).completeExceptionally(IllegalStateException("Offline"))
            runCurrent()
            assertTrue(search.groups.value.none { it.hasResults })
            assertTrue(search.groups.value.any { it.loading })
            pending.getValue(LyricsProvider.BETTERLYRICS).complete(
                listOf(LyricsProviderResult(LyricsProvider.BETTERLYRICS, "Better Lyrics", "Lyrics", LyricsTimingKind.UNSYNCED)),
            )
            runCurrent()
            assertEquals(
                listOf(LyricsProvider.BETTERLYRICS),
                search.groups.value
                    .filter { it.hasResults }
                    .map { it.provider },
            )
            search.toggle(LyricsProvider.BETTERLYRICS)
            pending.getValue(LyricsProvider.LRCLIB).complete(
                listOf(LyricsProviderResult(LyricsProvider.LRCLIB, "LRCLIB", "Lyrics", LyricsTimingKind.UNSYNCED)),
            )
            runCurrent()
            val visible = search.groups.value.filter { it.hasResults }
            assertEquals(listOf(LyricsProvider.LRCLIB, LyricsProvider.BETTERLYRICS), visible.map { it.provider })
            assertTrue(visible.last().expanded)
            pending.values.forEach { it.complete(emptyList()) }
            runCurrent()
            assertFalse(search.groups.value.any { it.loading })
            assertEquals(visible, search.groups.value.filter { it.hasResults })
            search.cancel()
        }

    @Test
    fun collapseKeepsOtherRequestsRunningAndCompletedResultsCached() =
        runTest {
            val calls = mutableListOf<LyricsProvider>()
            var cancelled = 0
            val search =
                LyricsProviderSearch(this, "LRCLIB") { provider ->
                    calls += provider
                    if (provider == LyricsProvider.LRCLIB) {
                        listOf(LyricsProviderResult(provider, "LRCLIB", "Lyrics", LyricsTimingKind.UNSYNCED))
                    } else {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                }
            runCurrent()
            val callCount = calls.size
            search.toggle(LyricsProvider.LRCLIB)
            search.toggle(LyricsProvider.LRCLIB)
            search.toggle(LyricsProvider.LRCLIB)
            runCurrent()
            assertEquals(callCount, calls.size)
            assertEquals(0, cancelled)
            assertTrue(
                search.groups.value
                    .first { it.provider == LyricsProvider.LRCLIB }
                    .expanded,
            )
            assertTrue(
                search.groups.value
                    .filter { it.provider != LyricsProvider.LRCLIB }
                    .all { it.loading },
            )
            search.cancel()
            runCurrent()
            assertEquals(callCount - 1, cancelled)
        }

    @Test
    fun dismissalCancelsAllRequestsAndDiscardsLateResults() =
        runTest {
            val started = mutableListOf<LyricsProvider>()
            val cancelled = mutableListOf<LyricsProvider>()
            val search =
                LyricsProviderSearch(this, "LRCLIB") { provider ->
                    started += provider
                    try {
                        awaitCancellation()
                    } catch (_: CancellationException) {
                        cancelled += provider
                        listOf(LyricsProviderResult(provider, provider.name, "Lyrics", LyricsTimingKind.UNSYNCED))
                    }
                }
            runCurrent()
            search.cancel()
            runCurrent()
            assertEquals(started.toSet(), cancelled.toSet())
            assertEquals(search.groups.value.size, cancelled.size)
            assertTrue(search.groups.value.none { it.hasResults })
            search.toggle(LyricsProvider.LRCLIB)
            runCurrent()
            assertTrue(search.groups.value.none { it.expanded })
            assertEquals(started.size, cancelled.size)
        }

    @Test
    fun selectionCancelsOtherProvidersAndCannotApplyTwiceOrAfterDismissal() =
        runTest {
            val result = LyricsProviderResult(LyricsProvider.LRCLIB, "LRCLIB", "Lyrics", LyricsTimingKind.UNSYNCED)
            var cancelled = 0
            val search =
                LyricsProviderSearch(this, "LRCLIB,BETTERLYRICS") { provider ->
                    if (provider == LyricsProvider.LRCLIB) {
                        listOf(result)
                    } else {
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled++
                        }
                    }
                }
            val selected = mutableListOf<LyricsProviderResult>()
            runCurrent()
            search.select(result, selected::add)
            search.select(result, selected::add)
            runCurrent()
            assertEquals(listOf(result), selected)
            assertEquals(search.groups.value.size - 1, cancelled)
            val dismissed = LyricsProviderSearch(this, "LRCLIB") { listOf(result) }
            runCurrent()
            dismissed.cancel()
            dismissed.select(result, selected::add)
            assertEquals(1, selected.size)
        }

    @Test
    fun emptyResultsFinishLoadingAndStayCachedForTheQuery() =
        runTest {
            var calls = 0
            val search =
                LyricsProviderSearch(this, "LRCLIB") {
                    calls++
                    emptyList()
                }
            runCurrent()
            assertTrue(search.groups.value.all { !it.loading && !it.hasResults && it.results?.isEmpty() == true })
            search.toggle(LyricsProvider.LRCLIB)
            search.toggle(LyricsProvider.LRCLIB)
            runCurrent()
            assertEquals(search.groups.value.size, calls)
            search.cancel()
        }

    @Test
    fun allFailuresFinishLoadingWithoutVisibleGroupsOrRetries() =
        runTest {
            var calls = 0
            val search =
                LyricsProviderSearch(this, "LRCLIB") {
                    calls++
                    error("Offline")
                }
            runCurrent()
            assertTrue(search.groups.value.all { !it.loading && !it.hasResults && it.failed })
            search.toggle(LyricsProvider.LRCLIB)
            search.toggle(LyricsProvider.LRCLIB)
            runCurrent()
            assertEquals(search.groups.value.size, calls)
            search.cancel()
        }

    @Test
    fun previewsRetainTimestampsAndPlainLyrics() {
        assertEquals(
            "[00:24.25] First\n[01:03.07] Second",
            lyricsResultPreview(
                listOf(
                    LyricsEntry(24_250, "First"),
                    LyricsEntry(63_070, "Second"),
                ),
            ),
        )
        assertEquals("First\nSecond", lyricsResultPreview(listOf(LyricsEntry(0, "First"), LyricsEntry(0, "Second"))))
    }
}

package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeChipFeedTest {
    private val focus = Chip("Focus", params = "focus-params")
    private val workout = Chip("Workout", params = "workout-params", browseId = "workout-feed")

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun randomSelectionUsesEachEndpointOnceAndStopsEvenWhenPagesAreEmpty() {
        var state =
            HomeUiState(
                chips = listOf(Chip("All"), focus, focus.copy(title = "Same endpoint", browseId = "FEmusic_home"), workout),
            )
        val random = Random(42)
        val selected = mutableListOf<Chip>()
        repeat(2) {
            val chip = assertNotNull(state.nextHomeChip(random))
            selected += chip
            state = state.withChipFeed(chip, emptyList())
        }
        assertEquals(setOf(focus, workout), selected.toSet())
        assertTrue(state.chipFeeds.all { it.sections.isEmpty() })
        assertNull(state.nextHomeChip(random))
        assertNull(state.homePaginationKey)
    }

    @Test
    fun mainContinuationAndSelectedChipPreventRandomChipLoading() {
        val state = HomeUiState(chips = listOf(focus, workout))
        assertNull(state.copy(continuation = "main-next").nextHomeChip())
        assertEquals(HomePaginationKey("main-next", null, 0), state.copy(continuation = "main-next").homePaginationKey)
        assertNull(state.copy(selectedChip = focus).nextHomeChip())
        assertNull(state.copy(selectedChip = focus).homePaginationKey)
        assertEquals(focus, state.copy(usedHomeChips = listOf(workout)).nextHomeChip())
        assertEquals(workout, state.copy(usedHomeChips = listOf(focus)).nextHomeChip())
    }

    @Test
    fun sameTitleAndEndpointShelvesWithDifferentItemsSurviveAcrossChips() {
        val main = shelf("Mixed for you", "main").copy(browseId = "mixes")
        val first = main.copy(items = shelf("", "focus").items)
        val second = main.copy(items = shelf("", "workout").items)
        val state =
            HomeUiState(data = listOf(main), chips = listOf(focus, workout))
                .withChipFeed(focus, listOf(first))
                .withChipFeed(workout, listOf(second))
        assertEquals(listOf(first, second), state.chipFeeds.flatMap { it.sections })
        assertEquals(listOf(main), state.data)
    }

    @Test
    fun onlyShelvesWithTheSameCanonicalItemsAreDuplicates() {
        val main = shelf("Main", "one", "two")
        val discovery = shelf("Discovery", "three")
        val state =
            HomeUiState(data = listOf(main), discoverySections = listOf(discovery))
                .withChipFeed(
                    focus,
                    listOf(
                        shelf("Different metadata", "two", "one", "one").copy(browseId = "another"),
                        shelf("Different title", "three"),
                        shelf("Main", "one", "new"),
                        shelf("New", "four", "four"),
                        shelf("Repeated", "four"),
                    ),
                )
        assertEquals(listOf(shelf("Main", "one", "new"), shelf("New", "four")), state.chipFeeds.single().sections)
    }

    @Test
    fun chipContinuationsLoadBeforeAnotherChipAndAppendAllDistinctShelves() =
        runTest {
            val firstRequests = mutableListOf<Chip>()
            val continuationRequests = mutableListOf<String>()
            val pager =
                HomeChipFeedPager(
                    loadFirst = { chip ->
                        firstRequests += chip
                        HomePageData(sections = listOf(shelf("Mixed for you", chip.params!!)), continuation = "next")
                    },
                    loadContinuation = { chip, token ->
                        assertEquals(focus, chip)
                        continuationRequests += token
                        HomePageData(sections = listOf(shelf("Mixed for you", "continued"), shelf("Quick picks", "quick")))
                    },
                )
            val first = assertNotNull(pager.loadNext(HomeUiState(chips = listOf(focus))))
            assertEquals("next", first.chipFeeds.single().continuation)
            assertNotNull(first.homePaginationKey)
            val continued = assertNotNull(pager.loadNext(first.copy(chips = listOf(focus, workout))))
            assertEquals(listOf(focus), firstRequests)
            assertEquals(listOf("next"), continuationRequests)
            assertEquals(
                listOf("Mixed for you", "Mixed for you", "Quick picks"),
                continued.chipFeeds
                    .single()
                    .sections
                    .map { it.title },
            )
            assertEquals(listOf(focus), continued.usedHomeChips)
            val last = assertNotNull(pager.loadNext(continued))
            assertEquals(listOf(focus, workout), firstRequests)
            assertEquals(workout, last.chipFeeds.last().chip)
        }

    @Test
    fun failuresRetryTheSameChipAndCursorWithoutEndingTheSequence() =
        runTest {
            var firstAttempts = 0
            var continuationAttempts = 0
            val pager =
                HomeChipFeedPager(
                    loadFirst = { chip ->
                        if (firstAttempts++ == 0) error("first request failed")
                        HomePageData(sections = listOf(shelf(chip.title, chip.title)), continuation = "cursor".takeIf { chip == focus })
                    },
                    loadContinuation = { _, cursor ->
                        assertEquals("cursor", cursor)
                        if (continuationAttempts++ == 0) error("continuation failed")
                        HomePageData(sections = listOf(shelf("More", "more")))
                    },
                )
            val initial = HomeUiState(chips = listOf(focus))
            assertFailsWith<IllegalStateException> { pager.loadNext(initial) }
            assertTrue(initial.usedHomeChips.isEmpty())
            val first = assertNotNull(pager.loadNext(initial))
            assertFailsWith<IllegalStateException> { pager.loadNext(first) }
            assertEquals("cursor", first.chipFeeds.single().continuation)
            val completed = assertNotNull(pager.loadNext(first))
            assertNull(completed.homePaginationKey)
            val next = assertNotNull(pager.loadNext(completed.copy(chips = listOf(focus, workout))))
            assertEquals(listOf(focus, workout), next.usedHomeChips)
            assertEquals(2, continuationAttempts)
        }

    @Test
    fun concurrentEndTriggersStartOnlyOneRequest() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val pager =
                HomeChipFeedPager(
                    loadFirst = {
                        requests++
                        release.await()
                        HomePageData(sections = listOf(shelf("Loaded", "loaded")))
                    },
                    loadContinuation = { _, _ -> error("Unexpected continuation") },
                )
            val state = HomeUiState(chips = listOf(focus))
            val running = async(start = CoroutineStart.UNDISPATCHED) { pager.loadNext(state) }
            repeat(10) { assertNull(pager.loadNext(state)) }
            assertEquals(1, requests)
            release.complete(Unit)
            assertEquals(listOf(focus), assertNotNull(running.await()).usedHomeChips)
        }

    @Test
    fun cancellationReleasesTheRequestGuardAndRepeatedCursorsDoNotLoop() =
        runTest {
            var attempts = 0
            val pager =
                HomeChipFeedPager(
                    loadFirst = {
                        if (attempts++ == 0) throw CancellationException("reset")
                        HomePageData(continuation = "same")
                    },
                    loadContinuation = { _, _ -> HomePageData(continuation = "same") },
                )
            val initial = HomeUiState(chips = listOf(focus))
            assertFailsWith<CancellationException> { pager.loadNext(initial) }
            pager.reset()
            val first = assertNotNull(pager.loadNext(initial))
            val last = assertNotNull(pager.loadNext(first))
            assertNull(last.homePaginationKey)
            assertNull(pager.loadNext(last))
        }

    @Test
    fun anEmptyOrDeduplicatedPageNeedsMoreScrollingBeforeAnotherAutomaticRequest() {
        val trigger = HomePaginationTrigger()
        val first = HomePaginationKey(null, null, 0)
        val second = HomePaginationKey(null, "next", 1)
        assertTrue(trigger.shouldLoad(first, 1, isLoading = false))
        assertFalse(trigger.shouldLoad(null, 2, isLoading = true))
        assertFalse(trigger.shouldLoad(second, 2, isLoading = false))
        repeat(10) { assertFalse(trigger.shouldLoad(second, 2, isLoading = false)) }
        assertTrue(trigger.shouldLoad(second, 3, isLoading = false))
        assertFalse(trigger.shouldLoad(second, 4, isLoading = false))
        assertTrue(HomePaginationTrigger().shouldLoad(first, 0, isLoading = false))
    }

    @Test
    fun sharedPaginationWaitsForVisibleContentAndDoesNotRetryFailuresAutomatically() {
        val state = HomeUiState(chips = listOf(focus))
        val trigger = HomePaginationTrigger()
        assertFalse(trigger.shouldLoad(state, nearEnd = false, scrollRevision = 0))
        val busyStates =
            listOf(
                state.copy(isLoading = true),
                state.copy(isRefreshing = true),
                state.copy(isRestoringCache = true),
                state.copy(isRevalidating = true),
                state.copy(isContentLoading = true),
                state.copy(isLoadingMore = true),
                state.copy(loadMoreError = true),
            )
        busyStates.forEach { assertFalse(trigger.shouldLoad(it, nearEnd = true, scrollRevision = 0)) }
        assertTrue(trigger.shouldLoad(state, nearEnd = true, scrollRevision = 0))
        assertFalse(trigger.shouldLoad(state.copy(loadMoreError = true), nearEnd = true, scrollRevision = 1))
        assertFalse(trigger.shouldLoad(state, nearEnd = true, scrollRevision = 2))
    }

    @Test
    fun sharedPaginationContinuesTheMainFeedThenTheChipAndWaitsForMoreScrolling() {
        val trigger = HomePaginationTrigger()
        val main = HomeUiState(chips = listOf(focus), continuation = "main")
        assertTrue(trigger.shouldLoad(main, nearEnd = true, scrollRevision = 0))
        assertFalse(trigger.shouldLoad(main.copy(isLoadingMore = true), nearEnd = true, scrollRevision = 1))
        val terminal = main.copy(continuation = null)
        assertFalse(trigger.shouldLoad(terminal, nearEnd = true, scrollRevision = 1))
        assertTrue(trigger.shouldLoad(terminal, nearEnd = true, scrollRevision = 2))
        val appended = terminal.withChipFeed(focus, listOf(shelf("Extra", "extra")), "chip-next")
        assertFalse(trigger.shouldLoad(appended, nearEnd = true, scrollRevision = 2))
        assertTrue(trigger.shouldLoad(appended, nearEnd = true, scrollRevision = 3))
        val completed = appended.withChipFeed(focus, listOf(shelf("Last", "last")))
        assertFalse(trigger.shouldLoad(completed, nearEnd = true, scrollRevision = 4))
        assertFalse(trigger.shouldLoad(completed.copy(selectedChip = focus), nearEnd = true, scrollRevision = 5))
        assertTrue(HomePaginationTrigger().shouldLoad(completed.withoutChipFeeds(), nearEnd = true, scrollRevision = 0))
    }

    @Test
    fun resetClearsAppendedContentAndUsedChipsWithoutChangingTheMainFeed() {
        val state = HomeUiState(data = listOf(shelf("Main", "main")), chips = listOf(focus))
        val appended = state.withChipFeed(focus, listOf(shelf("Extra", "extra")))
        assertEquals(listOf("main", "extra"), appended.loadedHomeSongs().map { it.id })
        assertEquals(state.copy(chipFeedGeneration = 1), appended.withoutChipFeeds())
        assertEquals(state, homeCacheJson.decodeFromString<HomeUiState>(homeCacheJson.encodeToString(appended)))
        assertEquals(focus, appended.withoutChipFeeds().nextHomeChip())
    }

    @Test
    fun chipFeedsFollowTerminalMainMoodsWithoutChangingTheCompletedMainFeed() =
        runTest {
            val main = shelf("Main", "main")
            val moods =
                HomeSection(
                    "Moods and genres",
                    listOf(BrowseItem("FEmusic_moods_and_genres_category", "Focus", params = "focus")),
                    browseId = "FEmusic_moods_and_genres",
                )
            val state = HomeUiState(data = listOf(main), chips = listOf(focus), continuation = "main-next")
            assertNull(state.nextHomeChip())
            val completed =
                state.data.orEmpty().appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = null,
                    loadExplore = { listOf(moods) },
                )
            val terminal = state.copy(data = completed, continuation = null)
            val appended = terminal.withChipFeed(assertNotNull(terminal.nextHomeChip()), listOf(moods, shelf("Extra", "extra")))
            assertEquals(listOf(main, moods), appended.data)
            assertEquals(listOf(shelf("Extra", "extra")), appended.chipFeeds.single().sections)
            assertNull(appended.nextHomeChip())
        }

    @Test
    fun appendedContentUsesTheExistingExplicitAndPodcastFilters() {
        Settings.shared.hideExplicit = true
        Settings.shared.hidePodcasts = true
        Settings.shared.setSongBlocked("blocked", true)
        val explicit =
            shelf(
                "Explicit",
                "explicit",
            ).let { it.copy(items = listOf((it.items.single() as SongItem).copy(isExplicit = true))) }
        val state =
            HomeUiState(chips = listOf(focus, workout))
                .withChipFeed(focus, listOf(explicit, shelf("Blocked", "blocked"), shelf("Allowed", "allowed")))
                .withChipFeed(Chip("Podcasts", browseId = "FEmusic_podcasts"), listOf(shelf("Shows", "show")))
                .withoutBlockedContent()
        assertEquals(
            listOf("Allowed"),
            state.chipFeeds
                .flatMap { it.sections }
                .map { it.title },
        )
        assertEquals(2, state.usedHomeChips.size)
        assertTrue(state.hasHomeFeedContent())
    }

    private fun shelf(
        title: String,
        vararg ids: String,
    ) = HomeSection(title, ids.map { SongItem(it, it, null, emptyList(), null) })
}

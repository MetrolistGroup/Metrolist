package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.pauseSearchHistory = true
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun failedSearchIsDistinctFromSuccessfulEmptySearchAndCanBeRetried() =
        runTest {
            val fail = MutableStateFlow(true)
            val client =
                HttpClient(
                    mockEngine {
                        respond(
                            "{}",
                            if (fail.value) HttpStatusCode.TooManyRequests else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                viewModel.searchQuery("query")
                val failed = viewModel.uiState.first { it.searchFailed && !it.isSearching }
                assertTrue(failed.searchFailed)
                assertTrue(
                    failed.searchSummary
                        ?.summaries
                        .orEmpty()
                        .isEmpty(),
                )
                fail.value = false
                viewModel.searchQuery("query")
                val retried = viewModel.uiState.first { !it.searchFailed && !it.isSearching }
                assertTrue(retried.searchSummary!!.summaries.isEmpty())
                viewModel.close()
            } finally {
                client.close()
            }
        }

    @Test
    fun suggestionFailureKeepsTypedQueryInSuggestionsPresentation() =
        runTest {
            val client =
                HttpClient(
                    mockEngine { throw IllegalStateException("offline") },
                )
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                assertNull(repository.getSearchSuggestions("query"))
                val viewModel = SearchViewModel(backgroundScope, repository)
                viewModel.onQueryChange("query")
                advanceTimeBy(301)
                runCurrent()
                val state = viewModel.uiState.value
                assertEquals(
                    SearchPresentation.SUGGESTIONS,
                    searchPresentation(
                        state.query,
                        state.isQuerySubmitted,
                    ),
                )
                assertNull(state.searchSuggestions)
            } finally {
                client.close()
            }
        }

    @Test
    fun failedContinuationPreservesCursorAndItemsForExplicitRetry() =
        runTest {
            val fail = MutableStateFlow(true)
            val client =
                HttpClient(
                    mockEngine { request ->
                        val continuation = request.url.parameters["continuation"] == "cursor"
                        if (continuation && fail.value) {
                            respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            val id = if (continuation) "two" else "one"
                            val tracks = """[{"musicResponsiveListItemRenderer":{
                        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}],
                        "playlistItemData":{"videoId":"$id"}
                    }}]"""
                            val response =
                                if (continuation) {
                                    """{"continuationContents":{"musicShelfContinuation":{"contents":$tracks}}}"""
                                } else {
                                    """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
                            "title":{"runs":[{"text":"Songs"}]}, "contents":$tracks,
                            "continuations":[{"nextContinuationData":{"continuation":"cursor"}}]
                        }}]}}}"""
                                }
                            respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                viewModel.searchQuery("query")
                val summary = viewModel.uiState.first { it.searchSummary != null && !it.isSearching }.searchSummary!!
                assertFalse(summary.summaries.first().isTopResult)
                viewModel.searchFiltered(SearchFilter.SONG)
                viewModel.uiState.first { it.filteredResults?.continuation == "cursor" && !it.isSearching }
                viewModel.loadMoreFilteredResults()
                val failed = viewModel.uiState.first { it.loadMoreFailed && !it.isLoadingMore }
                assertEquals("cursor", failed.filteredResults!!.continuation)
                assertEquals(listOf("one"), failed.filteredResults.items.map { it.id })
                val failedRequests = (client.engine as MockEngine).requestHistory.size
                viewModel.loadMoreFilteredResults()
                runCurrent()
                assertEquals(failedRequests, (client.engine as MockEngine).requestHistory.size)
                fail.value = false
                viewModel.retryLoadMoreFilteredResults()
                val retried = viewModel.uiState.first { it.filteredResults?.items?.size == 2 && !it.isLoadingMore }
                assertFalse(retried.loadMoreFailed)
                assertNull(retried.filteredResults!!.continuation)
                assertEquals(listOf("one", "two"), retried.filteredResults.items.map { it.id })
                assertEquals(failedRequests + 1, (client.engine as MockEngine).requestHistory.size)
                viewModel.close()
            } finally {
                client.close()
            }
        }

    @Test
    fun clearHistoryPreservesEntriesWhenRemoteReadFails() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("keep me"))
            val client =
                HttpClient(
                    mockEngine { throw IllegalStateException("offline") },
                )
            try {
                val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(innerTube))
                viewModel.clearSearchHistory()
                runCurrent()
                assertEquals(listOf("keep me"), viewModel.uiState.value.searchHistory)
                assertEquals(listOf("keep me"), SearchHistoryCodec.decode(Settings.shared.searchHistory))
            } finally {
                client.close()
            }
        }

    @Test
    fun failedAccountClearPreservesHistoryAndReportsFailure() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("local"))
            val engine =
                mockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val tube = InnerTube(client, retryDelay = {})
                tube.cookie = "SAPISID=session"
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                viewModel.clearSearchHistory()
                viewModel.uiState.first { it.historyMutationFailures > 0 }
                assertEquals(listOf("local"), viewModel.uiState.value.searchHistory)
                assertEquals(listOf("local"), SearchHistoryCodec.decode(Settings.shared.searchHistory))
            } finally {
                client.close()
            }
        }

    @Test
    fun editingQueryRetainsSuggestionsUntilReplacementArrives() =
        runTest {
            val replacementStarted = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    mockEngine { request ->
                        val query =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["input"]!!
                                .jsonPrimitive.content
                        if (query == "second") {
                            replacementStarted.complete(Unit)
                            releaseReplacement.await()
                        }
                        if (query == "offline") error("offline")
                        respond(
                            """{"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
                                {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"$query result"}]}}}
                            ]}}]}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                viewModel.onQueryChange("first")
                advanceTimeBy(300)
                val first = viewModel.uiState.first { it.searchSuggestions != null }.searchSuggestions
                assertEquals(listOf("first result"), first?.queries)
                val states = mutableListOf<SearchViewModel.SearchUiState>()
                val collector =
                    backgroundScope.launch(
                        UnconfinedTestDispatcher(testScheduler),
                    ) { viewModel.uiState.collect { states += it } }
                viewModel.onQueryChange("second")
                assertEquals(first, viewModel.uiState.value.searchSuggestions)
                advanceTimeBy(299)
                assertEquals(first, viewModel.uiState.value.searchSuggestions)
                advanceTimeBy(1)
                replacementStarted.await()
                assertEquals(first, viewModel.uiState.value.searchSuggestions)
                releaseReplacement.complete(Unit)
                val second = viewModel.uiState.first { it.searchSuggestions?.queries == listOf("second result") }.searchSuggestions
                viewModel.onQueryChange("offline")
                advanceTimeBy(301)
                runCurrent()
                assertEquals(second, viewModel.uiState.value.searchSuggestions)
                collector.cancel()
                assertTrue(states.isNotEmpty())
                assertTrue(
                    states.all {
                        searchPresentation(it.query, it.isQuerySubmitted) == SearchPresentation.SUGGESTIONS
                    },
                )

                viewModel.onQueryChange("s")
                assertNull(viewModel.uiState.value.searchSuggestions)
                assertEquals(SearchPresentation.SUGGESTIONS, searchPresentation("s", false))
            } finally {
                releaseReplacement.complete(Unit)
                client.close()
            }
        }

    @Test
    fun failedSearchIsDistinctFromAnEmptySearchAndCanRetryTheFilter() =
        runTest {
            var fail = true
            val client =
                HttpClient(
                    mockEngine {
                        if (fail) error("Unavailable")
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                viewModel.searchQuery("query")
                viewModel.uiState.first { it.searchFailed && !it.isSearching }
                viewModel.searchFiltered(SearchFilter.SONG)
                viewModel.uiState.first { it.searchFailed && it.selectedFilter == SearchFilter.SONG && !it.isSearching }
                fail = false
                viewModel.searchFiltered(SearchFilter.SONG)
                val retried = viewModel.uiState.first { !it.searchFailed && !it.isSearching }
                assertEquals(SearchFilter.SONG, retried.selectedFilter)
                assertEquals(emptyList(), retried.filteredResults?.items)
                viewModel.clearFilter()
                val summary =
                    viewModel.uiState.first {
                        it.selectedFilter == null && !it.isSearching &&
                            it.searchSummary?.onlineSearchFailed == false
                    }
                assertFalse(summary.searchFailed)
            } finally {
                client.close()
            }
        }

    @Test
    fun accountHistoryNeverOverwritesDeviceSearches() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("device search"))
            val remote =
                (1..10).joinToString(",") { index ->
                    """{"historySuggestionRenderer":{"suggestion":{"runs":[{"text":"remote $index"}]},"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"token-$index"}}}}"""
                }
            var response = """{"contents":[$remote]}"""
            val client =
                HttpClient(
                    mockEngine { respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json")) },
                ) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            try {
                val tube = InnerTube(client, retryDelay = {})
                tube.cookie = "SAPISID=session"
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                viewModel.refreshSearchHistory()
                viewModel.uiState.first { it.remoteSearchHistoryFeedbackTokens.size == 10 }
                assertEquals(listOf("device search"), SearchHistoryCodec.decode(Settings.shared.searchHistory))
                response = "{}"
                viewModel.refreshSearchHistory()
                val refreshed = viewModel.uiState.first { it.remoteSearchHistoryFeedbackTokens.isEmpty() }
                assertEquals(listOf("device search"), refreshed.searchHistory)
                assertEquals(listOf("device search"), SearchHistoryCodec.decode(Settings.shared.searchHistory))
            } finally {
                client.close()
            }
        }

    @Test
    fun deletingNewSearchResolvesItsRemoteToken() =
        runTest {
            Settings.shared.pauseSearchHistory = false
            val engine =
                mockEngine { request ->
                    respond(
                        if (request.url.encodedPath.endsWith("get_search_suggestions")) historyResponse("new query", "new-token") else "{}",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val model = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                model.searchQuery("new query")
                model.uiState.first { it.searchSummary != null && !it.isSearching }
                assertTrue(
                    model.uiState.value.remoteSearchHistoryFeedbackTokens
                        .isEmpty(),
                )
                model.removeSearchHistoryItem("new query")
                model.uiState.first { it.searchHistory.isEmpty() }
                val feedback = engine.requestHistory.single { it.url.encodedPath.endsWith("feedback") }
                assertTrue((feedback.body as TextContent).text.contains("new-token"))
                assertEquals(emptyList(), SearchHistoryCodec.decode(Settings.shared.searchHistory))
            } finally {
                client.close()
            }
        }

    @Test
    fun signedOutDeletionIsImmediateAndDoesNotReadRemoteHistory() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("local"))
            val client = HttpClient(mockEngine { error("Signed-out deletion must stay local") })
            try {
                val model = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client)))
                model.removeSearchHistoryItem("local")
                assertTrue(
                    model.uiState.value.searchHistory
                        .isEmpty(),
                )
                assertEquals(emptyList(), SearchHistoryCodec.decode(Settings.shared.searchHistory))
            } finally {
                client.close()
            }
        }

    @Test
    fun missingTokenReadFailureKeepsSearchAndReportsFailure() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("keep"))
            val client =
                HttpClient(
                    mockEngine {
                        respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val model = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                model.removeSearchHistoryItem("keep")
                model.uiState.first { it.historyMutationFailures > 0 }
                assertEquals(listOf("keep"), model.uiState.value.searchHistory)
                assertEquals(listOf("keep"), SearchHistoryCodec.decode(Settings.shared.searchHistory))
            } finally {
                client.close()
            }
        }

    @Test
    fun resubmittingDuringTokenResolutionKeepsTheNewSearch() =
        runTest {
            Settings.shared.pauseSearchHistory = false
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val engine =
                mockEngine { request ->
                    if (request.url.encodedPath.endsWith("get_search_suggestions")) {
                        started.complete(Unit)
                        release.await()
                    }
                    respond(historyResponse("query", "old-token"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val model = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                model.addSearchHistory("query")
                model.removeSearchHistoryItem("query")
                started.await()
                model.searchQuery("query")
                release.complete(Unit)
                model.uiState.first { it.searchSummary != null && !it.isSearching }
                assertEquals(listOf("query"), model.uiState.value.searchHistory)
                assertTrue(engine.requestHistory.none { it.url.encodedPath.endsWith("feedback") })
            } finally {
                release.complete(Unit)
                client.close()
            }
        }

    @Test
    fun logoutClearsRemoteHistoryAndKeepsDeviceHistory() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("device"))
            val client =
                HttpClient(
                    mockEngine {
                        respond(historyResponse("remote", "token"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val model = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                model.refreshSearchHistory()
                model.uiState.first { "remote" in it.searchHistory }
                tube.cookie = null
                runCurrent()
                assertEquals(listOf("device"), model.uiState.value.searchHistory)
                assertTrue(
                    model.uiState.value.remoteSearchHistoryFeedbackTokens
                        .isEmpty(),
                )
                model.refreshSearchHistory()
                runCurrent()
                assertEquals(listOf("device"), model.uiState.value.searchHistory)
            } finally {
                client.close()
            }
        }

    @Test
    fun oldAccountHistoryResponseCannotPublishAfterSwitch() =
        runTest {
            Settings.shared.searchHistory = SearchHistoryCodec.encode(listOf("device"))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    mockEngine {
                        withContext(NonCancellable) {
                            started.complete(Unit)
                            release.await()
                        }
                        respond(
                            historyResponse("old account", "old-token"),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=first" }
                val model = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                model.refreshSearchHistory()
                started.await()
                tube.cookie = "SAPISID=second"
                runCurrent()
                release.complete(Unit)
                runCurrent()
                assertEquals(listOf("device"), model.uiState.value.searchHistory)
                assertTrue(
                    model.uiState.value.remoteSearchHistoryFeedbackTokens
                        .isEmpty(),
                )
            } finally {
                release.complete(Unit)
                client.close()
            }
        }

    private fun historyResponse(
        query: String,
        token: String,
    ): String =
        """{
          "historySuggestionRenderer": {
            "suggestion": {"runs": [{"text": "$query"}]},
            "serviceEndpoint": {"feedbackEndpoint": {"feedbackToken": "$token"}}
          }
        }"""

    @Test
    fun successfulHistoryRemovalLoadsOlderEntriesAndIgnoresStaleDeletedQueries() =
        runTest {
            val initialHistory = (1..10).map { historyResponse("query $it", "token-$it") }
            var deleted = false
            var historyAfterDeletion =
                listOf(historyResponse("QUERY 1", "stale-token")) + initialHistory.drop(1) + historyResponse("older", "older-token")
            val engine =
                mockEngine { request ->
                    val response =
                        if (request.url.encodedPath.endsWith("feedback")) {
                            deleted = true
                            "{}"
                        } else {
                            val entries =
                                if (deleted) {
                                    historyAfterDeletion
                                } else {
                                    initialHistory
                                }
                            """{"contents":[${entries.joinToString(",")}]}"""
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val model = SearchViewModel(backgroundScope, YouTubeRepository(tube))
                model.refreshSearchHistory()
                model.uiState.first { it.searchHistory.size == 10 }

                model.removeSearchHistoryItem("query 1")

                val refreshed = model.uiState.first { "older" in it.searchHistory }
                assertEquals((2..10).map { "query $it" } + "older", refreshed.searchHistory)
                assertFalse("QUERY 1" in refreshed.remoteSearchHistoryFeedbackTokens)
                assertEquals(1, engine.requestHistory.count { it.url.encodedPath.endsWith("feedback") })

                historyAfterDeletion = listOf(historyResponse("acknowledged", "acknowledged-token"))
                model.refreshSearchHistory()
                model.uiState.first { "acknowledged" in it.searchHistory }
                historyAfterDeletion = listOf(historyResponse("QUERY 1", "new-token"))
                model.refreshSearchHistory()
                model.uiState.first { "QUERY 1" in it.remoteSearchHistoryFeedbackTokens }
            } finally {
                client.close()
            }
        }

    @Test
    fun typingLoadsOnlySuggestionsAndSubmittingLoadsOneSummary() =
        runTest {
            val engine =
                mockEngine { respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                viewModel.onQueryChange("partial")
                advanceTimeBy(200)
                viewModel.onQueryChange("complete")
                advanceTimeBy(300)
                val suggestions = viewModel.uiState.first { it.searchSuggestions != null }
                runCurrent()
                assertFalse(suggestions.isQuerySubmitted)
                assertNull(suggestions.searchSummary)
                assertEquals(listOf("get_search_suggestions"), engine.requestHistory.map { it.url.encodedPath.substringAfterLast('/') })

                viewModel.searchQuery("complete")
                val submitted = viewModel.uiState.first { it.searchSummary != null && !it.isSearching }
                assertTrue(submitted.isQuerySubmitted)
                assertNull(submitted.searchSuggestions)
                assertEquals(
                    listOf("get_search_suggestions", "search"),
                    engine.requestHistory.map { it.url.encodedPath.substringAfterLast('/') },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun editingSubmittedResultsKeepsSuggestionsPresentationUntilTheLatestResponse() =
        runTest {
            val replacementStarted = CompletableDeferred<Unit>()
            val releaseReplacement = CompletableDeferred<Unit>()
            val engine =
                mockEngine { request ->
                    if (request.url.encodedPath.endsWith("get_search_suggestions")) {
                        replacementStarted.complete(Unit)
                        releaseReplacement.await()
                        respond(
                            """{"contents":[{"searchSuggestionsSectionRenderer":{"contents":[
                                {"searchSuggestionRenderer":{"suggestion":{"runs":[{"text":"query longer suggestion"}]}}}
                            ]}}]}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    } else {
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client, retryDelay = {})))
                viewModel.searchQuery("query")
                viewModel.uiState.first { it.searchSummary != null && !it.isSearching }
                val states = mutableListOf<SearchViewModel.SearchUiState>()
                val collector =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        viewModel.uiState.collect { states += it }
                    }

                viewModel.onQueryChange("query long")
                advanceTimeBy(200)
                viewModel.onQueryChange("query longer")
                advanceTimeBy(299)
                assertEquals(1, engine.requestHistory.size)
                advanceTimeBy(1)
                replacementStarted.await()
                releaseReplacement.complete(Unit)
                viewModel.uiState.first { it.searchSuggestions?.queries == listOf("query longer suggestion") }
                collector.cancel()

                assertTrue(
                    states.none {
                        searchPresentation(it.query, it.isQuerySubmitted) ==
                            SearchPresentation.IDLE
                    },
                )
                assertFalse(viewModel.uiState.value.isQuerySubmitted)
                assertEquals(
                    listOf("search", "get_search_suggestions"),
                    engine.requestHistory.map { it.url.encodedPath.substringAfterLast('/') },
                )
                assertTrue(Settings.shared.searchHistory.isBlank())

                viewModel.onQueryChange("")
                advanceTimeBy(500)
                runCurrent()
                val cleared = viewModel.uiState.value
                assertEquals(SearchPresentation.IDLE, searchPresentation(cleared.query, cleared.isQuerySubmitted))
                assertNull(cleared.searchSuggestions)
                assertEquals(2, engine.requestHistory.size)
            } finally {
                releaseReplacement.complete(Unit)
                client.close()
            }
        }

    @Test
    fun recentSongsRespectBlocksBeforeAndAfterRestoringHistory() =
        runTest {
            val client =
                HttpClient(
                    mockEngine { error("Recent songs must not fetch the network") },
                )
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val blocked = SongItem("blocked", "Blocked", null, emptyList(), null)
                val clean = blocked.copy(id = "clean", title = "Clean")
                val viewModel = SearchViewModel(backgroundScope, repository)
                runCurrent()
                viewModel.addPlayedSearchSong(blocked)
                viewModel.addPlayedSearchSong(clean)
                val persisted = Settings.shared.recentPlayedSearchSongsJson

                BlockedContent.rememberDislikedSong(blocked)
                runCurrent()
                assertEquals(listOf(clean), viewModel.uiState.value.recentSearchItems)
                viewModel.close()

                val restored = SearchViewModel(backgroundScope, repository)
                runCurrent()
                assertEquals(listOf(clean), restored.uiState.value.recentSearchItems)
                BlockedContent.unblockSong(blocked.id)
                runCurrent()
                assertEquals(listOf(clean, blocked), restored.uiState.value.recentSearchItems)
                assertEquals(persisted, Settings.shared.recentPlayedSearchSongsJson)
            } finally {
                client.close()
            }
        }

    @Test
    fun editingVisibleRecentSongsPreservesHiddenHistoryUntilExplicitlyCleared() =
        runTest {
            val client =
                HttpClient(
                    mockEngine { error("Recent songs must not fetch the network") },
                )
            try {
                val viewModel = SearchViewModel(backgroundScope, YouTubeRepository(InnerTube(client)))
                val blocked = SongItem("blocked", "Blocked", null, emptyList(), null)
                val clean = blocked.copy(id = "clean", title = "Clean")
                runCurrent()
                viewModel.addPlayedSearchSong(blocked)
                BlockedContent.rememberDislikedSong(blocked)
                runCurrent()
                viewModel.addPlayedSearchSong(clean)
                assertEquals(listOf(clean), viewModel.uiState.value.recentSearchItems)
                viewModel.removeRecentPlayedSearchSong(clean)
                assertTrue(
                    viewModel.uiState.value.recentSearchItems
                        .isEmpty(),
                )

                BlockedContent.unblockSong(blocked.id)
                runCurrent()
                assertEquals(listOf(blocked), viewModel.uiState.value.recentSearchItems)
                BlockedContent.rememberDislikedSong(blocked)
                runCurrent()
                viewModel.clearRecentPlayedSearchSongs()
                BlockedContent.unblockSong(blocked.id)
                runCurrent()
                assertTrue(
                    viewModel.uiState.value.recentSearchItems
                        .isEmpty(),
                )
                assertTrue(Settings.shared.recentPlayedSearchSongsJson.isBlank())
            } finally {
                client.close()
            }
        }

    private fun TestScope.mockEngine(handler: MockRequestHandler) =
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            },
        )
}

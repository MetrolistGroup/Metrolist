package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseConcurrencyTest {
    @Test
    fun nestedBrowseAndHomeShelvesKeepOrderWithThreeActiveRequests() =
        runTest {
            val oldDispatcher = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                for (home in listOf(false, true)) {
                    var active = 0
                    var peak = 0
                    val requests = mutableListOf<String>()
                    val engine =
                        MockEngine(
                            MockEngineConfig().apply {
                                dispatcher = StandardTestDispatcher(testScheduler)
                                addHandler { request ->
                                    val token =
                                        Json
                                            .parseToJsonElement(
                                                (request.body as TextContent).text,
                                            ).jsonObject["continuation"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                    if (token == null || token == "home") {
                                        respond(page(home), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                                    } else {
                                        requests += token
                                        active++
                                        peak = maxOf(peak, active)
                                        try {
                                            delay(if (token == "shelf-0") 300 else 100)
                                            respond(shelf(token), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                                        } finally {
                                            active--
                                        }
                                    }
                                }
                            },
                        )
                    HttpClient(engine) { install(ContentNegotiation) { json() } }.use { client ->
                        val repository = YouTubeRepository(InnerTube(client))
                        val started = testScheduler.currentTime
                        val sections =
                            if (home) {
                                repository.getHomeSectionsContinuation("home").first
                            } else {
                                repository.getBrowsePageData("browse").sections
                            }
                        assertEquals(3, peak, "Continuation requests: $requests")
                        assertEquals(300L, testScheduler.currentTime - started)
                        assertEquals((0..4).map { "shelf-$it" }, requests)
                        assertEquals((0..4).map { "Shelf $it" }, sections.map { it.title })
                        assertEquals((0..4).map { listOf("shelf-$it") }, sections.map { it.items.map { item -> item.id } })
                    }
                }
            } finally {
                responseDecodeDispatcher = oldDispatcher
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun failedShelfKeepsCursorAndCancellationStopsAllChildren() =
        runTest {
            val oldDispatcher = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            Settings.replaceSharedForTest(Settings(MapSettings()))
            var cancelling = false
            var active = 0
            try {
                val engine =
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler { request ->
                                val token =
                                    Json
                                        .parseToJsonElement(
                                            (request.body as TextContent).text,
                                        ).jsonObject["continuation"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                if (token == null) {
                                    respond(page(false), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                                } else {
                                    active++
                                    try {
                                        delay(if (cancelling) 10_000 else 100)
                                        respond(
                                            if (token == "shelf-0") "{}" else shelf(token),
                                            if (token == "shelf-0") HttpStatusCode.BadRequest else HttpStatusCode.OK,
                                            headersOf(HttpHeaders.ContentType, "application/json"),
                                        )
                                    } finally {
                                        active--
                                    }
                                }
                            }
                        },
                    )
                HttpClient(engine) { install(ContentNegotiation) { json() } }.use { client ->
                    val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                    val sections = repository.getBrowsePageData("browse").sections
                    assertEquals("shelf-0", sections.first().continuation)
                    assertTrue(sections.first().items.isEmpty())
                    assertEquals((1..4).map { "shelf-$it" }, sections.drop(1).flatMap { it.items }.map { it.id })
                    cancelling = true
                    val pending = async { repository.getBrowsePageData("browse") }
                    runCurrent()
                    assertEquals(3, active)
                    pending.cancelAndJoin()
                    runCurrent()
                    assertEquals(0, active)
                    assertTrue(pending.isCancelled)
                }
            } finally {
                responseDecodeDispatcher = oldDispatcher
                Settings.replaceSharedForTest(null)
            }
        }

    private fun page(home: Boolean): String {
        val shelves =
            (0..4).joinToString(",") {
                """{"musicShelfRenderer":{"title":{"runs":[{"text":"Shelf $it"}]},"contents":[],
                "continuations":[{"nextContinuationData":{"continuation":"shelf-$it"}}]}}"""
            }
        val nested = """{"contents":[{"itemSectionRenderer":{"contents":[$shelves]}}]}"""
        return if (home) {
            """{"continuationContents":{"sectionListContinuation":$nested}}"""
        } else {
            """{"contents":{"sectionListRenderer":$nested}}"""
        }
    }

    private fun shelf(id: String) =
        """{"continuationContents":{"musicShelfContinuation":{"contents":[{
        "musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"$id"},
        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}]}
    }]}}}"""
}

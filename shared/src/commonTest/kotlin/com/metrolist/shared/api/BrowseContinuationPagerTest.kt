package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowseContinuationPagerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesShelfBeforeSectionListAndActionsInOrder() =
        runBlocking {
            val responses =
                mapOf(
                    "first" to
                        response(
                            """
                            {
                              "continuationContents": {
                                "musicShelfContinuation": {
                                  "contents": [{"id":"shelf"}],
                                  "continuations": [{"nextContinuationData":{"continuation":"shelf-next"}}]
                                },
                                "sectionListContinuation": {
                                  "continuations": [{"nextContinuationData":{"continuation":"section-next"}}]
                                }
                              },
                              "onResponseReceivedActions": [{
                                "appendContinuationItemsAction": {
                                  "continuationItems": [
                                    {"id":"action"},
                                    {"continuationCommand":{"token":"action-next"}}
                                  ]
                                }
                              }],
                              "onResponseReceivedCommands": [{
                                "reloadContinuationItemsCommand": {"continuationItems":[{"id":"command"}]}
                              }],
                              "onResponseReceivedEndpoints": [{
                                "appendContinuationItemsCommand": {"continuationItems":[{"id":"endpoint"}]}
                              }]
                            }
                            """.trimIndent(),
                        ),
                    "action-next" to shelfResponse("last"),
                )

            val result = pager(responses).collect(" first ", maxPages = 4, setLogin = false)

            assertEquals(listOf("shelf", "action", "command", "endpoint", "last"), result)
        }

    @Test
    fun stopsOnCycleAndMaximum() =
        runBlocking {
            val calls = mutableListOf<String>()
            val result =
                BrowseContinuationPager(
                    pageSource =
                        BrowseContinuationPageSource { token, _ ->
                            calls += token
                            shelfResponse(id = token, next = token)
                        },
                    itemParser = { it["id"]?.jsonPrimitive?.content },
                ).collect("start", maxPages = 2, setLogin = true)

            assertEquals(listOf("start"), result)
            assertEquals(listOf("start"), calls)

            val maxCalls = mutableListOf<String>()
            val maxResult =
                BrowseContinuationPager(
                    pageSource =
                        BrowseContinuationPageSource { token, _ ->
                            maxCalls += token
                            shelfResponse(id = token, next = "$token next")
                        },
                    itemParser = { it["id"]?.jsonPrimitive?.content },
                ).collect("start", maxPages = 2, setLogin = true)

            assertEquals(listOf("start", "start next"), maxCalls)
            assertEquals(listOf("start", "start next"), maxResult)
        }

    @Test
    fun boundedCollectionRetainsNextCursorAndFailureCanRetry() =
        runBlocking {
            val responses = mapOf("one" to shelfResponse("first", "two"), "two" to shelfResponse("second", "three"))
            val bounded = pager(responses).collectPage("one", maxPages = 1, setLogin = true)
            assertEquals(listOf("first"), bounded.items)
            assertEquals("two", bounded.continuation)
            val partial = pager(responses).collectPage("one", maxPages = 4, setLogin = true)
            assertEquals(listOf("first", "second"), partial.items)
            assertEquals("three", partial.continuation)
        }

    @Test
    fun cycleAtThePageLimitDoesNotExposeAnAlreadyConsumedCursor() =
        runBlocking {
            val page = pager(mapOf("one" to shelfResponse("first", "one"))).collectPage("one", maxPages = 1, setLogin = true)
            assertEquals(listOf("first"), page.items)
            assertEquals(null, page.continuation)
            assertTrue(page.continuationAmbiguous)
        }

    @Test
    fun keepsItemsWhenAnOrdinaryPageFails() =
        runBlocking {
            val result =
                BrowseContinuationPager(
                    pageSource =
                        BrowseContinuationPageSource { token, _ ->
                            if (token == "bad") error("network")
                            shelfResponse(id = "good", next = "bad")
                        },
                    itemParser = { it["id"]?.jsonPrimitive?.content },
                ).collect("first", maxPages = 4, setLogin = false)

            assertEquals(listOf("good"), result)
        }

    @Test
    fun propagatesCancellation() {
        assertFailsWith<CancellationException> {
            runBlocking {
                BrowseContinuationPager<String>(
                    pageSource = BrowseContinuationPageSource { _, _ -> throw CancellationException("cancelled") },
                    itemParser = { null },
                ).collect("first", maxPages = 4, setLogin = true)
            }
        }
    }

    @Test
    fun normalizesInitialAndNextTokensAndPreservesActionTokenPrecedence() =
        runBlocking {
            val calls = mutableListOf<String>()
            val result =
                BrowseContinuationPager(
                    pageSource =
                        BrowseContinuationPageSource { token, _ ->
                            calls += token
                            if (token == "first") actionResponse() else shelfResponse("done")
                        },
                    itemParser = { it["id"]?.jsonPrimitive?.content },
                ).collect(" first ", maxPages = 4, setLogin = false)

            assertEquals(listOf("first", "next"), calls)
            assertEquals(listOf("item", "done"), result)
        }

    private fun pager(responses: Map<String, BrowseResponse>) =
        BrowseContinuationPager(
            pageSource = BrowseContinuationPageSource { token, _ -> responses.getValue(token) },
            itemParser = { it["id"]?.jsonPrimitive?.content },
        )

    private fun response(value: String): BrowseResponse = json.decodeFromString(value)

    private fun shelfResponse(
        id: String,
        next: String? = null,
    ): BrowseResponse =
        BrowseResponse(
            continuationContents =
                BrowseResponse.ContinuationContents(
                    musicShelfContinuation =
                        buildJsonObject {
                            put("contents", buildJsonArray { add(buildJsonObject { put("id", id) }) })
                            next?.let { token ->
                                put(
                                    "continuations",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "nextContinuationData",
                                                    buildJsonObject { put("continuation", token) },
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        },
                ),
        )

    private fun actionResponse(): BrowseResponse =
        response(
            """
            {
              "onResponseReceivedActions": [{
                "appendContinuationItemsAction": {
                  "continuationItems": [
                    {"id":"item"},
                    {"continuationCommand":{"token":" next "}}
                  ]
                }
              }],
              "continuationContents": {
                "sectionListContinuation": {
                  "continuations": [{"nextContinuationData":{"continuation":"shelf-token"}}]
                }
              }
            }
            """.trimIndent(),
        )
}

package com.metrolist.shared.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinuationPagerTest {
    @Test
    fun branchCancellationStopsTheBatchAndDoesNotStartPendingRequests() =
        runTest {
            var started = 0
            var active = 0
            assertFailsWith<CancellationException> {
                collectPageBranches(
                    BranchingParsedPage(emptyList<Int>(), (0 until 20).map { ContinuationBranch(it.toString()) }.toSet()),
                    maxRequests = Int.MAX_VALUE,
                ) { branch ->
                    started++
                    active++
                    try {
                        if (branch.token == "0") {
                            delay(1)
                            throw CancellationException("cancelled")
                        }
                        delay(100)
                        BranchingParsedPage(emptyList(), emptySet())
                    } finally {
                        active--
                    }
                }
            }
            assertEquals(4, started)
            assertEquals(0, active)
        }

    @Test
    fun unlimitedBranchTraversalBoundsConcurrentRequestsAndKeepsEveryPage() =
        runTest {
            var active = 0
            var peak = 0
            val result =
                collectPageBranches(
                    BranchingParsedPage(emptyList<Int>(), (0 until 25).map { ContinuationBranch(it.toString()) }.toSet()),
                    maxRequests = Int.MAX_VALUE,
                ) { branch ->
                    active++
                    peak = maxOf(peak, active)
                    try {
                        delay(10)
                        val number = branch.token.toInt()
                        BranchingParsedPage(
                            listOf(number),
                            if (number < 25) {
                                setOf(ContinuationBranch((number + 25).toString()))
                            } else {
                                emptySet()
                            },
                        )
                    } finally {
                        active--
                    }
                }
            assertTrue(result.complete)
            assertEquals((0 until 50).toList(), result.items)
            assertEquals(4, peak)
            assertEquals(0, active)
        }

    @Test
    fun findsTokensInCommandsAndNestedShelves() {
        val json =
            Json.parseToJsonElement(
                """
                {
                  "onResponseReceivedCommands": [
                    {"appendContinuationItemsCommand": {"continuationItems": [
                      {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "next"}}}}
                    ]}}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("next", json.findContinuationToken())
    }

    @Test
    fun doesNotGuessWhenResponseContainsMultipleContinuationBranches() {
        val json =
            Json.parseToJsonElement(
                """
                {
                  "primary": {"continuationCommand": {"token": "primary-next"}},
                  "secondary": {"continuationCommand": {"token": "secondary-next"}}
                }
                """.trimIndent(),
            )

        assertEquals(null, json.findContinuationToken())
    }

    @Test
    fun ignoresSuggestionReloadTokens() {
        val json =
            Json.parseToJsonElement(
                """
                {
                  "continuations": [
                    {"reloadContinuationData": {"continuation": "refresh-suggestions"}}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(emptySet(), json.findContinuationTokens())
    }

    @Test
    fun ambiguousContinuationMarksPageIncomplete() =
        runBlocking {
            val result = collectPages(ParsedPage(listOf(1), null, continuationAmbiguous = true)) { error("unused") }

            assertEquals(listOf(1), result.items)
            assertFalse(result.complete)
        }

    @Test
    fun repeatedTokenMarksResultIncomplete() =
        runBlocking {
            val result = collectPages(ParsedPage(listOf(1), "same")) { ParsedPage(listOf(2), "same") }

            assertEquals(listOf(1, 2), result.items)
            assertFalse(result.complete)
        }

    @Test
    fun failedPageMarksResultIncompleteWithoutLosingCollectedItems() =
        runBlocking {
            val result =
                collectPages(ParsedPage(listOf(1), "next")) {
                    error("network")
                }

            assertEquals(listOf(1), result.items)
            assertFalse(result.complete)
        }

    @Test
    fun cancellationIsNeverConvertedToPartialSuccess() {
        assertFailsWith<CancellationException> {
            runBlocking {
                collectPages(ParsedPage(listOf(1), "next")) {
                    throw CancellationException("cancelled")
                }
            }
        }
    }

    @Test
    fun finalPageMarksResultComplete() =
        runBlocking {
            val result = collectPages(ParsedPage(listOf(1), "next")) { ParsedPage(listOf(2), null) }

            assertEquals(listOf(1, 2), result.items)
            assertTrue(result.complete)
        }

    @Test
    fun collectsMoreThanFiveHundredItemsInSourceOrder() =
        runBlocking {
            val firstPage = (0 until 100).toList()
            val result =
                collectPages(ParsedPage(firstPage, "page-1")) { token ->
                    val page = token.removePrefix("page-").toInt()
                    val items = (page * 100 until (page + 1) * 100).toList()
                    ParsedPage(items, if (page < 5) "page-${page + 1}" else null)
                }

            assertEquals((0 until 600).toList(), result.items)
            assertTrue(result.complete)
        }

    @Test
    fun branchRequestLimitIsIncompleteAndLongChainsDoNotOverflow() =
        runBlocking {
            val first = BranchingParsedPage(emptyList<Int>(), setOf(ContinuationBranch("0")))

            suspend fun next(branch: ContinuationBranch): BranchingParsedPage<Int> {
                val index = branch.token.toInt()
                return BranchingParsedPage(
                    listOf(index),
                    if (index < 9999) setOf(ContinuationBranch((index + 1).toString())) else emptySet(),
                )
            }
            val capped = collectPageBranches(first, maxRequests = 50, loadNext = ::next)
            assertFalse(capped.complete)
            assertEquals(50, capped.items.size)
            val complete = collectPageBranches(first, maxRequests = Int.MAX_VALUE, loadNext = ::next)
            assertTrue(complete.complete)
            assertEquals((0..9999).toList(), complete.items)
        }

    @Test
    fun collectsEveryContinuationBranch() =
        runBlocking {
            val result =
                collectPageBranches(
                    BranchingParsedPage(
                        listOf("initial"),
                        setOf(ContinuationBranch("tracks", "Tracks"), ContinuationBranch("suggestions", "Suggestions")),
                    ),
                ) { branch ->
                    when (branch.token) {
                        "tracks" -> BranchingParsedPage(listOf("track-1"), setOf(ContinuationBranch("tracks-2", branch.sectionTitle)))
                        "tracks-2" -> BranchingParsedPage(listOf("track-2"), emptySet())
                        "suggestions" -> BranchingParsedPage(listOf("suggestion"), emptySet())
                        else -> error("Unexpected token: ${branch.token}")
                    }
                }

            assertEquals(listOf("initial", "track-1", "suggestion", "track-2"), result.items)
            assertTrue(result.complete)
        }

    @Test
    fun repeatedBranchTokenMarksResultIncomplete() =
        runBlocking {
            val result =
                collectPageBranches(BranchingParsedPage(emptyList(), setOf(ContinuationBranch("first")))) {
                    BranchingParsedPage(listOf(1), setOf(ContinuationBranch("first")))
                }

            assertEquals(listOf(1), result.items)
            assertFalse(result.complete)
        }

    @Test
    fun convergingBranchesReuseAlreadyLoadedPage() =
        runBlocking {
            val result =
                collectPageBranches(
                    BranchingParsedPage(
                        emptyList(),
                        setOf(ContinuationBranch("shared"), ContinuationBranch("other")),
                    ),
                ) { branch ->
                    when (branch.token) {
                        "shared" -> BranchingParsedPage(listOf("shared"), emptySet())
                        "other" -> BranchingParsedPage(listOf("other"), setOf(ContinuationBranch("shared")))
                        else -> error("Unexpected token: ${branch.token}")
                    }
                }

            assertEquals(listOf("shared", "other"), result.items)
            assertTrue(result.complete)
        }

    @Test
    fun cycleBetweenInitialBranchesMarksResultIncomplete() =
        runBlocking {
            val result =
                collectPageBranches(
                    BranchingParsedPage(
                        emptyList(),
                        setOf(ContinuationBranch("first"), ContinuationBranch("second")),
                    ),
                ) { branch ->
                    val next = if (branch.token == "first") "second" else "first"
                    BranchingParsedPage(listOf(branch.token), setOf(ContinuationBranch(next)))
                }

            assertEquals(listOf("first", "second"), result.items)
            assertFalse(result.complete)
        }
}

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyprioRequestWorkTest {
    @Test
    fun validatesUnchangedReadyResultsOnlyOncePerSearchIncludingRejectedResults() =
        runTest {
            val mlx = MlxBuilder().line(1_000, "Already available").build()
            var requests = 0
            var validations = 0
            client {
                requests++
                respond(
                    ready(listOf(result(mlx), result("{malformed", "BAD")), complete = requests % 10 == 0),
                    HttpStatusCode.OK,
                    headersOf("X-Lyprio-Protocol", "2"),
                )
            }.use { client ->
                val provider =
                    provider(client, validate = {
                        validations++
                        MlxParser.isMlxFormat(it)
                    })
                repeat(2) {
                    val lyrics = provider.getLyrics("Song", "Artist", null)
                    assertEquals(mlx, lyrics?.lyrics)
                    assertTrue(lyrics!!.isComplete)
                }
                assertEquals(20, requests)
                assertEquals(4, validations)
            }
        }

    @Test
    fun validatesChangedResultsAndKeepsUpgradesAndStableProviderOrder() =
        runTest {
            val a = MlxBuilder().line(1_000, "First provider").build()
            val updatedA = MlxBuilder().line(1_000, "Updated provider").build()
            val b = MlxBuilder().line(1_000, "Second provider").build()
            val word = MlxBuilder().line(1_000, 2_000, words = listOf(MlxWord("Better", 1_000, 2_000))).build()
            val fixed = MlxBuilder().line(1_000, "Repaired document").build()
            val initial = listOf(result(a, "A"), result(b, "B"), result(a, "UP"), result("{malformed", "FIX"))
            val polls =
                listOf(
                    initial,
                    initial,
                    listOf(result(word, "UP", "word"), result(fixed, "FIX"), result(b, "B"), result(updatedA, "A")),
                    listOf(result(a, "A"), result(updatedA, "A"), result(a, "A"), result(b, "B"), result(a, "UP"), result(fixed, "FIX")),
                )
            val validations = mutableListOf<String>()
            var requests = 0
            client {
                val results = polls[requests++]
                respond(ready(results, requests == polls.size), HttpStatusCode.OK, headersOf("X-Lyprio-Protocol", "2"))
            }.use { client ->
                val provider =
                    provider(client, validate = {
                        validations += it
                        MlxParser.isMlxFormat(it)
                    })
                assertEquals(listOf(word, a, b, fixed), provider.getLyricsResults("Song", "Artist", null))
                assertEquals(1, validations.count { it == "{malformed" })
                assertEquals(1, validations.count { it == updatedA })
                assertEquals(1, validations.count { it == word })
            }
        }

    @Test
    fun rejectsNoticeOnlyReadyPayloadEvenAfterUsablePartial() =
        runTest {
            var requests = 0
            client {
                requests++
                val lyrics = if (requests == 1) MlxBuilder().line(1_000, "Usable partial").build() else "Lyrics not available"
                respond(ready(listOf(result(lyrics)), requests > 1), HttpStatusCode.OK, headersOf("X-Lyprio-Protocol", "2"))
            }.use { client ->
                assertFailsWith<IllegalStateException> { provider(client).getLyrics("Song", "Artist", null) }
                assertEquals(2, requests)
            }
        }

    @Test
    fun coalescesPollingButNotSubsequentRefreshAndCancelsOnlyTheWaiter() =
        runTest {
            val mlx = MlxBuilder().line(1_000, "Ready now").build()
            val polling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            client {
                requests++
                respond(ready(listOf(result(mlx)), requests > 1), HttpStatusCode.OK, headersOf("X-Lyprio-Protocol", "2"))
            }.use { client ->
                val provider =
                    provider(client, wait = {
                        polling.complete(Unit)
                        release.await()
                    })
                val owner = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                polling.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                cancelled.cancelAndJoin()
                assertFalse(waiter.isCompleted)
                assertEquals(1, requests)
                release.complete(Unit)
                assertEquals(owner.await(), waiter.await())
                assertEquals(2, requests)
                assertEquals(mlx, provider.getLyrics("Song", "Artist", null)?.lyrics)
                assertEquals(3, requests)
            }
        }

    @Test
    fun activeWaiterRetriesAfterOwnerCancellation() =
        runTest {
            val polling = CompletableDeferred<Unit>()
            val mlx = MlxBuilder().line(1_000, "Rescued request").build()
            var requests = 0
            client {
                requests++
                respond(ready(listOf(result(mlx)), requests > 1), HttpStatusCode.OK, headersOf("X-Lyprio-Protocol", "2"))
            }.use { client ->
                val provider =
                    provider(client, wait = {
                        polling.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    })
                val owner = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                polling.await()
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                owner.cancelAndJoin()
                assertEquals(mlx, waiter.await()?.lyrics)
                assertEquals(2, requests)
                assertEquals(mlx, provider.getLyrics("Song", "Artist", null)?.lyrics)
                assertEquals(3, requests)
            }
        }

    @Test
    fun sharesFailureOnlyWhileInFlightAndRetriesNextLookup() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var requests = 0
            client {
                requests++
                release.await()
                respond("{}", HttpStatusCode.Unauthorized)
            }.use { client ->
                val provider = provider(client)
                supervisorScope {
                    val owner = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                    val waiter = async(start = CoroutineStart.UNDISPATCHED) { provider.getLyrics("Song", "Artist", null) }
                    release.complete(Unit)
                    assertFailsWith<IllegalStateException> { owner.await() }
                    assertFailsWith<IllegalStateException> { waiter.await() }
                    assertEquals(1, requests)
                }
                assertFailsWith<IllegalStateException> { provider.getLyrics("Song", "Artist", null) }
                assertEquals(2, requests)
            }
        }

    @Test
    fun separatesAllSourcesAndEveryRequestMetadataField() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val bodies = mutableListOf<String>()
            client { request ->
                bodies += (request.body as TextContent).text
                release.await()
                respond("""{"status":"not_found","complete":true}""", HttpStatusCode.NotFound, headersOf("X-Lyprio-Protocol", "2"))
            }.use { client ->
                val provider = provider(client)
                val lookups =
                    listOf(
                        async { provider.getLyrics("Song", "Artist", 1_000, "Album", "video") },
                        async { provider.getLyrics("Other", "Artist", 1_000, "Album", "video") },
                        async { provider.getLyrics("Song", "Other", 1_000, "Album", "video") },
                        async { provider.getLyrics("Song", "Artist", 2_000, "Album", "video") },
                        async { provider.getLyrics("Song", "Artist", 1_000, "Other", "video") },
                        async { provider.getLyrics("Song", "Artist", 1_000, "Album", "other") },
                    )
                val allSources = async { provider.getLyricsResults("Song", "Artist", 1_000, "Album", "video") }
                runCurrent()
                assertEquals(7, bodies.size)
                assertEquals(7, bodies.distinct().size)
                release.complete(Unit)
                lookups.forEach { assertNull(it.await()) }
                assertTrue(allSources.await().isEmpty())
            }
        }

    @Test
    fun neverSharesBetweenProviderInstancesOrCredentials() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val keys = listOf("01234567890123456789012345678901", "12345678901234567890123456789012")
            val signatures = mutableListOf<String?>()
            client { request ->
                signatures += request.headers["X-Lyprio-Signature"]
                release.await()
                respond("""{"status":"not_found","complete":true}""", HttpStatusCode.NotFound, headersOf("X-Lyprio-Protocol", "2"))
            }.use { client ->
                val providers = keys.map { provider(client, key = it.encodeToByteArray()) }
                val lookups = providers.map { async { it.getLyrics("Song", "Artist", null) } }
                runCurrent()
                assertEquals(2, signatures.distinct().size)
                release.complete(Unit)
                lookups.forEach { assertNull(it.await()) }
            }
        }

    @Test
    fun cancelledOnlyCallerStopsPollingAndLeavesNoPendingEntry() =
        runTest {
            val polling = CompletableDeferred<Unit>()
            var requests = 0
            client {
                requests++
                respond(
                    ready(listOf(result(MlxBuilder().line(1_000, "Partial lyrics").build())), false),
                    HttpStatusCode.OK,
                    headersOf("X-Lyprio-Protocol", "2"),
                )
            }.use { client ->
                var waits = 0
                val provider =
                    provider(client, wait = {
                        if (++waits == 1) {
                            polling.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                        throw CancellationException("cancel next lookup")
                    })
                val owner = async { provider.getLyrics("Song", "Artist", null) }
                polling.await()
                owner.cancelAndJoin()
                runCurrent()
                assertEquals(1, requests)
                assertFailsWith<CancellationException> { provider.getLyrics("Song", "Artist", null) }
                assertEquals(2, requests)
            }
        }

    private fun TestScope.client(handler: MockRequestHandler): HttpClient =
        HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            }
        }

    private fun provider(
        client: HttpClient,
        wait: suspend (Long) -> Unit = {},
        validate: (String) -> Boolean = MlxParser::isMlxFormat,
        key: ByteArray = "01234567890123456789012345678901".encodeToByteArray(),
    ) = LyprioLyricsProvider(client, "https://lyrics.example", key, { 1_800_000_000L }, wait, { 1L }, validate)

    private fun ready(
        results: List<String>,
        complete: Boolean,
    ): String = """{"results":[${results.joinToString()}],"status":"ready","complete":$complete}"""

    private fun result(
        lyrics: String,
        subprovider: String = "TEST",
        syncType: String = "line",
    ): String = """{"subprovider":"$subprovider","format":"mlx","syncType":"$syncType","lyrics":${Json.encodeToString(lyrics)}}"""
}

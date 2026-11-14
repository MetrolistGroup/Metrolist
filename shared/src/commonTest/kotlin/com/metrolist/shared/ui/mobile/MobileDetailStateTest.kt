package com.metrolist.shared.ui.mobile

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.responseDecodeDispatcher
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileDetailStateTest {
    @Test
    fun liveEntryRestoresBrowseWithoutReloadingAndRefreshStillFetches() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            var requests = 0
            var waitForCancellation = false
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val client =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler {
                                requests++
                                if (waitForCancellation) {
                                    started.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cancelled.complete(Unit)
                                    }
                                }
                                respond(
                                    """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        },
                    ),
                ) { install(ContentNegotiation) { json() } }
            val store = MobileDetailStateStore(this, YouTubeRepository(InnerTube(client)))
            try {
                val destination = MobileBrowseDestination("browse", null, "Browse")
                val parent = MobileRouteEntry(MobileRoute.Browse(destination))
                val child = MobileRouteEntry(MobileRoute.Album("album", "Album"))
                val loader = store.entry(parent).browse(destination)
                advanceUntilIdle()
                store.entry(child)
                store.retain(listOf(parent, child))
                store.retain(listOf(parent))
                assertSame(loader, store.entry(parent).browse(destination))
                assertFalse(loader.isLoading)
                assertEquals(1, requests)
                loader.load()
                advanceUntilIdle()
                assertEquals(2, requests)
                val nextOccurrence = MobileRouteEntry(parent.route)
                assertNotSame(store.entry(parent), store.entry(nextOccurrence))
                waitForCancellation = true
                store.entry(nextOccurrence).browse(destination)
                runCurrent()
                started.await()
                store.attach(nextOccurrence)
                store.retain(listOf(parent))
                runCurrent()
                assertFalse(cancelled.isCompleted)
                store.detach(nextOccurrence)
                runCurrent()
                assertTrue(cancelled.isCompleted)
            } finally {
                store.close()
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun discardingAnEntryCancelsItsRequestAndReplacementStoreLoadsFreshContent() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val loader =
                MobileDetailLoader(backgroundScope) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            runCurrent()
            started.await()
            backgroundScope.coroutineContext[Job]!!.cancel()
            runCurrent()
            assertTrue(cancelled.isCompleted)
            assertFalse(loader.failed)
            var requests = 0
            val first = MobileDetailLoader(this) { ++requests }
            advanceUntilIdle()
            val replacement = MobileDetailLoader(this) { ++requests }
            advanceUntilIdle()
            assertEquals(1, first.page)
            assertEquals(2, replacement.page)
        }
}

package com.metrolist.music.viewmodels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPicksLoaderTest {
    private fun loader() = QuickPicksLoader<String>(limit = 20, shuffle = { it }) { it }

    /** A load whose local and network stages finish only when the test completes them. */
    private class GatedLoad {
        val local = CompletableDeferred<List<String>>()
        val similar = CompletableDeferred<List<String>>()
        lateinit var job: Job

        fun start(scope: CoroutineScope, loader: QuickPicksLoader<String>): GatedLoad {
            job =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    loader.load(local = { local.await() }, similar = { similar.await() })
                }
            return this
        }
    }

    private suspend fun settle() = repeat(10) { yield() }

    private suspend fun QuickPicksLoader<String>.loadNow(
        local: List<String>,
        similar: List<String> = emptyList(),
    ) = load(local = { local }, similar = { similar })

    @Test
    fun `first launch publishes local picks before slow enrichment finishes`() =
        runBlocking {
            val loader = loader()
            val load = GatedLoad().start(this, loader)
            assertNull(loader.items.value)

            load.local.complete(listOf("a", "b"))
            settle()
            assertEquals(listOf("a", "b"), loader.items.value)
            assertTrue(load.job.isActive)

            load.similar.complete(listOf("c"))
            load.job.join()
            assertEquals(listOf("a", "b", "c"), loader.items.value)
        }

    @Test
    fun `first launch without local data waits for enrichment instead of publishing empty`() =
        runBlocking {
            val loader = loader()
            val load = GatedLoad().start(this, loader)

            load.local.complete(emptyList())
            settle()
            assertNull(loader.items.value)

            load.similar.complete(listOf("yt"))
            load.job.join()
            assertEquals(listOf("yt"), loader.items.value)
        }

    @Test
    fun `first launch with no data anywhere ends as an empty loaded result`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = emptyList())
            assertEquals(emptyList<String>(), loader.items.value)

            loader.loadNow(local = listOf("a"))
            assertEquals(listOf("a"), loader.items.value)
        }

    @Test
    fun `refresh keeps the displayed list until the fresh result replaces it once`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("old"))

            val load = GatedLoad().start(this, loader)
            load.local.complete(listOf("new1", "new2"))
            settle()
            assertEquals(listOf("old"), loader.items.value)

            load.similar.complete(listOf("yt"))
            load.job.join()
            assertEquals(listOf("new1", "new2", "yt"), loader.items.value)
        }

    @Test
    fun `enrichment adds new songs deduplicated and limited`() =
        runBlocking {
            val loader = QuickPicksLoader<String>(limit = 3, shuffle = { it }) { it }
            loader.loadNow(local = listOf("a", "b", "a"), similar = listOf("b", "c", "d"))
            assertEquals(listOf("a", "b", "c"), loader.items.value)
        }

    @Test
    fun `enrichment adding nothing new does not reshuffle the early local result`() =
        runBlocking {
            var shuffles = 0
            val loader = QuickPicksLoader<String>(shuffle = { shuffles++; it }) { it }
            loader.loadNow(local = listOf("a", "b"), similar = listOf("b"))
            assertEquals(listOf("a", "b"), loader.items.value)
            assertEquals(1, shuffles)
        }

    @Test
    fun `enrichment failure keeps local results`() =
        runBlocking {
            val loader = loader()
            loader.load(local = { listOf("a") }, similar = { error("network down") })
            assertEquals(listOf("a"), loader.items.value)

            loader.load(local = { listOf("b") }, similar = { error("network down") })
            assertEquals(listOf("b"), loader.items.value)
        }

    @Test
    fun `missing related endpoint behaves like an empty enrichment`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("old"))
            loader.loadNow(local = listOf("a"), similar = emptyList())
            assertEquals(listOf("a"), loader.items.value)
        }

    @Test
    fun `temporarily empty database result keeps the previous list`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("old"))
            loader.loadNow(local = emptyList(), similar = emptyList())
            assertEquals(listOf("old"), loader.items.value)
        }

    @Test
    fun `refresh failing after valid recommendations keeps them visible`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("old"))
            loader.load(local = { emptyList() }, similar = { error("timeout") })
            assertEquals(listOf("old"), loader.items.value)
        }

    @Test
    fun `cancelled refresh publishes nothing and keeps the previous list`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("old"))

            val load = GatedLoad().start(this, loader)
            load.local.complete(listOf("new"))
            settle()
            load.job.cancel()
            load.job.join()

            assertTrue(load.job.isCancelled)
            assertEquals(listOf("old"), loader.items.value)
        }

    @Test
    fun `cancellation swallowed by the network call is still respected`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("old"))

            val job =
                launch {
                    loader.load(
                        local = { listOf("new") },
                        similar = {
                            // Mimics runCatching around a request: cancellation becomes a normal return.
                            coroutineContext[Job]!!.cancel()
                            withContext(NonCancellable) { listOf("yt") }
                        },
                    )
                }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(listOf("old"), loader.items.value)
        }

    @Test
    fun `overlapping refreshes finishing in reverse order keep the newest result`() =
        runBlocking {
            val loader = loader()
            loader.loadNow(local = listOf("initial"))

            val older = GatedLoad().start(this, loader)
            val newer = GatedLoad().start(this, loader)
            settle()
            assertTrue(older.job.isCancelled)

            newer.local.complete(listOf("newer"))
            newer.similar.complete(emptyList())
            newer.job.join()
            assertEquals(listOf("newer"), loader.items.value)

            older.local.complete(listOf("older"))
            older.similar.complete(listOf("older-yt"))
            settle()
            assertEquals(listOf("newer"), loader.items.value)
        }

    @Test
    fun `superseded first load cannot publish its early local result`() =
        runBlocking {
            val loader = loader()
            val older = GatedLoad().start(this, loader)
            val newer = GatedLoad().start(this, loader)

            older.local.complete(listOf("older"))
            settle()
            assertNull(loader.items.value)

            newer.local.complete(listOf("newer"))
            settle()
            assertEquals(listOf("newer"), loader.items.value)
            newer.similar.complete(emptyList())
            newer.job.join()
        }
}

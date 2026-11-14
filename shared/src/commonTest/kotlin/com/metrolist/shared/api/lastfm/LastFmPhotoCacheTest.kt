package com.metrolist.shared.api.lastfm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LastFmPhotoCacheTest {
    private var stored = "{}"

    private fun cache(
        load: suspend (String) -> LastFmPhotoDetails?,
        now: () -> Long,
        wallNow: () -> Long = now,
    ) = LastFmPhotoCache(read = { stored }, write = { stored = it }, load = load, now = now, wallNow = wallNow)

    @Test
    fun restartKeepsOriginalExpiryEvenWithANewMonotonicClock() =
        runTest {
            var wallTime = 1_000_000L
            var requests = 0
            val original = LastFmPhotoDetails(title = "original")
            val load: suspend (String) -> LastFmPhotoDetails? = { _ ->
                requests++
                if (requests == 1) original else null
            }
            cache(load, { 50_000 }, { wallTime }).get("photo")
            wallTime += 23 * 60 * 60_000L
            assertEquals(original, cache(load, { 0 }, { wallTime }).get("photo"))
            assertEquals(1, requests)
            wallTime += 60 * 60_000L
            assertEquals(original, cache(load, { 0 }, { wallTime }).get("photo"))
            assertEquals(2, requests)
        }

    @Test
    fun corruptedStorageRefetchesAndRepairsTheCache() =
        runTest {
            stored = "broken json"
            val original = LastFmPhotoDetails(title = "photo")
            cache({ _ -> original }, { testScheduler.currentTime }).get("photo")
            assertEquals(original, cache({ _ -> error("Must use repaired storage") }, { 0 }).get("photo"))
        }

    @Test
    fun hitsNeverRewriteStorageAndStorageStaysBounded() =
        runTest {
            var writes = 0
            val cache =
                LastFmPhotoCache(
                    read = { stored },
                    write = {
                        writes++
                        stored = it
                    },
                    load = { url -> LastFmPhotoDetails(title = url) },
                    now = { testScheduler.currentTime },
                    wallNow = { testScheduler.currentTime },
                )
            repeat(100) { cache.get("$it") }
            cache.get("0")
            cache.get("100")
            assertEquals("0", cache.get("0")?.title)
            assertNull(cache.peek("1"))
            assertEquals(101, writes)
            assertEquals(100, Regex(""""details"""").findAll(stored).count())
        }

    @Test
    fun cachedHitsDoNotWaitForPendingLoads() =
        runTest {
            val cache =
                cache(load = { url ->
                    if (url == "slow") delay(10_000)
                    LastFmPhotoDetails(title = url)
                }, now = { testScheduler.currentTime })
            cache.get("hit")
            val slow = async { cache.get("slow") }
            testScheduler.runCurrent()
            val start = testScheduler.currentTime
            assertEquals("hit", cache.get("hit")?.title)
            assertEquals(start, testScheduler.currentTime)
            slow.await()
        }

    @Test
    fun sleepingPrefetchDoesNotBlockForegroundLoads() =
        runTest {
            val calls = mutableListOf<Pair<String, Long>>()
            val cache =
                cache(load = { url ->
                    calls += url to testScheduler.currentTime
                    LastFmPhotoDetails(title = url)
                }, now = { testScheduler.currentTime })
            cache.get("first")
            val prefetch = async { cache.get("neighbor", prefetch = true) }
            testScheduler.runCurrent()
            cache.get("current")
            assertEquals(1_000L, testScheduler.currentTime)
            assertNull(prefetch.await())
            assertEquals(listOf("first" to 0L, "current" to 1_000L), calls)
        }

    @Test
    fun queuedDuplicatesShareOneSuspendedLoad() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val cache =
                cache(load = {
                    loads++
                    gate.await()
                    LastFmPhotoDetails(title = "photo")
                }, now = { testScheduler.currentTime })
            val first = async { cache.get("photo") }
            val second = async { cache.get("photo") }
            testScheduler.runCurrent()
            gate.complete(Unit)
            assertEquals("photo", first.await()?.title)
            assertEquals("photo", second.await()?.title)
            assertEquals(1, loads)
        }

    @Test
    fun cancellingLoadingQueuedOrSleepingRequestsNeverStrandsTheLock() =
        runTest {
            val cache =
                cache(load = { url ->
                    if (url == "stuck") awaitCancellation()
                    LastFmPhotoDetails(title = url)
                }, now = { testScheduler.currentTime })
            val loading = async { cache.get("stuck") }
            val queued = async { cache.get("queued") }
            val sleeping = async { cache.get("neighbor", prefetch = true) }
            testScheduler.runCurrent()
            queued.cancel()
            sleeping.cancel()
            loading.cancel()
            testScheduler.runCurrent()
            assertEquals("next", cache.get("next")?.title)
            assertEquals(1_000L, testScheduler.currentTime)
        }

    @Test
    fun rateLimitedAvatarIsRetriedAfterTheCooldownAndNeverDropsAKnownAvatar() =
        runTest {
            var calls = 0
            val cache =
                cache(load = {
                    when (++calls) {
                        1 -> throw LastFmPhotoBackoff(60_000, LastFmPhotoDetails(title = "photo"))
                        2 -> LastFmPhotoDetails(title = "photo", authorAvatarUrl = "avatar")
                        else -> throw LastFmPhotoBackoff(60_000, LastFmPhotoDetails(title = "photo"))
                    }
                }, now = { testScheduler.currentTime })
            assertNull(cache.get("photo")?.authorAvatarUrl)
            assertNull(cache.get("photo")?.authorAvatarUrl)
            assertEquals(1, calls)
            delay(60_001)
            assertEquals("avatar", cache.get("photo")?.authorAvatarUrl)
            assertEquals("avatar", cache.get("photo")?.authorAvatarUrl)
            assertEquals(2, calls)
            delay(24 * 60 * 60_000L)
            assertEquals("avatar", cache.get("photo")?.authorAvatarUrl)
            assertEquals(3, calls)
        }

    @Test
    fun foregroundUsesShortStartSpacingWhilePrefetchWaitsAfterCompletion() =
        runTest {
            val calls = mutableListOf<Pair<String, Long>>()
            val cache =
                cache(load = { url ->
                    calls += url to testScheduler.currentTime
                    if (url == "slow") delay(2_000)
                    LastFmPhotoDetails(title = url)
                }, now = { testScheduler.currentTime })
            cache.get("first")
            cache.get("slow")
            cache.get("next")
            cache.get("neighbor", prefetch = true)
            cache.get("first")
            assertEquals(listOf("first" to 0L, "slow" to 1_000L, "next" to 3_000L, "neighbor" to 8_000L), calls)
            assertEquals(8_000L, testScheduler.currentTime)
        }

    @Test
    fun serverBackoffSkipsPrefetchButForegroundWaitsAndLoads() =
        runTest {
            var calls = 0
            val cache =
                cache(load = { _ ->
                    if (++calls == 1) throw LastFmPhotoBackoff(300_000)
                    LastFmPhotoDetails(title = "next")
                }, now = { testScheduler.currentTime })
            assertNull(cache.get("first", prefetch = true))
            assertNull(cache.get("neighbor", prefetch = true))
            assertEquals(1, calls)
            assertEquals("next", cache.get("second")?.title)
            assertEquals(300_000L, testScheduler.currentTime)
        }

    @Test
    fun foregroundRetriesRateLimitOnceAndPreservesPartialDetails() =
        runTest {
            var calls = 0
            val cache =
                cache(load = { _ ->
                    calls++
                    throw LastFmPhotoBackoff(300_000, if (calls > 1) LastFmPhotoDetails(title = "photo") else null)
                }, now = { testScheduler.currentTime })
            assertEquals("photo", cache.get("photo")?.title)
            assertEquals(2, calls)
            assertEquals(300_000L, testScheduler.currentTime)
            assertEquals("photo", cache.get("photo")?.title)
            assertNull(cache.get("neighbor", prefetch = true))
        }
}

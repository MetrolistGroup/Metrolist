package com.metrolist.shared.playlist

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryDeletionConcurrencyTest {
    @Test
    fun concurrentDeletionAndReconciliationRetainAllPendingDeletions() {
        val settings = Settings(MapSettings())
        Settings.replaceSharedForTest(settings)
        LibraryDeletionTracker.clear()
        val executor = Executors.newFixedThreadPool(5)
        val start = CountDownLatch(1)
        val ids = (0 until 200).map { "PL_concurrent_$it" }
        val keys = ids.mapTo(mutableSetOf()) { "playlist:$it" }
        try {
            val tasks =
                ids.chunked(50).map { batch ->
                    executor.submit {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        batch.forEach { LibraryDeletionTracker.trackDeletedPlaylist(it, timestamp = 1_000) }
                    }
                } +
                    executor.submit {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        repeat(200) { LibraryDeletionTracker.reconcile(keys) }
                    }
            start.countDown()
            tasks.forEach { it.get(15, TimeUnit.SECONDS) }

            assertTrue(keys.all { LibraryDeletionTracker.isDeleted(it, now = 1_000) })
            assertEquals(keys, Json.decodeFromString<Map<String, Long>>(settings.deletedPlaylistIdsJson).keys)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            LibraryDeletionTracker.clear()
            Settings.replaceSharedForTest(null)
        }
    }
}

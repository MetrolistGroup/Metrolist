package com.metrolist.android.playback

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import androidx.room3.Room
import com.metrolist.android.App
import com.metrolist.android.db.QueryCallbackDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class DownloadServiceRestartRegressionTest {
    @Test
    fun replacementServiceDrainsQueueWhilePreviousServiceIsFinishingCancellation() = verifyRestart(overlap = true)

    @Test
    fun replacementServiceDrainsQueueAfterPreviousCancellationHasFinished() = verifyRestart(overlap = false)

    @Test
    fun pausingDuringReplacementServiceWaitKeepsQueuePaused() = verifyRestart(overlap = true, pauseWhileWaiting = true)

    private fun verifyRestart(
        overlap: Boolean,
        pauseWhileWaiting: Boolean = false,
    ) = runBlocking {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val context = notificationContext()
        val lookupEntered = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val blockFirstLookup = AtomicBoolean(true)
        val contention = ObservedClaims()
        val database =
            Room
                .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                .setDriver(
                    QueryCallbackDriver { sql ->
                        if (sql.startsWith("SELECT * FROM cached_audio WHERE songId") && blockFirstLookup.compareAndSet(true, false)) {
                            lookupEntered.countDown()
                            check(releaseLookup.await(10, TimeUnit.SECONDS)) { "The fixture must release the old lookup" }
                        }
                    },
                ).build()
        val file = File(context.cacheDir, "restart-download-$overlap.opus").apply { writeBytes(ByteArray(2048)) }
        var manager: SongDownloadManager? = null
        val services = mutableListOf<DownloadService>()
        val drains = mutableListOf<Job>()
        try {
            val song = SongItem("saved", "Saved download", null, emptyList(), null)
            database.dao().upsert(
                CachedAudioEntity(
                    song.id,
                    song.title,
                    "Artist",
                    filePath = file.path,
                    fileSize = file.length(),
                    cachedAt = 1,
                    lastAccessedAt = 1,
                    cacheLocation = "INTERNAL",
                    kind = KIND_DOWNLOAD,
                ),
            )
            Settings.shared.downloadQueueJson = Json.encodeToString(listOf(DownloadRequest(song, enqueuedAt = 1)))
            val downloads = SongDownloadManager(context, AudioCacheManager(context, database), database)
            manager = downloads
            ReflectionHelpers.setField(downloads, "activeDownloads", contention)
            downloads.flushQueue()
            startKoin { modules(module { single { downloads } }) }
            val app = App()
            app.startup.initialize(restore = {}, start = {})
            ReflectionHelpers.setField(app, "infrastructureReady", true)

            fun createService(): DownloadService =
                Robolectric.buildService(DownloadService::class.java).get().also {
                    ReflectionHelpers.setField(it, "mApplication", app)
                    it.onCreate()
                    services += it
                }

            val first = createService()
            first.onStartCommand(Intent(context, DownloadService::class.java), 0, 1)
            assertTrue("First real service must reach its database lookup", lookupEntered.await(10, TimeUnit.SECONDS))
            val previousDrain = checkNotNull(ReflectionHelpers.getField<Job?>(first, "drainJob"))
            drains += previousDrain
            assertEquals(DownloadQueueState.RUNNING, downloads.queuedRequests().single().state)

            first.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            first.onDestroy()
            assertEquals(DownloadQueueState.PAUSED, downloads.queuedRequests().single().state)
            assertFalse("Destroy must return while the old lookup is still blocked", previousDrain.isCompleted)
            if (!overlap) {
                releaseLookup.countDown()
                withTimeout(10_000) { previousDrain.join() }
            }

            downloads.resumeAll().join()
            assertEquals(DownloadQueueState.QUEUED, downloads.queuedRequests().single().state)
            val replacement = createService()
            replacement.onStartCommand(Intent(context, DownloadService::class.java), 0, 2)
            if (overlap) {
                assertTrue("Replacement must encounter the old task owner", contention.entered.await(10, TimeUnit.SECONDS))
                assertFalse(previousDrain.isCompleted)
                val replacementDrain = checkNotNull(ReflectionHelpers.getField<Job?>(replacement, "drainJob"))
                drains += replacementDrain
                contention.release.countDown()
                if (pauseWhileWaiting) withTimeout(10_000) { downloads.pauseAll().join() }
                releaseLookup.countDown()
                withTimeout(10_000) { drains.joinAll() }
            } else {
                withTimeout(10_000) { downloads.requests.first { it.single().state == DownloadQueueState.COMPLETED } }
            }
            downloads.flushQueue()
            println(
                "overlap=$overlap, pauseWhileWaiting=$pauseWhileWaiting, state=${downloads.queuedRequests().single().state}, " +
                    "runnable=${downloads.hasRunnableDownloads()}, oldDrainCompleted=${previousDrain.isCompleted}, " +
                    "replacementDrain=${ReflectionHelpers.getField<Job?>(replacement, "drainJob")}, owners=${contention.size}",
            )
            val expectedState = if (pauseWhileWaiting) DownloadQueueState.PAUSED else DownloadQueueState.COMPLETED
            assertEquals(
                "The replacement service must respect the current queue state without another service-start command",
                expectedState,
                downloads.queuedRequests().single().state,
            )
            assertFalse(downloads.hasRunnableDownloads())
            assertEquals(
                expectedState,
                Json.decodeFromString<List<DownloadRequest>>(Settings.shared.downloadQueueJson).single().state,
            )
            assertTrue(file.exists())
            assertNotNull(database.dao().cachedAudio(song.id))
        } finally {
            contention.release.countDown()
            releaseLookup.countDown()
            services.forEach { it.onDestroy() }
            withTimeout(10_000) {
                services.map { ReflectionHelpers.getField<CoroutineScope>(it, "serviceScope").coroutineContext.job }.joinAll()
            }
            manager?.flushQueue()
            manager?.let {
                withTimeout(10_000) {
                    ReflectionHelpers
                        .getField<CoroutineScope>(it, "workerScope")
                        .coroutineContext.job
                        .cancelAndJoin()
                }
            }
            stopKoin()
            database.close()
            file.delete()
            Settings.replaceSharedForTest(null)
        }
    }

    private class ObservedClaims : ConcurrentHashMap<String, Any>() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun putIfAbsent(
            key: String,
            value: Any,
        ): Any? {
            val previous = super.putIfAbsent(key, value)
            if (previous != null) {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "The fixture must release the ownership observation" }
            }
            return previous
        }
    }

    private fun notificationContext() =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                    override fun getString(id: Int): String = id.toString()

                    override fun getString(
                        id: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()

                    override fun getQuantityString(
                        id: Int,
                        quantity: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()
                }

            override fun getResources(): Resources = labels
        }
}

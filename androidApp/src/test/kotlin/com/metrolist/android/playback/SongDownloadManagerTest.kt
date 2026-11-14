package com.metrolist.android.playback

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Resources
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.db.QueryCallbackDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.russhwolf.settings.Settings as KmpSettings

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SongDownloadManagerTest {
    @Test
    fun removedCompletedDownloadStaysCancelledAfterRestartAndAllowsExplicitDownload() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = notificationContext()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val audio = AudioCacheManager(context, database)
            val file = File(audio.downloadDirectory(), "removed.opus").apply { writeBytes(ByteArray(2048)) }
            val song = SongItem("removed", "Episode", null, emptyList(), null)
            Settings.shared.downloadQueueJson = Json.encodeToString(listOf(DownloadRequest(song, DownloadQueueState.COMPLETED, 1)))
            val manager = SongDownloadManager(context, audio, database)
            try {
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
                manager.trackPlaylist("LPtracked")
                assertTrue(manager.removeDownload(song))
                assertFalse(file.exists())
                assertTrue(manager.isTrackingPlaylist("LPtracked"))
                val restarted = SongDownloadManager(context, audio, database)
                restarted.flushQueue()
                assertEquals(0, restarted.downloadMissing(listOf(song), "LPtracked"))
                assertEquals(DownloadQueueState.CANCELLED, restarted.queuedRequests().single().state)
                restarted.download(song)
                restarted.flushQueue()
                assertEquals(DownloadQueueState.QUEUED, restarted.queuedRequests().single().state)
                restarted.cancelAll().join()
                restarted.flushQueue()
            } finally {
                manager.flushQueue()
                file.delete()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun clearDownloadsStopsTrackingButCancelBatchPreservesCompletedDownloads() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = notificationContext()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val audio = AudioCacheManager(context, database)
            val song = SongItem("completed", "Song", null, emptyList(), null)
            val file = File(audio.downloadDirectory(), "completed.opus").apply { writeBytes(ByteArray(2048)) }
            Settings.shared.downloadQueueJson = Json.encodeToString(listOf(DownloadRequest(song, DownloadQueueState.COMPLETED, 1)))
            val manager = SongDownloadManager(context, audio, database)
            try {
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
                manager.trackPlaylist("LPtracked")
                manager.trackPlaylist("LM")
                manager.cancelAll().join()
                assertTrue(manager.isTrackingPlaylist("LPtracked"))
                assertTrue(file.exists())
                assertEquals(DownloadQueueState.COMPLETED, manager.queuedRequests().single().state)
                assertTrue(manager.clearDownloads())
                assertTrue(manager.trackedPlaylistIds().isEmpty())
                assertFalse(file.exists())
                assertEquals(0, manager.downloadMissing(listOf(song), "LPtracked"))
                assertTrue(database.dao().cachedAudioByLastAccessed(KIND_DOWNLOAD).isEmpty())
            } finally {
                manager.flushQueue()
                file.delete()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun queueRestorationRunsOffCallerThreadAndEarlyCancellationWaitsForIt() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val readThread = AtomicReference<Thread>()
            val backing = MapSettings()
            val store =
                object : KmpSettings by backing {
                    override fun getString(
                        key: String,
                        defaultValue: String,
                    ): String {
                        if (key == "downloadQueueJson") {
                            readThread.set(Thread.currentThread())
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                        return backing.getString(key, defaultValue)
                    }
                }
            val settings = Settings(store)
            backing.putString(
                "downloadQueueJson",
                Json.encodeToString(
                    listOf(
                        DownloadRequest(SongItem("saved", "Saved", null, emptyList(), null), enqueuedAt = 1),
                    ),
                ),
            )
            Settings.replaceSharedForTest(settings)
            var manager: SongDownloadManager? = null
            try {
                val downloads = SongDownloadManager(context, AudioCacheManager(context, database), database)
                manager = downloads
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertFalse(Thread.currentThread() === readThread.get())
                val cancel = downloads.cancelAll()
                assertFalse(cancel.isCompleted)
                release.countDown()
                cancel.join()
                downloads.flushQueue()
                assertEquals(
                    "saved",
                    downloads
                        .queuedRequests()
                        .single()
                        .song.id,
                )
                assertEquals(DownloadQueueState.CANCELLED, downloads.queuedRequests().single().state)
                assertFalse(downloads.hasRunnableDownloads())
            } finally {
                release.countDown()
                manager?.flushQueue()
                Settings.replaceSharedForTest(null)
                database.close()
            }
        }

    @Test
    fun cancelDuringPublicationRemovesTheCommittedDownloadBeforeReleasingTheTask() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val context = notificationContext()
            val song = SongItem("cancel-publish", "Track", null, listOf(ArtistTiny(null, "Artist")), null)
            lateinit var downloads: SongDownloadManager
            var cancelAtCommit = true
            val database =
                Room
                    .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                    .setDriver(
                        QueryCallbackDriver { sql ->
                            if (cancelAtCommit && sql.startsWith("INSERT") && sql.contains("cached_audio")) {
                                cancelAtCommit = false
                                runBlocking { downloads.cancel(song).join() }
                            }
                        },
                    ).build()
            val bytes = ByteArray(4096)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("fixture")
                            .body(bytes.toResponseBody())
                            .build()
                    }.build()
            val cache = AudioCacheManager(context, database, client)
            Settings.shared.downloadQueueJson = Json.encodeToString(listOf(DownloadRequest(song, enqueuedAt = 1)))
            downloads = SongDownloadManager(context, cache, database)
            try {
                val resource =
                    PlaybackResource(
                        song.id,
                        "https://audio.example/test",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = bytes.size.toLong(),
                        mimeType = "audio/mp4",
                        requireBoundedRange = false,
                    )
                assertTrue(downloads.processNext { _, _ -> resource })
                assertEquals(DownloadQueueState.CANCELLED, downloads.queuedRequests().single().state)
                assertNull(database.dao().cachedAudio(song.id))
                assertFalse(cache.isDownloaded(song.id))
                assertTrue(
                    cache
                        .downloadDirectory()
                        .listFiles()
                        .orEmpty()
                        .isEmpty(),
                )
            } finally {
                downloads.flushQueue()
                cache.downloadDirectory().deleteRecursively()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun deniedServiceStartPausesDurableWorkAndKeepsResumeNotificationAfterForegroundRemoval() =
        runBlocking {
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val context =
                object : ContextWrapper(application) {
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

                    override fun startForegroundService(service: Intent): ComponentName? =
                        throw IllegalStateException("Background start denied")
                }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = SongDownloadManager(context, AudioCacheManager(context, database), database)
            startKoin { modules(module { single { manager } }) }
            try {
                val song = SongItem("pending", "Pending", null, emptyList(), null)
                assertFalse(manager.download(song))
                manager.flushQueue()
                assertEquals(DownloadQueueState.PAUSED, manager.queuedRequests().single().state)
                assertFalse(manager.hasRunnableDownloads())
                assertEquals(
                    DownloadQueueState.PAUSED,
                    Json.decodeFromString<List<DownloadRequest>>(Settings.shared.downloadQueueJson).single().state,
                )
                val notifications = context.getSystemService(NotificationManager::class.java)
                notifications.cancel(SongDownloadManager.NOTIFICATION_ID)
                val paused = shadowOf(notifications).getNotification(SongDownloadManager.PAUSED_NOTIFICATION_ID)
                assertNotNull(paused)
                assertEquals(SongDownloadManager.ACTION_RESUME_ALL, shadowOf(paused.actions.first().actionIntent).savedIntent.action)
                manager.cancelAll().join()
                manager.flushQueue()
                assertNull(shadowOf(notifications).getNotification(SongDownloadManager.PAUSED_NOTIFICATION_ID))
            } finally {
                manager.flushQueue()
                stopKoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun cancellationDuringFileLookupReleasesTaskSoServiceRestartCanDrain() =
        runBlocking {
            val context = notificationContext()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val database =
                Room
                    .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                    .setDriver(
                        QueryCallbackDriver { sql ->
                            if (sql.startsWith("SELECT * FROM cached_audio WHERE songId")) {
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                        },
                    ).build()
            val file = File(context.cacheDir, "restart-download").apply { writeBytes(ByteArray(2048)) }
            var manager: SongDownloadManager? = null
            try {
                val song = SongItem("song", "Song", null, emptyList(), null)
                database.dao().upsert(
                    CachedAudioEntity(
                        "song",
                        "Song",
                        "Artist",
                        filePath = file.path,
                        fileSize = 2048,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "INTERNAL",
                        kind = KIND_DOWNLOAD,
                    ),
                )
                Settings.shared.downloadQueueJson = Json.encodeToString(listOf(DownloadRequest(song, enqueuedAt = 1)))
                val downloads = SongDownloadManager(context, AudioCacheManager(context, database), database)
                manager = downloads
                val worker = launch(Dispatchers.IO) { downloads.processNext() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                worker.cancel()
                release.countDown()
                worker.join()
                downloads.resetInterruptedDownloads()
                assertTrue(downloads.processNext())
                assertEquals(DownloadQueueState.COMPLETED, downloads.queuedRequests().single().state)
            } finally {
                release.countDown()
                manager?.flushQueue()
                database.close()
                file.delete()
                Settings.replaceSharedForTest(null)
            }
        }

    private fun notificationContext() =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                    override fun getString(id: Int): String = id.toString()

                    override fun getQuantityString(
                        id: Int,
                        quantity: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()

                    override fun getString(
                        id: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()
                }

            override fun getResources(): Resources = labels
        }
}

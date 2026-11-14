@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)

package com.metrolist.shared.storage

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.closeFile
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.truncateFileAtOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosAudioDownloadManagerTest {
    @BeforeTest
    fun setMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun storedAudioPreservesCatalogDurationForPlaybackWithoutQueueMetadata() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                for (kind in listOf(KIND_CACHE, KIND_DOWNLOAD)) {
                    for (storedDuration in listOf(null, -1, 0, 200)) {
                        val song = SongItem("duration-$kind-$storedDuration", "test", null, emptyList(), null)
                        if (storedDuration != null) {
                            database.dao().upsert(SongEntity(song.id, song.title, duration = storedDuration, liked = true))
                        }
                        val resource =
                            PlaybackResource(
                                song.id,
                                "https://rr1.googlevideo.com/videoplayback",
                                emptyMap(),
                                null,
                                null,
                                durationSeconds = 198L,
                            )
                        manager.saveAudio(song, resource, kind) { _, path -> writeTestAudio(path) }
                        val expectedDuration = storedDuration?.takeIf { it > 0 }?.toLong() ?: 198L
                        val storedSong = checkNotNull(database.dao().getSongById(song.id)).song
                        assertEquals(expectedDuration.toInt(), storedSong.duration)
                        assertEquals(storedDuration != null, storedSong.liked)
                        assertEquals(expectedDuration, manager.getCachedPlaybackResource(song)?.durationSeconds)
                        assertEquals(201L, manager.getCachedPlaybackResource(song.copy(duration = 201L))?.durationSeconds)
                    }
                }
            } finally {
                manager.clearDownloads()
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun savingCacheCopiesOnlyMatchingCompleteRepresentations() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                for (scenario in listOf("matching", "changed-quality", "truncated")) {
                    val song = SongItem("promotion-$scenario", "test", null, emptyList(), null)
                    val resource =
                        PlaybackResource(
                            song.id,
                            "https://rr1.googlevideo.com/videoplayback",
                            emptyMap(),
                            null,
                            null,
                            contentLengthBytes = 4096,
                            itag = 140,
                            mimeType = "audio/mp4",
                            codecs = "mp4a.40.2",
                            bitrate = 128000,
                        )
                    manager.saveAudio(song, resource, KIND_CACHE) { _, path -> writeTestAudio(path) }
                    val cached = checkNotNull(database.dao().cachedAudio(song.id))
                    if (scenario == "truncated") {
                        val file = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(cached.filePath))
                        file.truncateFileAtOffset(2048u)
                        file.closeFile()
                    }
                    var transfers = 0
                    val requested = if (scenario == "changed-quality") resource.copy(itag = 141) else resource
                    manager.saveAudio(song, requested, KIND_DOWNLOAD) { _, path ->
                        transfers++
                        writeTestAudio(path)
                    }
                    val downloaded = checkNotNull(database.dao().cachedAudio(song.id))
                    assertEquals(if (scenario == "matching") 0 else 1, transfers)
                    assertEquals(KIND_DOWNLOAD, downloaded.kind)
                    assertEquals(4096L, downloaded.fileSize)
                    assertTrue(NSFileManager.defaultManager.fileExistsAtPath(downloaded.filePath))
                    assertFalse(NSFileManager.defaultManager.fileExistsAtPath(cached.filePath))
                }
            } finally {
                manager.clearDownloads()
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun publicationRejectsAFileWhoseActualLengthDoesNotMatchTransferResult() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                val song = SongItem("truncated-publication", "test", null, emptyList(), null)
                val resource =
                    PlaybackResource(
                        song.id,
                        "https://rr1.googlevideo.com/videoplayback",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = 8192,
                    )
                assertFailsWith<IllegalStateException> {
                    manager.saveAudio(song, resource, KIND_CACHE) { _, path ->
                        writeTestAudio(path)
                        "audio/mp4" to 8192L
                    }
                }
                assertNull(database.dao().cachedAudio(song.id))
            } finally {
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun cancellationAroundMetadataCommitKeepsPublishedAudio() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val driver = CommitBoundaryDriver(BundledSQLiteDriver())
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(driver).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                for (cancelBeforeCommit in listOf(true, false)) {
                    val song = SongItem("commit-cancellation-$cancelBeforeCommit", "test", null, emptyList(), null)
                    val resource = PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null)
                    manager.saveAudio(song, resource, KIND_CACHE) { _, path -> writeTestAudio(path) }
                    val previous = checkNotNull(database.dao().cachedAudio(song.id))
                    var cancelledAtCommit = false
                    val task =
                        launch {
                            val saving = currentCoroutineContext().job
                            val cancel = {
                                cancelledAtCommit = true
                                saving.cancel()
                            }
                            manager.saveAudio(
                                song,
                                resource,
                                KIND_DOWNLOAD,
                                metadataTransaction = { update ->
                                    database.withDatabaseTransaction {
                                        update()
                                        if (cancelBeforeCommit) driver.beforeCommit = cancel else driver.afterCommit = cancel
                                    }
                                },
                            ) { _, path -> writeTestAudio(path) }
                        }
                    task.join()
                    driver.beforeCommit = {}
                    driver.afterCommit = {}
                    assertTrue(cancelledAtCommit)
                    assertTrue(task.isCancelled)
                    val published = checkNotNull(database.dao().cachedAudio(song.id))
                    assertEquals(KIND_DOWNLOAD, published.kind)
                    assertTrue(NSFileManager.defaultManager.fileExistsAtPath(published.filePath))
                    assertFalse(NSFileManager.defaultManager.fileExistsAtPath(previous.filePath))
                }
            } finally {
                manager.clearDownloads()
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun failedMetadataCommitRetainsPreviousAudioAndRemovesReplacement() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                val song = SongItem("failed-commit", "test", null, emptyList(), null)
                val resource = PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null)
                manager.saveAudio(song, resource, KIND_CACHE) { _, path -> writeTestAudio(path) }
                val previous = checkNotNull(database.dao().cachedAudio(song.id))
                var replacementPath: String? = null
                assertFailsWith<IllegalStateException> {
                    manager.saveAudio(
                        song,
                        resource,
                        KIND_DOWNLOAD,
                        metadataTransaction = { update ->
                            database.withDatabaseTransaction {
                                update()
                                replacementPath = checkNotNull(database.dao().cachedAudio(song.id)).filePath
                                error("commit failed")
                            }
                        },
                    ) { _, path -> writeTestAudio(path) }
                }
                assertEquals(previous, database.dao().cachedAudio(song.id))
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath(previous.filePath))
                assertFalse(NSFileManager.defaultManager.fileExistsAtPath(checkNotNull(replacementPath)))
            } finally {
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    private fun writeTestAudio(path: String): Pair<String?, Long> {
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null))
        val file = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path))
        file.truncateFileAtOffset(4096u)
        file.closeFile()
        return "audio/mp4" to 4096L
    }

    @Test
    fun storageUsageUpdatesAfterPublicationReplacementRemovalAndEviction() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { maxSongCacheSize = 1 })
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                val initial = manager.storageUsage()
                val song = SongItem("storage-usage", "test", null, emptyList(), null)
                val resource = PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null)
                manager.saveAudio(song, resource, KIND_CACHE) { _, path ->
                    writeAudioFile(path, 4096L)
                    "audio/mp4" to 4096L
                }
                awaitUsage(manager, initial.copy(cacheBytes = initial.cacheBytes + 4096L))
                manager.saveAudio(song, resource, KIND_DOWNLOAD) { _, path ->
                    writeAudioFile(path, 8192L)
                    "audio/mp4" to 8192L
                }
                awaitUsage(manager, initial.copy(downloadBytes = initial.downloadBytes + 8192L))
                assertTrue(manager.removeDownload(song.id))
                awaitUsage(manager, initial)
                manager.saveAudio(song, resource, KIND_CACHE) { _, path ->
                    writeAudioFile(path, 4096L)
                    "audio/mp4" to 4096L
                }
                awaitUsage(manager, initial.copy(cacheBytes = initial.cacheBytes + 4096L))
                Settings.shared.maxSongCacheSize = 0
                manager.enforceCacheLimit()
                awaitUsage(manager, initial)
            } finally {
                manager.clearDownloads()
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun storageUsageCountsActivePartialsOnDemandAndRefreshesAfterFailureOrCancellation() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            var transfer: Deferred<Unit>? = null
            try {
                val initial = manager.storageUsage()
                for (cancel in listOf(false, true)) {
                    val started = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val song = SongItem("partial-usage", "test", null, emptyList(), null)
                    val resource = PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null)
                    transfer =
                        async {
                            runCatching {
                                manager.saveAudio(song, resource, KIND_CACHE) { _, path ->
                                    writeAudioFile(path, 4096L)
                                    started.complete(Unit)
                                    release.await()
                                    error("Transfer failed")
                                }
                            }
                        }
                    started.await()
                    assertEquals(
                        initial.copy(cacheBytes = initial.cacheBytes + 4096L),
                        manager.storageUsage().copy(freeBytes = initial.freeBytes),
                    )
                    if (cancel) {
                        transfer.cancelAndJoin()
                    } else {
                        release.complete(Unit)
                        transfer.await()
                    }
                    awaitUsage(manager, initial)
                    assertNull(database.dao().cachedAudio(song.id))
                }
            } finally {
                withContext(NonCancellable) {
                    transfer?.cancelAndJoin()
                    manager.close()
                    backgroundScope.coroutineContext.job.cancelAndJoin()
                    database.close()
                    Settings.replaceSharedForTest(null)
                }
            }
        }

    @Test
    fun storageUsageIncludesRetainedPlaybackCapturesAndRefreshesAfterDeletion() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { songCacheEnabled = true })
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                val initial = manager.storageUsage()
                val resource =
                    PlaybackResource(
                        "capture-usage",
                        "https://rr1.googlevideo.com/videoplayback",
                        emptyMap(),
                        null,
                        null,
                        contentLengthBytes = 8192L,
                        mimeType = "audio/mp4",
                    )
                val capture = checkNotNull(manager.playbackCache.acquire(resource))
                val directory = manager.playbackCache.directory
                val name =
                    NSFileManager.defaultManager
                        .contentsOfDirectoryAtPath(directory, null)
                        .orEmpty()
                        .single()
                writeAudioFile("$directory/$name", 4096L)
                manager.playbackCache.release(capture)
                val retained = initial.copy(cacheBytes = initial.cacheBytes + 4096L)
                assertEquals(retained, manager.storageUsage().copy(freeBytes = retained.freeBytes))
                assertEquals(initial.totalBytes + 4096L, retained.totalBytes)
                manager.clearCache()
                awaitUsage(manager, initial)
            } finally {
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun smallUnknownLengthTransfersKeepBothCachedSongs() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { maxSongCacheSize = 1 })
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                for (id in listOf("first-unknown", "second-unknown")) {
                    val song = SongItem(id, "test", null, emptyList(), null)
                    val resource = PlaybackResource(id, "https://media.example.test/audio", emptyMap(), null, null)
                    manager.saveAudio(song, resource, KIND_CACHE) { _, path ->
                        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null))
                        val file = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path))
                        file.truncateFileAtOffset(4096u)
                        file.closeFile()
                        "audio/mp4" to 4096L
                    }
                }
                for (id in listOf("first-unknown", "second-unknown")) {
                    val entry = checkNotNull(database.dao().cachedAudio(id))
                    assertTrue(NSFileManager.defaultManager.fileExistsAtPath(entry.filePath))
                    assertEquals(4096L, entry.fileSize)
                }
            } finally {
                manager.clearCache()
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun networkTransferDoesNotBlockPlaybackAndClearPreventsRepublishing() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var transfer: Deferred<Unit>? = null
            try {
                manager.storageUsage()
                val song = SongItem("transfer", "test", null, emptyList(), null)
                val resource = PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null)
                transfer =
                    async {
                        manager.saveAudio(song, resource, KIND_CACHE) { _, _ ->
                            started.complete(Unit)
                            release.await()
                            "audio/mp4" to 2048L
                        }
                    }
                started.await()
                assertNull(manager.getCachedPlaybackResource(SongItem("unrelated", "test", null, emptyList(), null)))
                manager.clearCache()
                release.complete(Unit)
                transfer.await()
                assertNull(database.dao().cachedAudio(song.id))
            } finally {
                withContext(NonCancellable) {
                    transfer?.cancelAndJoin()
                    manager.close()
                    backgroundScope.coroutineContext.job.cancelAndJoin()
                    database.close()
                    Settings.replaceSharedForTest(null)
                }
            }
        }

    @Test
    fun failedUnknownLengthCacheTransferKeepsExistingCachedSongs() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.maxSongCacheSize = 1
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            val path = "${NSTemporaryDirectory()}${NSUUID().UUIDString}"
            try {
                manager.storageUsage()
                assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null))
                val cached =
                    CachedAudioEntity(
                        songId = "cached",
                        title = "test",
                        artist = "test",
                        filePath = path,
                        fileSize = 0L,
                        cachedAt = 1L,
                        lastAccessedAt = 1L,
                        cacheLocation = "IOS_APPLICATION_SUPPORT",
                        kind = KIND_CACHE,
                    )
                database.dao().upsert(cached)
                val song = SongItem("unknown-length", "test", null, emptyList(), null)
                val resource = PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null)
                assertTrue(
                    runCatching {
                        manager.saveAudio(song, resource, KIND_CACHE) { _, _ -> error("Transfer failed") }
                    }.isFailure,
                )
                assertEquals(cached, database.dao().cachedAudio(cached.songId))
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
            } finally {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun removalRetainsRecordWhenFileCannotBeDeletedAndAcceptsAlreadyAbsentFiles() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            val path = "${NSTemporaryDirectory()}${NSUUID().UUIDString}"
            try {
                manager.storageUsage()
                assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null))
                val entry =
                    CachedAudioEntity(
                        songId = "removal",
                        title = "test",
                        artist = "test",
                        filePath = path,
                        fileSize = 0L,
                        cachedAt = 1L,
                        lastAccessedAt = 1L,
                        cacheLocation = "IOS_APPLICATION_SUPPORT",
                        kind = KIND_DOWNLOAD,
                    )
                database.dao().upsert(entry)
                assertFalse(manager.removeDownload(entry.songId))
                assertEquals(entry, database.dao().cachedAudio(entry.songId))
                assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path))
                assertTrue(NSFileManager.defaultManager.removeItemAtPath(path, error = null))
                assertTrue(manager.removeDownload(entry.songId))
                assertNull(database.dao().cachedAudio(entry.songId))
            } finally {
                NSFileManager.defaultManager.removeItemAtPath(path, error = null)
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun concurrentCacheRepairPreservesTheStorageWritersReplacement() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database = Room.inMemoryDatabaseBuilder<MusicDatabase>().setDriver(BundledSQLiteDriver()).build()
            val manager = IosAudioDownloadManager(database, backgroundScope)
            try {
                manager.storageUsage()
                val old =
                    CachedAudioEntity(
                        songId = "replacement",
                        title = "test",
                        artist = "test",
                        filePath = "${NSTemporaryDirectory()}${NSUUID().UUIDString}",
                        fileSize = 0L,
                        cachedAt = 1L,
                        lastAccessedAt = 1L,
                        cacheLocation = "IOS_APPLICATION_SUPPORT",
                        kind = KIND_DOWNLOAD,
                    )
                database.dao().upsert(old)
                manager.storageMutex.lock()
                val reader =
                    async {
                        manager.getCachedPlaybackResource(SongItem(old.songId, old.title, null, emptyList(), null))
                    }
                val replacement = quarantineAudioDownload(old.copy(filePath = old.filePath + "-replacement"))
                try {
                    database.dao().upsert(replacement)
                } finally {
                    manager.storageMutex.unlock()
                }
                assertNull(reader.await())
                assertEquals(replacement, database.dao().cachedAudio(old.songId))
            } finally {
                manager.close()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }

    private fun writeAudioFile(
        path: String,
        size: Long,
    ) {
        assertTrue(NSFileManager.defaultManager.createFileAtPath(path, contents = null, attributes = null))
        val file = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path))
        try {
            file.truncateFileAtOffset(size.toULong())
        } finally {
            file.closeFile()
        }
    }

    private suspend fun awaitUsage(
        manager: IosAudioDownloadManager,
        expected: IosStorageUsage,
    ) {
        manager.storageUsageUpdates.first { it?.copy(freeBytes = expected.freeBytes) == expected }
    }
}

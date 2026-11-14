package com.metrolist.shared.storage

import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.db.withDatabaseTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioReplacementTest {
    @Test
    fun quarantinedFileSurvivesRestartAndRollbackUntilReplacementCommits() =
        runTest {
            val directory = createTempDirectory().toFile()
            val dbFile = File(directory, "audio.db")
            val previous = File(directory, "previous.m4a").apply { writeText("old audio") }
            val replacement = File(directory, "replacement.m4a")
            val deleteFile: (String) -> Unit = { File(it).delete() }
            var database = getDesktopDatabaseBuilder(dbFile).build()
            try {
                val quarantined = quarantineAudioDownload(downloadEntry(previous.path))
                database.dao().upsert(quarantined)
                database.close()
                database = getDesktopDatabaseBuilder(dbFile).build()

                val restored = assertNotNull(database.dao().cachedAudio("song"))
                assertTrue(isQuarantinedDownload(restored))
                assertFalse(canUseDownloadedAudio(restored, fileIsValid = true))
                assertTrue(shouldRetainAudioFile(restored, fileIsValid = false))
                assertTrue(previous.exists())

                val replacementEntry = downloadEntry(replacement.path)
                assertFailsWith<IllegalStateException> {
                    replaceAudioAfterCommit<Unit>(previous.path, {
                        database.withDatabaseTransaction {
                            database.dao().upsert(replacementEntry)
                            error("database commit failed")
                        }
                    }, deleteFile)
                }
                assertEquals(quarantined, database.dao().cachedAudio("song"))
                assertTrue(previous.exists())

                replacement.writeText("new audio")
                replaceAudioAfterCommit(previous.path, {
                    database.withDatabaseTransaction { database.dao().upsert(replacementEntry) }
                    replacement.path to Unit
                }, deleteFile)

                val published = assertNotNull(database.dao().cachedAudio("song"))
                assertTrue(canUseDownloadedAudio(published, fileIsValid = true))
                assertFalse(matchesAudioFile(published, previous.path))
                assertFalse(previous.exists())
                assertTrue(replacement.exists())
            } finally {
                database.close()
                directory.deleteRecursively()
            }
        }

    @Test
    fun cancellationAroundCommitKeepsPublishedFileAndCompletesOldFileCleanup() =
        runTest {
            val directory = createTempDirectory().toFile()
            val driver = CommitBoundaryDriver(JdbcSQLiteDriver())
            val database = getDesktopDatabaseBuilder(File(directory, "audio.db")).setDriver(driver).build()
            try {
                for (cancelBeforeCommit in listOf(true, false)) {
                    val previous = File(directory, "previous.m4a").apply { writeText("old audio") }
                    val published = File(directory, "replacement.m4a").apply { writeText("new audio") }
                    database.dao().upsert(downloadEntry(previous.path))
                    var cancelledAtCommit = false
                    val saving =
                        launch {
                            val caller = currentCoroutineContext().job
                            val cancel = {
                                cancelledAtCommit = true
                                caller.cancel()
                            }
                            var committed = false
                            try {
                                replaceAudioAfterCommit(previous.path, {
                                    database.withDatabaseTransaction {
                                        database.dao().upsert(downloadEntry(published.path))
                                        if (cancelBeforeCommit) driver.beforeCommit = cancel else driver.afterCommit = cancel
                                    }
                                    committed = true
                                    published.path to Unit
                                }, { File(it).delete() })
                            } catch (error: Exception) {
                                if (!committed) published.delete()
                                throw error
                            }
                        }
                    saving.join()
                    driver.beforeCommit = {}
                    driver.afterCommit = {}
                    assertTrue(cancelledAtCommit)
                    assertTrue(saving.isCancelled)
                    assertEquals(published.path, database.dao().cachedAudio("song")?.filePath)
                    assertTrue(published.exists())
                    assertFalse(previous.exists())
                }
            } finally {
                database.close()
                directory.deleteRecursively()
            }
        }

    private fun downloadEntry(path: String) =
        CachedAudioEntity(
            songId = "song",
            title = "title",
            artist = "artist",
            filePath = path,
            fileSize = MINIMUM_AUDIO_BYTES,
            cachedAt = 1L,
            lastAccessedAt = 1L,
            cacheLocation = "IOS_APPLICATION_SUPPORT",
            kind = KIND_DOWNLOAD,
        )
}

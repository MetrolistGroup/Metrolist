package com.metrolist.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingRestoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun stagingLeavesLiveDatabaseUntouchedAndInstallDropsOldSidecars() {
        val live = temporary.newFile("song.db").apply { writeText("old") }
        val wal = temporary.newFile("song.db-wal").apply { writeText("old-wal") }
        val source = temporary.newFile("backup.db").apply { writeText("new") }
        val pending = PendingRestore(File(temporary.root, "pending"))
        var settings = "old-settings"
        pending.stage(source, "new-settings")
        assertEquals("old", live.readText())
        assertEquals("old-wal", wal.readText())
        pending.install(live, { settings }, { settings = it }) {
            assertEquals("new", live.readText())
            assertFalse(wal.exists())
            settings = pending.request.readText()
        }
        assertEquals("new-settings", settings)
        assertFalse(pending.request.exists())
    }

    @Test
    fun verificationOrSettingsFailureRestoresDatabaseSidecarsAndSettings() {
        val live = temporary.newFile("song.db").apply { writeText("old") }
        val wal = temporary.newFile("song.db-wal").apply { writeText("old-wal") }
        val source = temporary.newFile("backup.db").apply { writeText("new") }
        val pending = PendingRestore(File(temporary.root, "pending"))
        var settings = "old-settings"
        pending.stage(source, "new-settings")
        val failure =
            runCatching {
                pending.install(live, { settings }, { settings = it }) {
                    settings = "partly-imported"
                    File(live.path + "-shm").writeText("new-shm")
                    error("verification failed")
                }
            }
        assertTrue(failure.isFailure)
        assertEquals("old", live.readText())
        assertEquals("old-wal", wal.readText())
        assertFalse(File(live.path + "-shm").exists())
        assertEquals("old-settings", settings)
        assertFalse(pending.request.exists())
    }

    @Test
    fun interruptedInstallRollsBackBeforeRetryAndRetainsOriginalOnFailure() {
        val live = temporary.newFile("song.db").apply { writeText("half-installed") }
        val source = temporary.newFile("backup.db").apply { writeText("new") }
        val directory = File(temporary.root, "pending")
        val pending = PendingRestore(directory)
        pending.stage(source, "new-settings")
        val rollback = File(directory, "rollback").apply { mkdirs() }
        File(rollback, "song.db").writeText("original")
        File(rollback, "settings.json").writeText("original-settings")
        File(directory, "installing").writeText("")
        var settings = "half-imported"
        runCatching {
            pending.install(live, { settings }, { settings = it }) { error("retry failed") }
        }
        assertEquals("original", live.readText())
        assertEquals("original-settings", settings)
    }

    @Test
    fun committedRestoreIsNeverRolledBackAndSettingsOnlyRestoreLeavesDatabaseOpen() {
        val live = temporary.newFile("song.db").apply { writeText("live") }
        val directory = File(temporary.root, "pending")
        val pending = PendingRestore(directory)
        pending.stage(null, "settings")
        pending.install(live, { "old" }, { error("unexpected rollback") }) {
            assertEquals("live", live.readText())
        }
        pending.stage(null, "settings")
        File(directory, "committed").writeText("")
        pending.install(live, { error("already committed") }, { error("already committed") }) { error("already committed") }
        assertEquals("live", live.readText())
        assertFalse(directory.exists())
    }
}

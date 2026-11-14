package com.metrolist.desktop.backup

import com.metrolist.desktop.utils.desktopRestartCommand
import com.metrolist.shared.backup.BACKUP_DATABASE_ENTRY
import com.metrolist.shared.backup.BACKUP_MANIFEST_ENTRY
import com.metrolist.shared.backup.BACKUP_SETTINGS_ENTRY
import com.metrolist.shared.backup.BackupArchiveLimits
import com.metrolist.shared.backup.BackupManifest
import com.metrolist.shared.backup.BackupPlatform
import com.metrolist.shared.db.StoredHistoryPlay
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.history
import com.metrolist.shared.db.historyArtistStatistics
import com.metrolist.shared.db.importHistorySources
import com.metrolist.shared.db.recordHistoryPlay
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.PortableSetting
import com.metrolist.shared.settings.PortableSettings
import com.metrolist.shared.settings.SettingType
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class DesktopBackupRestoreManagerTest {
    private lateinit var root: File
    private lateinit var database: File
    private lateinit var restore: File

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
        root = Files.createTempDirectory("metrolist restore 'quoted' ").toFile()
        database = File(root, "data/metrolist.db")
        val sharedMemory = File("/dev/shm").takeIf { it.isDirectory && it.canWrite() }
        restore = Files.createTempDirectory(sharedMemory?.toPath() ?: root.toPath(), "metrolist-restore-").resolve("pending").toFile()
        DesktopBackupRestoreManager.databaseFileOverride = database
        DesktopBackupRestoreManager.restoreDirOverride = restore
    }

    @After
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        DesktopBackupRestoreManager.databaseFileOverride = null
        DesktopBackupRestoreManager.restoreDirOverride = null
        root.deleteRecursively()
        restore.parentFile?.deleteRecursively()
    }

    @Test
    fun incompleteSettingsSnapshotCanRecoverBeforeInstallationStarts() {
        restore.mkdirs()
        File(restore, "ready").writeText("1")
        File(restore, "previous-settings.json").writeText("{\"entries\":")
        val settings = Settings(PropertiesSettings(Properties()))
        settings.persistentQueue = false
        assertTrue(DesktopBackupRestoreManager.applyPendingRestore(settings) { error("no database to install") })
        assertFalse(settings.persistentQueue)
        assertFalse(File(restore, "ready").exists())
    }

    @Test
    fun incompleteSettingsSnapshotAndRecoveryFilesRemainAfterInstallationStarts() {
        for (marker in listOf("installation-started", "database-preserved", "database-backup")) {
            restore.mkdirs()
            File(restore, "ready").writeText("1")
            val snapshot = File(restore, "previous-settings.json").apply { writeText("{") }
            database.parentFile.mkdirs()
            database.writeText("live database")
            val recovery = File(database.path + ".restore-backup")
            val boundary = if (marker == "database-backup") recovery else File(restore, marker)
            boundary.writeText("preserved")
            assertFailsWith<IllegalArgumentException> {
                DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { error("must not install") }
            }
            assertEquals("{", snapshot.readText())
            assertEquals("live database", database.readText())
            assertEquals("preserved", boundary.readText())
            assertTrue(File(restore, "ready").exists())
            boundary.delete()
            restore.deleteRecursively()
        }
    }

    @Test
    fun committedRestoreRecoveryFilesAreNotReusedByTheNextInstall() {
        database.parentFile.mkdirs()
        database.writeText("committed database")
        val previous = File(database.path + ".restore-backup").apply { writeText("obsolete database") }
        assertFalse(
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { error("already committed") },
        )
        assertEquals("committed database", database.readText())
        assertFalse(previous.exists())
    }

    @Test
    fun interruptedRestoreKeepsOriginalDatabaseForRollbackAtEachInstallBoundary() {
        for (promoted in listOf(false, true)) {
            val archive = File(root, "interrupted.zip")
            writeArchive(archive, androidV1Database())
            DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
            val original = database.readBytes().toList()
            File(restore, "previous-settings.json").writeText(Json.encodeToString(Settings.shared.exportBackupSettings()))
            Files.move(database.toPath(), File(database.path + ".restore-backup").toPath())
            if (promoted) {
                File(database.path + ".restore-backup-wal").writeText("original wal")
                File(database.path + ".restore-backup-shm").writeText("original shm")
                File(restore, "database-preserved").writeText("1")
                database.writeText("interrupted replacement")
                File(database.path + "-wal").writeText("replacement wal")
                File(database.path + "-shm").writeText("replacement shm")
            } else {
                File(database.path + "-wal").writeText("original wal")
                File(database.path + "-shm").writeText("original shm")
            }
            assertFailsWith<IllegalStateException> {
                DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) {
                    assertEquals(original, File(database.path + ".restore-backup").readBytes().toList())
                    error("verification failed after interrupted install")
                }
            }
            assertEquals(original, database.readBytes().toList())
            assertEquals("original wal", File(database.path + "-wal").readText())
            assertEquals("original shm", File(database.path + "-shm").readText())
            File(database.path + "-wal").delete()
            File(database.path + "-shm").delete()
        }
    }

    @Test
    fun failedRestartDisarmsRestoreBeforeReturningToTheRunningApp() {
        val archive = File(root, "restart.zip")
        writeArchive(archive, androidV1Database())
        assertTrue(
            DesktopBackupRestoreManager
                .stageRestoreAndRestart(archive, beforeRestart = {}, restartApp = {
                    assertFalse(File(restore, "ready").exists())
                    Result.failure(IllegalStateException("restart failed"))
                })
                .isFailure,
        )
        assertFalse(File(restore, "ready").exists())
        assertFalse(
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { error("restore was disarmed") },
        )
    }

    @Test
    fun backupTemporaryFilesArePrivateBeforeWritingAndDoNotClobberExistingFiles() {
        val existing = File(root, ".backup.zip.tmp").apply { writeText("unrelated") }
        val temporary = createPrivateBackupFile(root.toPath(), ".backup.zip.")
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(temporary))
            }
            assertEquals("unrelated", existing.readText())
            assertTrue(temporary != existing.toPath())
            DesktopBackupRestoreManager.backup(File(root, "backup.zip")).getOrThrow()
            assertEquals("unrelated", existing.readText())
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Test
    fun legacyBackupsAroundOneAndSevenMiBRestoreSongsCommittedOnlyToWal() {
        for (sizeMiB in listOf(1, 7)) {
            val source = androidV1Database()
            DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA wal_autocheckpoint=0")
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                    statement.execute(
                        """
                        WITH RECURSIVE songs(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM songs WHERE n < ${sizeMiB * 10_000})
                        INSERT INTO song
                        SELECT 'wal-' || n, 'Song ' || hex(randomblob(64)), 180, 0, 7, 1700000000000, 0 FROM songs
                        """.trimIndent(),
                    )
                }
                val archive = File(root, "legacy-$sizeMiB.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    val entries = listOf(source to BACKUP_DATABASE_ENTRY, File(source.path + "-wal") to "$BACKUP_DATABASE_ENTRY-wal")
                    (if (sizeMiB == 1) entries else entries.reversed()).forEach { (file, name) ->
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                assertTrue(archive.length() in sizeMiB * 750_000L..sizeMiB * 1_500_000L, "Archive bytes: ${archive.length()}")
                DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
                val staged = File(restore, BACKUP_DATABASE_ENTRY)
                assertTrue(File(staged.path + "-wal").length() == 0L)
                DriverManager.getConnection("jdbc:sqlite:${staged.toURI().toASCIIString()}?mode=ro&immutable=1").use { restored ->
                    restored.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM song WHERE id LIKE 'wal-%'").use {
                            assertTrue(it.next())
                            assertEquals(sizeMiB * 10_000, it.getInt(1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun compressedWalCannotExceedTheUncompressedDatabaseLimit() {
        val archive = File(root, "oversized-wal.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("$BACKUP_DATABASE_ENTRY-wal"))
            val block = ByteArray(64 * 1024)
            repeat((BackupArchiveLimits.DATABASE_BYTES / block.size).toInt()) { zip.write(block) }
            zip.write(0)
            zip.closeEntry()
        }
        assertTrue(archive.length() < 1024 * 1024)
        val failure = DesktopBackupRestoreManager.stageRestore(archive).exceptionOrNull()
        assertEquals("Backup entry is too large", failure?.message)
        assertFalse(restore.exists())
        assertFalse(database.exists())
    }

    @Test
    fun orphanWalFailsBeforeChangingTheCurrentDatabase() {
        val archive = File(root, "orphan.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("$BACKUP_DATABASE_ENTRY-wal"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertTrue(DesktopBackupRestoreManager.stageRestore(archive).isFailure)
        assertFalse(restore.exists())
        assertFalse(database.exists())
    }

    @Test
    fun oversizedSettingsFailExportWithoutReplacingTheDestination() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            val db = getDatabaseBuilder(database).build()
            try {
                db.dao().insert(SongEntity(id = "song", title = "Title"))
                Settings.shared.blockedSongIds = "x".repeat(BackupArchiveLimits.SETTINGS_BYTES.toInt() + 1)
                val archive = File(root, "preserved.zip").apply { writeText("previous backup") }
                assertTrue(DesktopBackupRestoreManager.backup(archive).isFailure)
                assertEquals("previous backup", archive.readText())
                assertFalse(File(archive.parentFile, ".${archive.name}.tmp").exists())
            } finally {
                db.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun importsLegacyAliasSidecarAndRejectsMalformedAliasesBeforeStaging() {
        val archive = File(root, "aliases.zip")

        fun writeAliases(json: String) {
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("artist_name_aliases.json"))
                zip.write(json.encodeToByteArray())
                zip.closeEntry()
            }
        }
        writeAliases("""{"artist":"別名","name:Original":"別名"}""")
        DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
        val settings = Settings(PropertiesSettings(Properties()))
        settings.artistNameAliasesJson = """{"stale":"Old"}"""
        assertTrue(DesktopBackupRestoreManager.applyPendingRestore(settings) { emptyList() })
        assertEquals(
            mapOf("artist" to "別名", "name:Original" to "別名"),
            Json.decodeFromString<Map<String, String>>(settings.artistNameAliasesJson),
        )
        writeAliases("""{"artist":null}""")
        assertTrue(DesktopBackupRestoreManager.stageRestore(archive).isFailure)
        assertFalse(DesktopBackupRestoreManager.applyPendingRestore(settings) { emptyList() })
        assertEquals("別名", Json.decodeFromString<Map<String, String>>(settings.artistNameAliasesJson)["artist"])
    }

    @Test
    fun buildsDesktopRestartCommandForPackagedAndDevelopmentApps() {
        assertEquals(
            listOf("/home/listener/.local/bin/metrolist"),
            desktopRestartCommand(
                appImage = "/home/listener/.local/bin/metrolist",
                command = "/usr/bin/java",
                processArguments = listOf("-cp", "old", "Main"),
                jvmArguments = emptyList(),
                classPath = "ignored",
                mainClass = "ignored",
            ),
        )
        assertEquals(
            listOf("/usr/bin/java", "-Xmx2g", "-cp", "app libs", "Main"),
            desktopRestartCommand(
                appImage = null,
                command = "/usr/bin/java",
                processArguments = listOf("-Xmx8g"),
                jvmArguments = listOf("-Xmx2g"),
                classPath = "app libs",
                mainClass = "Main",
            ),
        )
        assertEquals(
            listOf("/usr/bin/java", "-Xmx2g", "-cp", "app libs", "Main"),
            desktopRestartCommand(
                appImage = null,
                command = "/usr/bin/java",
                processArguments = null,
                jvmArguments = listOf("-Xmx2g"),
                classPath = "app libs",
                mainClass = "Main",
            ),
        )
        assertEquals(
            listOf("/opt/metrolist", "--profile", "default profile"),
            desktopRestartCommand(
                appImage = null,
                command = "/opt/metrolist",
                processArguments = listOf("--profile", "default profile"),
                jvmArguments = emptyList(),
                classPath = "ignored",
                mainClass = "ignored",
            ),
        )
    }

    @Test
    fun backupRoundTripsTheLiveLibraryDatabase() =
        runBlocking {
            val initial = getDatabaseBuilder(database).build()
            try {
                initial.dao().upsert(SongEntity(id = "backed-up", title = "Backed-up song"))
                initial.importHistorySources(
                    listOf(
                        "old" to
                            listOf(
                                StoredHistoryPlay(
                                    9,
                                    "backed-up",
                                    "Past title",
                                    "Past artist",
                                    "Past album",
                                    182,
                                    "art",
                                    1_765_000_000_000,
                                    "LOCAL",
                                    null,
                                    false,
                                ),
                            ),
                    ),
                )
            } finally {
                initial.close()
            }
            val archive = File(root, "round-trip.zip")
            DesktopBackupRestoreManager.backup(archive).getOrThrow()

            val changed = getDatabaseBuilder(database).build()
            try {
                changed.dao().upsert(SongEntity(id = "newer", title = "Not in backup"))
            } finally {
                changed.close()
            }
            DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { emptyList() }

            val restored = getDatabaseBuilder(database).build()
            try {
                assertEquals("Past title", restored.history().single().title)
                assertEquals("Past artist", restored.history().single().artists)
                assertEquals(182L, restored.history().single().duration)
                restored.dao().delete(
                    restored
                        .dao()
                        .historyRows()
                        .single()
                        .event,
                )
                assertEquals(false, restored.dao().historyImportEnabled())
                assertFailsWith<IllegalStateException> {
                    restored.importHistorySources(
                        listOf(
                            "other-device" to
                                listOf(
                                    StoredHistoryPlay(
                                        9,
                                        "backed-up",
                                        "Past title",
                                        "Past artist",
                                        "Past album",
                                        182,
                                        "art",
                                        1_765_000_000_000,
                                        "LOCAL",
                                        null,
                                        false,
                                    ),
                                ),
                        ),
                    )
                }
                assertTrue(restored.history().isEmpty())
            } finally {
                restored.close()
            }
            DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT title FROM song WHERE id='backed-up'").use { result ->
                        assertTrue(result.next())
                        assertEquals("Backed-up song", result.getString(1))
                    }
                    statement.executeQuery("SELECT 1 FROM song WHERE id='newer'").use { result -> assertFalse(result.next()) }
                }
            }
        }

    @Test
    fun rejectsCorruptArchiveWithoutChangingTheLiveDatabase() {
        database.parentFile.mkdirs()
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { it.execute("CREATE TABLE retained (value TEXT)") }
        }
        val corrupt = File(root, "corrupt.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertTrue(DesktopBackupRestoreManager.stageRestore(corrupt).isFailure)
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT name FROM sqlite_master WHERE name='retained'").use { assertTrue(it.next()) }
            }
        }
    }

    @Test
    fun rejectsOversizedUnknownArchiveEntries() {
        val oversized = File(root, "oversized.zip")
        ZipOutputStream(oversized.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("unknown.bin"))
            zip.write(ByteArray((BackupArchiveLimits.UNKNOWN_ENTRY_BYTES + 1).toInt()))
            zip.closeEntry()
        }

        assertTrue(DesktopBackupRestoreManager.stageRestore(oversized).isFailure)
        assertFalse(database.exists())
    }

    @Test
    fun rejectsUnsafeArchivePathsWithoutExtractingThem() {
        val unsafe = File(root, "unsafe.zip")
        ZipOutputStream(unsafe.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../song.db"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }

        assertTrue(DesktopBackupRestoreManager.stageRestore(unsafe).isFailure)
        assertFalse(File(root.parentFile, "song.db").exists())
        assertFalse(database.exists())
    }

    @Test
    fun restoresPermanentAndroidV1SnapshotIntoFreshDesktopDatabase() {
        val archive = File(root, "android-v1.zip")
        writeArchive(archive, androidV1Database())
        val originalSize = archive.length()

        DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()

        assertTrue(database.isFile)
        assertTrue(archive.isFile)
        assertEquals(originalSize, archive.length())
        assertTrue(
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) {
                DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT title FROM song WHERE id='fixture-song'").use { result ->
                            assertTrue(result.next())
                            assertEquals("Fixture song", result.getString(1))
                        }
                        statement
                            .executeQuery(
                                "SELECT position FROM playlist_song_map WHERE playlistId='9' AND songId='fixture-song'",
                            ).use { result ->
                                assertTrue(result.next())
                                assertEquals(0, result.getInt(1))
                            }
                    }
                }
                emptyList()
            },
        )
        assertTrue(archive.isFile)
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT enabled FROM history_import_policy WHERE id = 1").use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun version51BackupPreservesDisplayOnlySnapshotWithoutInventingArtistBoundaries() =
        runBlocking {
            val old = File(root, "version51.db")
            val source = getDatabaseBuilder(old).build()
            try {
                source.importHistorySources(
                    listOf(
                        "old" to
                            listOf(
                                StoredHistoryPlay(
                                    1,
                                    "song",
                                    "Title",
                                    "Tyler, The Creator",
                                    "Album",
                                    180,
                                    "art",
                                    1_765_000_000_000,
                                    "LOCAL",
                                    null,
                                    false,
                                ),
                            ),
                    ),
                )
            } finally {
                source.close()
            }
            DriverManager.getConnection("jdbc:sqlite:${old.absolutePath}").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("ALTER TABLE history_snapshot DROP COLUMN artistNamesJson")
                    sql.execute("ALTER TABLE history_snapshot DROP COLUMN albumId")
                    sql.execute("DROP TABLE pending_scrobble")
                    sql.execute("PRAGMA user_version = 51")
                }
            }
            val archive = File(root, "version51.zip")
            writeArchive(archive, old, databaseVersion = 51)
            DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { emptyList() }
            val restored = getDatabaseBuilder(database).build()
            try {
                assertEquals("Tyler, The Creator", restored.history().single().artists)
                assertEquals(listOf("Tyler, The Creator"), restored.history().single().artistNames)
                assertEquals(setOf("Tyler, The Creator"), restored.historyArtistStatistics(0, Long.MAX_VALUE).map { it.name }.toSet())
                assertEquals(false, restored.dao().historyImportEnabled())
            } finally {
                restored.close()
            }
        }

    @Test
    fun newBackupPreservesStructuredCreditsAndAlbumId() =
        runBlocking {
            val initial = getDatabaseBuilder(database).build()
            try {
                initial.recordHistoryPlay(
                    SongItem(
                        "song",
                        "Title",
                        "art",
                        listOf(ArtistTiny("lead", "Earth, Wind & Fire"), ArtistTiny("guest", "Guest")),
                        AlbumTiny("album-id", "Album"),
                    ),
                    1_765_000_000_000,
                )
            } finally {
                initial.close()
            }
            val archive = File(root, "structured.zip")
            DesktopBackupRestoreManager.backup(archive).getOrThrow()
            DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { emptyList() }
            val restored = getDatabaseBuilder(database).build()
            try {
                assertEquals(listOf("Earth, Wind & Fire", "Guest"), restored.history().single().artistNames)
                assertEquals("album-id", restored.history().single().albumId)
            } finally {
                restored.close()
            }
        }

    @Test
    fun version50BackupRestoresEventsWithoutReplayingUnbackedLegacyHistory() {
        runBlocking {
            val old = File(root, "version50.db")
            val source = getDatabaseBuilder(old).build()
            try {
                source.dao().upsert(SongEntity(id = "kept", title = "Original song"))
                source.dao().insert(
                    Event(
                        songId = "kept",
                        timestamp = LocalDateTime(2025, 1, 1, 0, 0),
                        playTime = 12_000,
                    ),
                )
                source.dao().insert(
                    Event(
                        songId = "kept",
                        timestamp = LocalDateTime(2025, 1, 2, 0, 0),
                        playTime = 240_000,
                        source = "YOUTUBE",
                        remoteFeedbackToken = "old-remote-token",
                    ),
                )
            } finally {
                source.close()
            }
            DriverManager.getConnection("jdbc:sqlite:${old.absolutePath}").use { connection ->
                connection.createStatement().use { sql ->
                    sql.execute("DROP TABLE history_provenance")
                    sql.execute("DROP TABLE history_snapshot")
                    sql.execute("DROP TABLE history_import_policy")
                    sql.execute("ALTER TABLE event DROP COLUMN playTimeKnown")
                    sql.execute("DROP TABLE pending_scrobble")
                    sql.execute("PRAGMA user_version = 50")
                }
            }
            val archive = File(root, "version50.zip")
            writeArchive(archive, old, databaseVersion = 50)
            DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
            DesktopBackupRestoreManager.applyPendingRestore(Settings(PropertiesSettings(Properties()))) { emptyList() }
            val restored = getDatabaseBuilder(database).build()
            try {
                assertEquals(2, restored.history().size)
                assertEquals(12_000L, restored.history().first { it.source == "LOCAL" }.playTimeMs)
                assertEquals(null, restored.history().first { it.source == "YOUTUBE" }.playTimeMs)
                assertEquals("old-remote-token", restored.history().first { it.source == "YOUTUBE" }.remoteFeedbackToken)
                assertEquals(false, restored.dao().historyImportEnabled())
                assertFailsWith<IllegalStateException> {
                    restored.importHistorySources(
                        listOf(
                            "old" to
                                listOf(StoredHistoryPlay(1, "kept", "Deleted", "Artist", null, null, null, 0, "LOCAL", null, false)),
                        ),
                    )
                }
            } finally {
                restored.close()
            }
        }
    }

    @Test
    fun rejectsSchemaZeroWithoutTouchingSourceOrDatabase() {
        val schemaZero = File(root, "schema-zero.db")
        DriverManager.getConnection("jdbc:sqlite:${schemaZero.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE song (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
            }
        }
        val archive = File(root, "schema-zero.zip")
        writeArchive(archive, schemaZero, databaseVersion = null)

        assertTrue(DesktopBackupRestoreManager.stageRestore(archive).isFailure)
        assertFalse(database.exists())
        assertTrue(archive.isFile)
    }

    @Test
    fun validatesUnsupportedManifestBeforeMaterializingEntries() {
        val archive = File(root, "future.zip")
        writeArchive(
            archive,
            androidV1Database(),
            formatVersion = BackupManifest.CURRENT_FORMAT_VERSION + 1,
        )

        assertTrue(DesktopBackupRestoreManager.stageRestore(archive).isFailure)
        assertFalse(database.exists())
        assertFalse(File(restore, BACKUP_DATABASE_ENTRY).exists())
    }

    @Test
    fun forcedVerificationFailureRollsBackDatabaseAndSettings() {
        val archive = File(root, "android-v1.zip")
        writeArchive(archive, androidV1Database())
        DesktopBackupRestoreManager.stageRestore(archive).getOrThrow()
        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "INSERT INTO song (id,title,duration,liked,totalPlayTime) VALUES ('current','Current song',0,0,0)",
                )
            }
        }
        val settings =
            Settings(PropertiesSettings(Properties())).apply {
                contentLanguage = "fr"
                innerTubeCookie = "current-cookie"
            }
        val originalSettings = settings.exportBackupSettings()

        assertFailsWith<IllegalStateException> {
            DesktopBackupRestoreManager.applyPendingRestore(settings) { error("forced verification failure") }
        }

        DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM song WHERE id='current'").use { result ->
                    assertTrue(result.next())
                    assertEquals("Current song", result.getString(1))
                }
            }
        }
        assertEquals(originalSettings, settings.exportBackupSettings())
        assertTrue(archive.isFile)
    }

    @Test
    fun failedSettingsImportRemovesNewPreferencesWhenRollingBack() {
        val properties = Properties()
        val backing = PropertiesSettings(properties)
        var failImport = true
        val settings =
            Settings(
                object : KmpSettings by backing {
                    override fun putBoolean(
                        key: String,
                        value: Boolean,
                    ) {
                        if (key == "hideExplicit" && failImport) {
                            failImport = false
                            error("forced settings failure")
                        }
                        backing.putBoolean(key, value)
                    }
                },
            )
        settings.innerTubeCookie = "current-cookie"
        assertEquals("SYSTEM", settings.contentLanguage)
        val previousSettings = settings.exportBackupSettings()
        restore.mkdirs()
        File(restore, "ready").writeText("1")
        File(restore, BACKUP_SETTINGS_ENTRY).writeText(
            Json.encodeToString(
                PortableSettings(
                    entries =
                        listOf(
                            PortableSetting("contentLanguage", SettingType.STRING, "fr"),
                            PortableSetting("hideExplicit", SettingType.BOOLEAN, "true"),
                        ),
                ),
            ),
        )

        assertFailsWith<IllegalStateException> {
            DesktopBackupRestoreManager.applyPendingRestore(settings) { emptyList() }
        }

        assertEquals(previousSettings, settings.exportBackupSettings())
        assertEquals("SYSTEM", settings.contentLanguage)
        assertEquals("current-cookie", settings.innerTubeCookie)
        assertFalse(backing.hasKey("contentLanguage"))
        assertFalse(backing.hasKey("hideExplicit"))
    }

    @Test
    fun backupRejectsLiveDatabaseAndSidecarDestinationsWithoutChangingThem() =
        runBlocking {
            val initial = getDatabaseBuilder(database).build()
            try {
                initial.dao().upsert(SongEntity(id = "kept", title = "Keep this song"))
            } finally {
                initial.close()
            }
            val originalDatabase = database.readBytes().toList()
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val destination = File(database.path + suffix)
                if (suffix.isNotEmpty()) destination.writeText("preserve $suffix")
                val originalDestination = destination.readBytes().toList()

                assertTrue(DesktopBackupRestoreManager.backup(destination).isFailure, "Must reject $suffix")
                assertEquals(originalDestination, destination.readBytes().toList())
                assertEquals(originalDatabase, database.readBytes().toList())
                if (suffix.isNotEmpty()) destination.delete()
            }
        }

    private fun androidV1Database(): File =
        File(root, "android-v1-${System.nanoTime()}.db").also { file ->
            val sql = checkNotNull(javaClass.getResource("/backup/android-v1.sql")).readText()
            DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
                connection.createStatement().use { statement ->
                    sql
                        .split(';')
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .forEach(statement::execute)
                }
            }
        }

    private fun writeArchive(
        archive: File,
        sourceDatabase: File,
        formatVersion: Int = BackupManifest.CURRENT_FORMAT_VERSION,
        databaseVersion: Int? = 1,
    ) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_MANIFEST_ENTRY))
            zip.write(
                Json
                    .encodeToString(
                        BackupManifest(
                            formatVersion = formatVersion,
                            platform = BackupPlatform.ANDROID,
                            databaseVersion = databaseVersion,
                        ),
                    ).encodeToByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(BACKUP_DATABASE_ENTRY))
            sourceDatabase.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package com.metrolist.shared.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getIosDatabaseBuilder
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import no.synth.kmpzip.io.OutputStream
import no.synth.kmpzip.zip.ZipEntry
import no.synth.kmpzip.zip.ZipOutputStream
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as SettingsStore

class IosBackupRestoreManagerTest {
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun restorePreservesSongChangesFromNonemptyWal() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val directory = "${NSTemporaryDirectory()}wal-restore-${NSUUID().UUIDString}".toPath()
            val fs = FileSystem.SYSTEM
            fs.createDirectories(directory)
            val source = directory / "source.db"
            val target = directory / "target.db"
            val archive = directory / "backup.zip"
            try {
                val original = getIosDatabaseBuilder(source.toString()).build()
                try {
                    original.dao().upsert(SongEntity(id = "song", title = "Before WAL"))
                } finally {
                    original.close()
                }
                BundledSQLiteDriver().open(source.toString()).use { connection ->
                    listOf(
                        "PRAGMA journal_mode=WAL",
                        "PRAGMA wal_autocheckpoint=0",
                        "PRAGMA wal_checkpoint(TRUNCATE)",
                        "UPDATE song SET title = 'From WAL' WHERE id = 'song'",
                    ).forEach { sql -> connection.prepare(sql).use { it.step() } }
                    fs.write(archive) {
                        val sink = this
                        val output =
                            object : OutputStream() {
                                override fun write(b: Int) {
                                    sink.writeByte(b)
                                }

                                override fun write(
                                    b: ByteArray,
                                    off: Int,
                                    len: Int,
                                ) {
                                    sink.write(b, off, len)
                                }

                                override fun close() = Unit
                            }
                        ZipOutputStream(output).use { zip ->
                            listOf("-wal", "").forEach { suffix ->
                                zip.putNextEntry(ZipEntry("$BACKUP_DATABASE_ENTRY$suffix"))
                                zip.write(fs.read("$source$suffix".toPath()) { readByteArray() })
                                zip.closeEntry()
                            }
                        }
                    }
                }
                val manager = IosBackupRestoreManager(target.toString(), Settings(MapSettings()))
                manager.restore(archive.toString(), closeDatabase = {}, reopenDatabase = {}).getOrThrow()
                val restored = getIosDatabaseBuilder(target.toString()).build()
                try {
                    assertEquals("From WAL", restored.dao().getSongById("song")?.title)
                } finally {
                    restored.close()
                }
                assertEquals(emptyList(), fs.list(directory).map { it.name }.filter { it.startsWith("target.db.restore-staged-") })
            } finally {
                fs.deleteRecursively(directory)
            }
        }

    @Test
    fun importsPrismCompanionsAtOriginalArchivePaths() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val directory = "${NSTemporaryDirectory()}prism-import-${NSUUID().UUIDString}".toPath()
            val fs = FileSystem.SYSTEM
            fs.createDirectories(directory)
            val source = directory / "prism.db"
            val target = directory / "library.db"
            val archive = directory / "prism.zip"
            val companion = """{"lists":[{"title":"Companion only","songs":[{"id":"abcdefghijk","title":"Companion song"}]}]}"""
            try {
                BundledSQLiteDriver().open(source.toString()).use { connection ->
                    connection
                        .prepare(
                            "CREATE TABLE songs (id TEXT, title TEXT, artistName TEXT, durationSec INTEGER, liked INTEGER, likedAt INTEGER, lastPlayed INTEGER)",
                        ).use { it.step() }
                    connection
                        .prepare(
                            "CREATE TABLE play_events (id TEXT, songId TEXT, timestamp INTEGER, playedMs INTEGER)",
                        ).use { it.step() }
                }
                fs.write(archive) {
                    val sink = this
                    val output =
                        object : OutputStream() {
                            override fun write(b: Int) {
                                sink.writeByte(b)
                            }

                            override fun write(
                                b: ByteArray,
                                off: Int,
                                len: Int,
                            ) {
                                sink.write(b, off, len)
                            }

                            override fun close() = Unit
                        }
                    ZipOutputStream(output).use { zip ->
                        zip.putNextEntry(ZipEntry("databases/prism.db"))
                        zip.write(fs.read(source) { readByteArray() })
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("files/library/prism.json"))
                        zip.write(companion.encodeToByteArray())
                        zip.closeEntry()
                        zip.putNextEntry(ZipEntry("shared_prefs/artist_prefs.xml"))
                        zip.write(
                            """<map><string name="favorites">[{"id":"UCstarred","name":"Star &amp; Artist","thumbnail":null,"at":1700000000000}]</string></map>"""
                                .encodeToByteArray(),
                        )
                        zip.closeEntry()
                    }
                }
                val database = getIosDatabaseBuilder(target.toString()).build()
                try {
                    IosBackupRestoreManager(target.toString(), Settings(MapSettings()))
                        .importForeignBackup(archive.toString(), database)
                        .getOrThrow()
                    assertTrue(database.dao().getSongById("abcdefghijk") != null)
                    assertTrue(database.dao().findPlaylistByName("Companion only") != null)
                    assertEquals("Star & Artist", database.dao().getArtistById("UCstarred")?.name)
                    assertEquals(null, database.dao().getArtistById("UCstarred")?.thumbnailUrl)
                } finally {
                    database.close()
                }
            } finally {
                fs.deleteRecursively(directory)
            }
        }

    @Test
    fun prismFavoritesPreserveMixedTextAndCdata() {
        val library = ForeignLibrary(ForeignLibraryFamily.PRISM, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val xml = """<map><string name="favorites">[{"id":"UCmixed","name":"Artist <![CDATA[</string>]]> Name"}]</string></map>"""

        assertEquals(
            "Artist </string> Name",
            library
                .withPrismArtistPreferences(xml)
                .artists
                .single()
                .name,
        )
    }

    @Test
    fun rejectsZip64LocatorBeforeImportOrRestoreOpensArchive() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val directory = "${NSTemporaryDirectory()}zip64-rejection-${NSUUID().UUIDString}".toPath()
            val fs = FileSystem.SYSTEM
            fs.createDirectories(directory)
            val target = directory / "library.db"
            val archive = directory / "zip64.zip"
            val zip64WithEmptyOrdinaryDirectory =
                ByteArray(42).apply {
                    this[0] = 0x50
                    this[1] = 0x4b
                    this[2] = 0x06
                    this[3] = 0x07
                    this[20] = 0x50
                    this[21] = 0x4b
                    this[22] = 0x05
                    this[23] = 0x06
                }
            val zip64WithLongComment =
                ByteArray(20 + 22 + 65_516).apply {
                    this[0] = 0x50
                    this[1] = 0x4b
                    this[2] = 0x06
                    this[3] = 0x07
                    this[20] = 0x50
                    this[21] = 0x4b
                    this[22] = 0x05
                    this[23] = 0x06
                    this[40] = 0xec.toByte()
                    this[41] = 0xff.toByte()
                }
            try {
                fs.write(archive) { write(zip64WithEmptyOrdinaryDirectory) }
                val longCommentArchive = directory / "zip64-long-comment.zip"
                fs.write(longCommentArchive) { write(zip64WithLongComment) }
                val database = getIosDatabaseBuilder(target.toString()).build()
                try {
                    val manager = IosBackupRestoreManager(target.toString(), Settings(MapSettings()))
                    var databaseClosed = false
                    for (path in listOf(archive, longCommentArchive)) {
                        val importFailure = manager.importForeignBackup(path.toString(), database).exceptionOrNull()
                        assertNotNull(importFailure)
                        assertTrue(importFailure.causeMessages().contains("ZIP64"))

                        val restoreFailure =
                            manager
                                .restore(
                                    path.toString(),
                                    closeDatabase = { databaseClosed = true },
                                    reopenDatabase = {},
                                ).exceptionOrNull()
                        assertNotNull(restoreFailure)
                        assertTrue(restoreFailure.causeMessages().contains("ZIP64"))
                    }
                    assertFalse(databaseClosed)
                } finally {
                    database.close()
                }
            } finally {
                fs.deleteRecursively(directory)
            }
        }

    @Test
    fun successfulRestoreAndSettingsFailureKeepDatabaseAndPreferencesTogether() =
        runTest {
            // Restore closes and reopens the database on Main, which runTest blocks on iOS.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val directory = "${NSTemporaryDirectory()}restore-${NSUUID().UUIDString}".toPath()
            val fs = FileSystem.SYSTEM
            fs.createDirectories(directory)
            val path = (directory / "library.db").toString()
            val archive = (directory / "backup.zip").toString()
            val values = MapSettings()
            var rejectImport = false
            val settings =
                Settings(
                    object : SettingsStore by values {
                        override fun putFloat(
                            key: String,
                            value: Float,
                        ) {
                            if (rejectImport) {
                                rejectImport = false
                                error("Settings write failed")
                            }
                            values.putFloat(key, value)
                        }
                    },
                )
            val manager = IosBackupRestoreManager(path, settings)

            suspend fun writeSong(id: String) {
                val db = getIosDatabaseBuilder(path).build()
                try {
                    db.dao().upsert(SongEntity(id = id, title = id))
                } finally {
                    db.close()
                }
            }

            suspend fun hasSong(id: String): Boolean {
                val db = getIosDatabaseBuilder(path).build()
                return try {
                    db.dao().getSongById(id) != null
                } finally {
                    db.close()
                }
            }
            try {
                settings.volume = 0.25f
                writeSong("backup")
                manager.backup(archive).getOrThrow()
                settings.volume = 0.75f
                writeSong("current")
                for (failClose in listOf(true, false)) {
                    var reopenedAfterFailure = false
                    val failed =
                        manager.restore(archive, closeDatabase = {
                            if (failClose) error("Database close failed")
                            fs.list(directory).filter { it.name.startsWith("library.db.restore-staged-") }.forEach { fs.delete(it) }
                        }, reopenDatabase = { reopenedAfterFailure = true })
                    assertTrue(failed.isFailure)
                    assertTrue(reopenedAfterFailure)
                    assertTrue(hasSong("current"))
                    assertEquals(0.75f, settings.volume)
                }
                rejectImport = true
                var reopened = false
                assertTrue(manager.restore(archive, closeDatabase = {}, reopenDatabase = { reopened = true }).isFailure)
                assertTrue(reopened)
                assertTrue(hasSong("current"))
                assertEquals(0.75f, settings.volume)
                manager.restore(archive, closeDatabase = {}, reopenDatabase = {}).getOrThrow()
                assertTrue(hasSong("backup"))
                assertFalse(hasSong("current"))
                assertEquals(0.25f, settings.volume)
                assertFalse(fs.exists("$path.restore-state".toPath()))
            } finally {
                fs.deleteRecursively(directory)
            }
        }

    @Test
    fun interruptedPromotionRollsBackOnStartupAndRecoveryIsRepeatable() {
        val directory = "${NSTemporaryDirectory()}recovery-${NSUUID().UUIDString}".toPath()
        val fs = FileSystem.SYSTEM
        fs.createDirectories(directory)
        val path = (directory / "library.db").toString()
        val values = MapSettings()
        var failRecovery = false
        val settings =
            Settings(
                object : SettingsStore by values {
                    override fun putFloat(
                        key: String,
                        value: Float,
                    ) {
                        if (failRecovery) error("Recovery interrupted")
                        values.putFloat(key, value)
                    }
                },
            )
        val manager = IosBackupRestoreManager(path, settings)
        try {
            settings.volume = 0.3f
            fs.write("$path.restore-rollback".toPath()) { writeUtf8("previous database") }
            manager.prepareRestoreRecovery(settings.exportBackupSettings(), hasDatabase = true, hadDatabase = true)
            fs.write(path.toPath()) { writeUtf8("restored database") }
            settings.volume = 0.9f
            failRecovery = true
            assertFailsWith<IllegalStateException> { manager.recoverInterruptedRestore() }
            assertTrue(fs.exists("$path.restore-state".toPath()))
            assertTrue(fs.exists("$path.restore-rollback".toPath()))
            failRecovery = false
            manager.recoverInterruptedRestore()
            manager.recoverInterruptedRestore()
            assertEquals("previous database", fs.read(path.toPath()) { readUtf8() })
            assertEquals(0.3f, settings.volume)
            assertFalse(fs.exists("$path.restore-state".toPath()))
            assertFalse(fs.exists("$path.restore-rollback".toPath()))
        } finally {
            fs.deleteRecursively(directory)
        }
    }
}

private fun Throwable.causeMessages(): String =
    generateSequence(this) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")

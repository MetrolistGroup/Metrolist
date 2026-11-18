package com.metrolist.desktop.storage

import com.metrolist.desktop.utils.AppLogger
import com.metrolist.shared.crash.FatalCrashHandler
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.storage.DownloadedPlaylists
import com.metrolist.shared.utils.XdgUtils
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.runner.JUnitCore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistDownloadOwnershipRegressionTest {
    @Test
    fun removingOnePlaylistKeepsDownloadsOwnedByAnotherTrackedPlaylist() = runInChild("shared")

    @Test
    fun removingPlaylistWithReadOnlyDownloadDirectoryDoesNotReachFatalHandler() = runInChild("read-only")

    private fun runInChild(scenario: String) {
        val childScenario = System.getenv(CHILD_PROCESS)
        if (childScenario != null) {
            if (childScenario == scenario) runScenario(readOnly = scenario == "read-only")
            return
        }

        val root = Files.createTempDirectory("playlist-download-ownership")
        try {
            if (scenario == "read-only") {
                assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-x------"))
                try {
                    assumeTrue("Tests require permissions enforced for the current user", !Files.isWritable(root))
                } finally {
                    Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
                }
            }
            val command =
                listOf(
                    ProcessHandle
                        .current()
                        .info()
                        .command()
                        .orElseThrow(),
                    "--enable-native-access=ALL-UNNAMED",
                    "--sun-misc-unsafe-memory-access=allow",
                    "-cp",
                    System.getProperty("java.class.path"),
                    JUnitCore::class.java.name,
                    javaClass.name,
                )
            val process =
                ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .apply {
                        environment()[CHILD_PROCESS] = scenario
                        environment()["XDG_DATA_HOME"] = root.resolve("data").absolutePathString()
                        environment()["XDG_STATE_HOME"] = root.resolve("state").absolutePathString()
                        environment()["XDG_CACHE_HOME"] = root.resolve("cache").absolutePathString()
                        environment()["XDG_CONFIG_HOME"] = root.resolve("config").absolutePathString()
                    }.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, process.waitFor(), output)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun runScenario(readOnly: Boolean) =
        runBlocking {
            val databaseFile = XdgUtils.getXdgStateHome().resolve("music.db")
            databaseFile.parentFile.mkdirs()
            val database = getDatabaseBuilder(databaseFile).build()
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            try {
                val song = SongItem("shared-song", "Shared song", null, emptyList(), null)
                val unique = SongItem("unique-song", "Unique song", null, emptyList(), null)
                val first = PlaylistEntity(id = "LPfirst", name = "First", isLocal = true)
                val second = PlaylistEntity(id = "LPsecond", name = "Second", isLocal = true)
                database.dao().insert(first)
                database.dao().insert(second)
                database.dao().upsertHistorySong(song)
                database.dao().upsertHistorySong(unique)
                database.dao().insert(PlaylistSongMap(playlistId = first.id, songId = song.id, position = 0))
                database.dao().insert(PlaylistSongMap(playlistId = first.id, songId = unique.id, position = 1))
                database.dao().insert(PlaylistSongMap(playlistId = second.id, songId = song.id, position = 0))
                DownloadedPlaylists.track(first.id)
                DownloadedPlaylists.track(second.id)

                val audio = XdgUtils.getXdgDataHome().resolve("metrolist/downloads/shared.m4a").toPath()
                Files.createDirectories(audio.parent)
                Files.write(audio, ByteArray(2_048) { 1 })
                val uniqueAudio = audio.parent.resolve("external/unique.m4a")
                Files.createDirectories(uniqueAudio.parent)
                Files.write(uniqueAudio, ByteArray(2_048) { 2 })
                val catalog = XdgUtils.getXdgStateHome().resolve("metrolist/downloads.json").toPath()
                Files.createDirectories(catalog.parent)
                Files.writeString(
                    catalog,
                    Json.encodeToString(
                        listOf(
                            DownloadedFile(
                                songId = song.id,
                                title = song.title,
                                artist = "",
                                thumbnailUrl = null,
                                audioFilePath = audio.toString(),
                                thumbnailFilePath = null,
                                duration = null,
                                downloadDate = 1L,
                            ),
                            DownloadedFile(
                                songId = unique.id,
                                title = unique.title,
                                artist = "",
                                thumbnailUrl = null,
                                audioFilePath = uniqueAudio.toString(),
                                thumbnailFilePath = null,
                                duration = null,
                                downloadDate = 2L,
                            ),
                        ),
                    ),
                )
                startKoin { modules(module { single<MusicDatabase> { database } }) }

                assertTrue(DownloadManager.isDownloaded(song.id))
                assertTrue(DownloadManager.isDownloaded(unique.id))
                if (readOnly) {
                    verifyDeniedRemoval(first.id, listOf(song, unique), uniqueAudio)
                    assertTrue(DownloadedPlaylists.contains(second.id))
                    assertTrue(DownloadManager.isDownloaded(song.id))
                    return@runBlocking
                }
                DownloadManager.removePlaylistDownloads(first.id, listOf(song, unique))
                withTimeout(10_000) {
                    while (DownloadManager.isDownloaded(unique.id)) delay(10)
                }

                assertTrue(DownloadedPlaylists.contains(second.id))
                assertTrue(DownloadManager.isDownloaded(song.id))
                assertTrue(Files.isRegularFile(audio))
                assertNotNull(DownloadManager.getLocalPlaybackResource(song.id, song))
                assertTrue(!DownloadManager.isDownloaded(unique.id))
                assertTrue(Files.notExists(uniqueAudio))
                Unit
            } finally {
                runCatching { stopKoin() }
                Settings.replaceSharedForTest(null)
                database.close()
            }
        }

    private suspend fun verifyDeniedRemoval(
        playlistId: String,
        songs: List<SongItem>,
        audio: Path,
    ) {
        val directory = audio.parent
        val probe = Files.createFile(directory.resolve("delete-probe"))
        val originalPermissions = Files.getPosixFilePermissions(directory)
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        val fatal = AtomicReference<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val logDirectory = XdgUtils.getXdgStateHome().resolve("test-logs")
        val log = logDirectory.resolve("app.log")
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-x------"))
            assertTrue(Files.isReadable(audio))
            assertFailsWith<AccessDeniedException> { Files.delete(probe) }
            Thread.setDefaultUncaughtExceptionHandler(
                FatalCrashHandler(originalHandler) { _, error -> fatal.set(error) },
            )
            AppLogger.initialize(scope, logDirectory)

            DownloadManager.removePlaylistDownloads(playlistId, songs)
            withTimeout(10_000) {
                while (fatal.get() == null) {
                    AppLogger.flush()
                    if (log.isFile && "[DownloadManager] Failed to delete downloads" in log.readText()) break
                    delay(10)
                }
            }

            assertNull(fatal.get(), "A normal file deletion error must not reach the fatal crash handler")
            assertTrue(DownloadManager.isDownloaded(songs.last().id))
            assertTrue(Files.isRegularFile(audio))
        } finally {
            AppLogger.shutdown()
            Thread.setDefaultUncaughtExceptionHandler(originalHandler)
            scope.cancel()
            Files.setPosixFilePermissions(directory, originalPermissions)
        }
    }

    private companion object {
        const val CHILD_PROCESS = "METROLIST_PLAYLIST_OWNERSHIP_TEST_CHILD"
    }
}

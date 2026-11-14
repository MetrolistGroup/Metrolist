package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.PlaybackStatePersistence
import com.metrolist.desktop.state.playback.PlaybackStateStore
import com.metrolist.desktop.state.playback.PlaybackStateWrite
import com.metrolist.desktop.state.playback.PlaybackStateWriteQueue
import com.metrolist.desktop.state.playback.localPlaybackResource
import com.metrolist.desktop.state.playback.validatedLocalMediaUrl
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.UniversalPlaylistParser
import com.metrolist.shared.youtube.extraction.models.toContentHints
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatePersistenceTest {
    @Test
    fun advancingPositionDoesNotRewriteQueueAndStaleCheckpointCannotChangeAnotherQueue() {
        val store = InMemoryPlaybackStateStore()
        val persistence = PlaybackStatePersistence(store)
        val queue = snapshot()
        persistence.persist(queue, 1000, RepeatMode.OFF)
        persistence.persist(queue, 6000, RepeatMode.OFF)
        assertEquals(1, store.writeCount)
        assertEquals(6000L, PlaybackStatePersistence(store).restore()?.positionMs)
        val oldCheckpoint = store.persistentPlaybackPosition
        persistence.persist(queue.copy(displayedItems = listOf(song("other")), originalItems = listOf(song("other"))), 0, RepeatMode.OFF)
        store.persistentPlaybackPosition = oldCheckpoint
        assertEquals(0L, PlaybackStatePersistence(store).restore()?.positionMs)
        assertEquals(2, store.writeCount)
    }

    @Test
    fun importedLocalPlaylistEntryResolvesThroughDatabaseMappingToLocalPlayback() =
        runBlocking {
            val directory = Files.createTempDirectory("metrolist-local-import-playback")
            val media = Files.createFile(directory.resolve("song.audio"))
            val database = getDatabaseBuilder(directory.resolve("music.db").toFile()).build()
            val client = HttpClient()
            try {
                val tracks =
                    UniversalPlaylistParser.parse("#EXTM3U\n#EXTINF:-1,Artist - Song\nsong.audio") { reference ->
                        validatedLocalMediaUrl(reference, directory)
                    }
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val songs = manager.resolveImportTracks(tracks, null, null).map { it.second }.distinctBy { it.id }
                val playlist =
                    manager
                        .import("Local files", songs, PlaylistImportManager.Destination.NewLocal)
                        .getOrThrow()
                        .playlist
                val restored =
                    database
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .single()
                        .song
                        .toSongItem()
                val resource = localPlaybackResource(restored.id, restored.toContentHints())

                assertTrue(restored.isLocal)
                assertEquals(media.toRealPath().toUri().toString(), restored.endpointParams)
                assertEquals(media.toRealPath().toUri().toString(), resource?.url)
                assertEquals("device-file", resource?.profileId)
            } finally {
                client.close()
                database.close()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun restoredLocalMediaMustResolveToAnExistingLocalFile() {
        val directory = Files.createTempDirectory("metrolist local media")
        val file = Files.createFile(directory.resolve("song with spaces.audio"))
        try {
            val expected = file.toRealPath().toUri().toString()
            val nativePath = file.toString()

            assertEquals(expected, validatedLocalMediaUrl(nativePath))
            assertEquals(expected, validatedLocalMediaUrl(file.toUri().toString()))
            assertEquals(expected, validatedLocalMediaUrl(file.fileName.toString(), file.parent))

            val parsed =
                UniversalPlaylistParser.parse("#EXTM3U\n#EXTINF:-1,Artist - Song\n$nativePath") { reference ->
                    validatedLocalMediaUrl(reference)
                }
            assertEquals(expected, parsed.single().directReference)
            assertTrue(parsed.single().isResolvedLocalReference)
            val csv =
                UniversalPlaylistParser.parse("title,artist,url\nLocal song,Local artist,$nativePath") { reference ->
                    validatedLocalMediaUrl(reference)
                }
            assertEquals(expected, csv.single().directReference)
            assertTrue(csv.single().isResolvedLocalReference)

            if (File.separatorChar != '\\') {
                assertNull(validatedLocalMediaUrl("C:\\\\Music\\\\song with spaces.mp3"))
            }
            assertNull(validatedLocalMediaUrl("https://attacker.invalid/audio"))
            assertNull(validatedLocalMediaUrl("file://attacker.invalid/share/audio"))
            assertNull(validatedLocalMediaUrl(file.resolveSibling("missing.audio").toString()))
            assertTrue(Files.isRegularFile(file))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun finalPlaybackStateCannotBeOverwrittenByPendingOrConcurrentSubmissions() =
        runTest {
            val writtenPositions = mutableListOf<Long>()
            val writes =
                PlaybackStateWriteQueue(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler), persist = {
                    writtenPositions += it.positionMs
                })
            writes.submit(PlaybackStateWrite(snapshot(), 1L, RepeatMode.OFF))
            assertTrue(writtenPositions.isEmpty())
            runCurrent()
            writes.submit(PlaybackStateWrite(snapshot(), 2L, RepeatMode.OFF))
            writes.submit(PlaybackStateWrite(snapshot(), 3L, RepeatMode.OFF))
            val flush =
                async(start = CoroutineStart.UNDISPATCHED) {
                    writes.flush(PlaybackStateWrite(snapshot(), 4L, RepeatMode.OFF))
                }
            writes.submit(PlaybackStateWrite(snapshot(), 5L, RepeatMode.OFF))
            val repeatedFlush =
                async(start = CoroutineStart.UNDISPATCHED) {
                    writes.flush(PlaybackStateWrite(snapshot(), 6L, RepeatMode.OFF))
                }
            flush.await()
            repeatedFlush.await()
            assertEquals(listOf(1L, 2L, 3L, 4L), writtenPositions)
        }

    @Test
    fun finalPlaybackStatePersistsAfterWorkerScopeCancellation() =
        runTest {
            backgroundScope.cancel()
            val writtenPositions = mutableListOf<Long>()
            val writes =
                PlaybackStateWriteQueue(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler), persist = {
                    writtenPositions += it.positionMs
                })
            writes.submit(PlaybackStateWrite(snapshot(), 1L, RepeatMode.OFF))
            writes.flush(PlaybackStateWrite(snapshot(), 2L, RepeatMode.OFF))
            assertEquals(listOf(2L), writtenPositions)
        }

    @Test
    fun asynchronousPersistenceFailureIsReportedAfterFinalStateIsWritten() =
        runTest {
            val writtenPositions = mutableListOf<Long>()
            val writes =
                PlaybackStateWriteQueue(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler), persist = {
                    writtenPositions += it.positionMs
                    if (it.positionMs == 1L) error("write failed")
                })
            writes.submit(PlaybackStateWrite(snapshot(), 1L, RepeatMode.OFF))
            runCurrent()
            assertFailsWith<IllegalStateException> {
                writes.flush(PlaybackStateWrite(snapshot(), 2L, RepeatMode.OFF))
            }
            assertEquals(listOf(1L, 2L), writtenPositions)
        }

    @Test
    fun unchangedStateIsWrittenOnlyOnce() {
        val store = InMemoryPlaybackStateStore()
        val persistence = PlaybackStatePersistence(store)
        val snapshot = snapshot()

        persistence.persist(snapshot, positionMs = 1200L, repeatMode = RepeatMode.ALL)
        persistence.persist(snapshot, positionMs = 1200L, repeatMode = RepeatMode.ALL)

        assertEquals(1, store.writeCount)
    }

    @Test
    fun disabledPersistenceClearsStoredState() {
        val store = InMemoryPlaybackStateStore(storedState = "old")
        val persistence = PlaybackStatePersistence(store)
        store.persistentQueue = false

        persistence.persist(snapshot(), positionMs = 0L, repeatMode = RepeatMode.OFF)

        assertEquals("", store.persistentPlaybackState)
        assertNull(persistence.restore())
    }

    @Test
    fun invalidAndEmptyStatesAreCleared() {
        val store = InMemoryPlaybackStateStore(storedState = "not json")
        val persistence = PlaybackStatePersistence(store)

        assertNull(persistence.restore())
        assertEquals("", store.persistentPlaybackState)

        store.persistentPlaybackState = "{\"queue\":{}}"
        assertNull(persistence.restore())
        assertEquals("", store.persistentPlaybackState)
    }

    @Test
    fun restoreNormalizesQueueAndRememberedMetadata() {
        val store = InMemoryPlaybackStateStore(rememberShuffleAndRepeat = false)
        val persistence = PlaybackStatePersistence(store)
        val first = song("first")
        val second = song("second")
        persistence.persist(
            MusicQueueSnapshot(
                displayedItems = listOf(first, second, first),
                originalItems = listOf(first, second, first),
                currentIndex = 0,
                isShuffled = true,
                originalIndices = listOf(2, 1, 0),
            ),
            positionMs = 1234L,
            repeatMode = RepeatMode.ALL,
        )

        val restored = persistence.restore()!!

        assertFalse(restored.queue.isShuffled)
        assertEquals(listOf("first", "first", "second"), restored.queue.displayedItems.map { it.id })
        assertEquals(0, restored.queue.currentIndex)
        assertEquals(RepeatMode.OFF, restored.repeatMode)
        assertEquals(1234L, restored.positionMs)
    }

    @Test
    fun trackAdvanceWritesOnlyCheckpointAndOldFormatStillRestores() {
        val store = InMemoryPlaybackStateStore()
        store.persistentPlaybackState =
            """{"queue":{"displayedItems":[{"id":"old","title":"Old","thumbnail":null,"artists":[],"album":null}],
                "currentIndex":0},"positionMs":123}"""
        assertEquals(
            "old",
            PlaybackStatePersistence(store)
                .restore()
                ?.queue
                ?.displayedItems
                ?.single()
                ?.id,
        )
        assertEquals(123L, PlaybackStatePersistence(store).restore()?.positionMs)
        val queue = MusicQueueSnapshot(listOf(song("one"), song("two")), listOf(song("one"), song("two")), 0)
        val persistence = PlaybackStatePersistence(store)
        persistence.persist(queue, 100, RepeatMode.OFF)
        val writes = store.writeCount
        persistence.persist(queue.copy(currentIndex = 1), 900, RepeatMode.ONE)
        assertEquals(writes, store.writeCount)
        val restored = PlaybackStatePersistence(store).restore()!!
        assertEquals(1, restored.queue.currentIndex)
        assertEquals(900L, restored.positionMs)
        assertEquals(RepeatMode.ONE, restored.repeatMode)
        store.persistentPlaybackPosition = ""
        assertEquals(0, PlaybackStatePersistence(store).restore()?.queue?.currentIndex)
        assertEquals(100L, PlaybackStatePersistence(store).restore()?.positionMs)
    }

    private fun snapshot() =
        MusicQueueSnapshot(
            displayedItems = listOf(song("song")),
            originalItems = listOf(song("song")),
            currentIndex = 0,
            isShuffled = false,
        )

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}

private class InMemoryPlaybackStateStore(
    override var persistentQueue: Boolean = true,
    private var storedState: String = "",
    override var rememberShuffleAndRepeat: Boolean = true,
) : PlaybackStateStore {
    override var persistentPlaybackPosition: String = ""
    var writeCount = 0

    override var persistentPlaybackState: String
        get() = storedState
        set(value) {
            writeCount++
            storedState = value
        }
}

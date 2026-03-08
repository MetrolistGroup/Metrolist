package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalResourceApi::class)
class PlaylistReorderTest {
    @Before
    fun initializeResources() {
        setResourceReaderAndroidContext(RuntimeEnvironment.getApplication())
        checkNotNull(
            javaClass.classLoader?.getResource("composeResources/com.metrolist.shared.generated.resources/values/strings.commonMain.cvr"),
        )
    }

    @Test
    fun overlappingCommitsKeepBothRemoteMovesAndLocalOrder() = checkOverlappingCommits()

    @Test
    fun clearingViewModelFinishesBothRunningAndQueuedReorders() = checkOverlappingCommits(clearViewModel = true)

    @Test
    fun clearingViewModelAlsoCommitsTheLastDragPreview() = checkOverlappingCommits(clearViewModel = true, commitSecondMove = false)

    private fun checkOverlappingCommits(
        clearViewModel: Boolean = false,
        commitSecondMove: Boolean = true,
    ) = runTest {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val db =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    MusicDatabase::class.java,
                ).setDriver(AndroidSQLiteDriver())
                .build()
        val store = ViewModelStore()
        try {
            val playlist = PlaylistEntity(id = "LPtest", name = "Test", isLocal = true)
            db.dao().insert(playlist)
            listOf("a", "b", "c", "d").forEachIndexed { index, id ->
                db.dao().insert(SongEntity(id = id, title = id))
                db.dao().insert(
                    PlaylistSongMap(id = index + 1, playlistId = playlist.id, songId = id, position = index, setVideoId = "set-$id"),
                )
            }
            val lookupStarted = CompletableDeferred<Unit>()
            val releaseLookup = CompletableDeferred<Unit>()
            val calls = mutableListOf<Pair<String, String?>>()
            val model =
                PlaylistViewModel(
                    playlistId = playlist.id,
                    database = db,
                    applicationScope = backgroundScope,
                    findLocalPlaylist = { id ->
                        lookupStarted.complete(Unit)
                        releaseLookup.await()
                        db.dao().playlist(id).first()
                    },
                    moveRemoteSong = { id, song, successor ->
                        assertEquals("remote", id)
                        calls += song to successor
                        Result.success(Unit)
                    },
                )
            store.put("playlist", model)
            awaitPlaylistState { model.songs.value.size == 4 }
            db.dao().update(playlist.copy(browseId = "remote"))

            model.moveSong(0, 2)
            model.commitPendingReorder()
            lookupStarted.await()
            model.moveSong(3, 0)
            if (commitSecondMove) model.commitPendingReorder()
            assertEquals(listOf("d", "b", "c", "a"), model.songs.value.map { it.id })
            if (clearViewModel) store.clear()
            releaseLookup.complete(Unit)

            val final = db.dao().playlistSongs(playlist.id).first { songs -> songs.map { it.song.id } == listOf("d", "b", "c", "a") }
            assertEquals(listOf("set-a" to "set-d", "set-d" to "set-b"), calls)
            assertEquals(listOf(0, 1, 2, 3), final.map { it.map.position })
        } finally {
            store.clear()
            db.close()
            Dispatchers.resetMain()
            Settings.replaceSharedForTest(null)
        }
    }

    @Test
    fun rejectedRemoteMoveDoesNotChangeCachedMembershipPositions() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val db =
                Room
                    .inMemoryDatabaseBuilder(
                        RuntimeEnvironment.getApplication(),
                        MusicDatabase::class.java,
                    ).setDriver(AndroidSQLiteDriver())
                    .build()
            val store = ViewModelStore()
            try {
                val playlist = PlaylistEntity(id = "LPtest", name = "Test", isLocal = true)
                db.dao().insert(playlist)
                listOf("a", "b").forEachIndexed { index, id ->
                    db.dao().insert(SongEntity(id = id, title = id))
                    db.dao().insert(
                        PlaylistSongMap(id = index + 1, playlistId = playlist.id, songId = id, position = index, setVideoId = "set-$id"),
                    )
                }
                val attempted = CompletableDeferred<Unit>()
                val model =
                    PlaylistViewModel(
                        playlistId = playlist.id,
                        database = db,
                        applicationScope = backgroundScope,
                        findLocalPlaylist = { id ->
                            db
                                .dao()
                                .playlist(id)
                                .first()
                                ?.let { it.copy(playlist = it.playlist.copy(browseId = "remote")) }
                        },
                        moveRemoteSong = { _, _, _ ->
                            attempted.complete(Unit)
                            Result.failure(IllegalStateException("offline"))
                        },
                    )
                store.put("playlist", model)
                awaitPlaylistState { model.songs.value.size == 2 }
                model.moveSong(0, 1)
                model.commitPendingReorder()
                attempted.await()
                awaitPlaylistState { model.operationState.value.message != null }
                assertEquals(
                    listOf("a", "b"),
                    db
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .map { it.song.id },
                )
                assertTrue(model.operationState.value.message != null)
            } finally {
                store.clear()
                db.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun selectedDuplicateOccurrencesPersistLocallyAndRemotelyWithPartialFailureRecovery() =
        runTest {
            for (mode in listOf("local", "remote", "failure")) {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                Dispatchers.setMain(StandardTestDispatcher(testScheduler))
                val db =
                    Room
                        .inMemoryDatabaseBuilder(
                            RuntimeEnvironment.getApplication(),
                            MusicDatabase::class.java,
                        ).setDriver(AndroidSQLiteDriver())
                        .build()
                val store = ViewModelStore()
                try {
                    val playlist = PlaylistEntity(id = "LPtest", name = "Test", isLocal = true)
                    db.dao().insert(playlist)
                    db.dao().insert(SongEntity(id = "duplicate", title = "Song"))
                    for (id in 1..5) {
                        db.dao().insert(
                            PlaylistSongMap(
                                id = id,
                                playlistId = playlist.id,
                                songId = "duplicate",
                                position = id - 1,
                                setVideoId = "set-$id",
                            ),
                        )
                    }
                    val calls = mutableListOf<Pair<String, String?>>()
                    val model =
                        PlaylistViewModel(
                            playlistId = playlist.id,
                            database = db,
                            applicationScope = backgroundScope,
                            findLocalPlaylist = { id -> db.dao().playlist(id).first() },
                            moveRemoteSong = { _, song, successor ->
                                calls += song to successor
                                if (mode == "failure" &&
                                    calls.size == 2
                                ) {
                                    Result.failure(IllegalStateException("offline"))
                                } else {
                                    Result.success(Unit)
                                }
                            },
                        )
                    store.put("playlist", model)
                    awaitPlaylistState { model.songs.value.size == 5 }
                    if (mode == "local") {
                        model.moveSong(0, 4, setOf(0, 2))
                        model.loadPlaylist()
                        awaitPlaylistState { model.songs.value.map { it.playlistSetVideoId } == (1..5).map { "set-$it" } }
                        model.commitPendingReorder()
                        assertEquals(
                            (1..5).toList(),
                            db
                                .dao()
                                .playlistSongs(playlist.id)
                                .first()
                                .map { it.map.id },
                        )
                    }
                    if (mode != "local") db.dao().update(playlist.copy(browseId = "remote"))
                    model.moveSong(1, 4, setOf(1, 3))
                    assertEquals(
                        listOf("set-1", "set-3", "set-5", "set-2", "set-4"),
                        model.songs.value.map { it.playlistSetVideoId },
                    )
                    assertTrue(calls.isEmpty())
                    model.commitPendingReorder()
                    val expected = if (mode == "failure") listOf(1, 2, 3, 5, 4) else listOf(1, 3, 5, 2, 4)
                    if (mode == "failure") awaitPlaylistState { model.operationState.value.message != null }
                    val saved = db.dao().playlistSongs(playlist.id).first { songs -> songs.map { it.map.id } == expected }
                    assertEquals(listOf(0, 1, 2, 3, 4), saved.map { it.map.position })
                    assertEquals(if (mode == "local") emptyList() else listOf("set-4" to null, "set-2" to "set-4"), calls)
                } finally {
                    store.clear()
                    db.close()
                    Dispatchers.resetMain()
                    Settings.replaceSharedForTest(null)
                }
            }
        }
}

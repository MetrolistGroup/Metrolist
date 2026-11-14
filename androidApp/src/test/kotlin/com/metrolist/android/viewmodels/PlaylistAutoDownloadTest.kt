package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.db.executeTestSql
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.PlaylistPresentationOptions
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.setResourceReaderAndroidContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalResourceApi::class)
class PlaylistAutoDownloadTest {
    @Test
    fun successfulLikeDownloadsFullMembershipDespiteSearchAndUnlikeDoesNotDownload() =
        withPlaylist { model, database ->
            Settings.shared.autoDownloadOnLike = true
            model.presentationOptions.value = PlaylistPresentationOptions(query = "a")
            val batches = mutableListOf<List<String>>()
            checkNotNull(
                model.toggleFavorite { songs ->
                    assertNotNull(database.dao().findPlaylistEntity("LPtest")?.bookmarkedAt)
                    batches += songs.map { it.id }
                },
            ).join()
            assertTrue(model.isFavorite.value)
            assertEquals(listOf(listOf("a", "b", "c")), batches)

            checkNotNull(model.toggleFavorite { batches += it.map { song -> song.id } }).join()
            assertEquals(1, batches.size)
            assertFalse(model.isFavorite.value)
            assertNull(database.dao().findPlaylistEntity("LPtest")?.bookmarkedAt)
        }

    @Test
    fun disabledSettingStillSavesLikeWithoutDownloading() =
        withPlaylist { model, database ->
            Settings.shared.autoDownloadOnLike = false
            var downloaded = false
            checkNotNull(model.toggleFavorite { downloaded = true }).join()
            assertFalse(downloaded)
            assertTrue(model.isFavorite.value)
            assertNotNull(database.dao().findPlaylistEntity("LPtest")?.bookmarkedAt)
            assertNull(model.operationState.value.message)
        }

    @Test
    fun failedBookmarkDoesNotDownloadOrPublishALike() =
        withPlaylist { model, database ->
            Settings.shared.autoDownloadOnLike = true
            database.executeTestSql(
                "CREATE TRIGGER reject_bookmark BEFORE UPDATE ON playlist BEGIN SELECT RAISE(ABORT, 'rejected'); END",
            )
            var downloaded = false
            checkNotNull(model.toggleFavorite { downloaded = true }).join()
            assertFalse(downloaded)
            assertFalse(model.isFavorite.value)
            assertNull(database.dao().findPlaylistEntity("LPtest")?.bookmarkedAt)
            assertNotNull(model.operationState.value.message)
        }

    @Test
    fun downloadFailurePreservesSuccessfulBookmarkAndReportsFailure() =
        withPlaylist { model, database ->
            Settings.shared.autoDownloadOnLike = true
            checkNotNull(model.toggleFavorite { throw IllegalStateException("download failed") }).join()
            assertTrue(model.isFavorite.value)
            assertNotNull(database.dao().findPlaylistEntity("LPtest")?.bookmarkedAt)
            assertNotNull(model.operationState.value.message)
        }

    @Test
    fun repeatedTapWhileDownloadIsStartingDoesNotStartAnotherBookmarkOrDownload() =
        withPlaylist { model, _ ->
            Settings.shared.autoDownloadOnLike = true
            val downloadStarted = CompletableDeferred<Unit>()
            val finishDownload = CompletableDeferred<Unit>()
            val job =
                checkNotNull(
                    model.toggleFavorite {
                        downloadStarted.complete(Unit)
                        finishDownload.await()
                    },
                )
            try {
                downloadStarted.await()
                assertNull(model.toggleFavorite { error("Duplicate download") })
                assertTrue(model.isFavorite.value)
            } finally {
                finishDownload.complete(Unit)
                job.join()
            }
        }

    @Test
    fun cancellationIsNotReportedAsADownloadFailure() =
        withPlaylist { model, _ ->
            Settings.shared.autoDownloadOnLike = true
            val job = checkNotNull(model.toggleFavorite { throw CancellationException() })
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(model.isFavorite.value)
            assertNull(model.operationState.value.message)
        }

    private fun withPlaylist(test: suspend (PlaylistViewModel, MusicDatabase) -> Unit) =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            setResourceReaderAndroidContext(context)
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val store = ViewModelStore()
            try {
                database.dao().insert(PlaylistEntity(id = "LPtest", name = "Test", isLocal = true, bookmarkedAt = null))
                listOf("a", "b", "c").forEachIndexed { position, id ->
                    database.dao().insert(SongEntity(id = id, title = id))
                    database.dao().insert(PlaylistSongMap(playlistId = "LPtest", songId = id, position = position))
                }
                val model = PlaylistViewModel(playlistId = "LPtest", database = database, applicationScope = backgroundScope)
                store.put("playlist", model)
                awaitPlaylistState { model.songs.value.size == 3 }
                test(model, database)
            } finally {
                val viewModelJob = store["playlist"]?.viewModelScope?.coroutineContext?.job
                store.clear()
                viewModelJob?.join()
                backgroundScope.coroutineContext.job.cancelAndJoin()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }
}

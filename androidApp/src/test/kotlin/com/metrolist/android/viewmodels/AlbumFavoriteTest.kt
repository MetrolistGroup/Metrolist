package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModelStore
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.android.playback.SongDownloadManager
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumFavoriteTest {
    @Test
    fun retainedHeadersFollowDatabaseChangesAndStopObservingPreviousAlbums() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            val store = ViewModelStore()
            try {
                val album = AlbumEntity(id = "album", title = "Album", songCount = 0, duration = 0)
                database.dao().upsert(album)
                val downloads = SongDownloadManager(context, AudioCacheManager(context, database), database)
                val repository = YouTubeRepository(InnerTube(client))
                val first = AlbumViewModel(album.id, database, downloads, backgroundScope, repository)
                val second = AlbumViewModel(album.id, database, downloads, backgroundScope, repository)
                store.put("first", first)
                store.put("second", second)
                awaitPlaylistState { !first.isLoading.value && !second.isLoading.value }
                assertFalse(first.isFavorite.value)
                val saved = album.copy(bookmarkedAt = LocalDateTime(2026, 11, 7, 0, 0))
                database.dao().upsert(saved)
                first.isFavorite.first { it }
                second.isFavorite.first { it }
                database.dao().upsert(AlbumEntity(id = "other", title = "Other", songCount = 0, duration = 0))
                first.loadAlbum("other")
                first.isFavorite.first { !it }
                database.dao().upsert(album)
                second.isFavorite.first { !it }
                database.dao().upsert(saved)
                second.isFavorite.first { it }
                assertFalse(first.isFavorite.value)
            } finally {
                store.clear()
                client.close()
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }
}

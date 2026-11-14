package com.metrolist.android.playback

import android.content.ContextWrapper
import android.content.res.Resources
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.R
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutomotivePlaylistActionsTest {
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                    override fun getString(id: Int): String = id.toString()

                    override fun getString(
                        id: Int,
                        vararg formatArgs: Any,
                    ): String = "$id:${formatArgs.joinToString()}"
                }

            override fun getResources(): Resources = labels
        }
    }
    private val song = SongItem(id = "song", title = "Song", artists = emptyList(), album = null, thumbnail = null)

    @Test
    fun addsOnlyToSelectedPlaylistAndRejectsDuplicateTaps() =
        withDatabase { database, repository ->
            val selected = PlaylistEntity(id = "selected", name = "Selected", isLocal = true)
            val other = PlaylistEntity(id = "other", name = "Other", isLocal = true)
            database.dao().insert(selected)
            database.dao().insert(other)

            assertEquals(
                SessionResult.RESULT_SUCCESS,
                addAutomotiveSongToPlaylist(context, database, repository, selected.id, song).resultCode,
            )
            val duplicate = addAutomotiveSongToPlaylist(context, database, repository, selected.id, song)
            assertEquals(SessionError.ERROR_BAD_VALUE, duplicate.resultCode)
            assertEquals(context.getString(R.string.song_already_in_playlist, song.title), duplicate.sessionError?.message)
            assertEquals(
                listOf(song.id),
                database
                    .dao()
                    .playlistSongs(selected.id)
                    .first()
                    .map { it.song.id },
            )
            assertTrue(
                database
                    .dao()
                    .playlistSongs(other.id)
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun missingSongUnsetDeletedAndReadOnlyDestinationsDoNotWrite() =
        withDatabase { database, repository ->
            val playlist = PlaylistEntity(id = "readonly", name = "Read only", isEditable = false)
            database.dao().insert(playlist)
            for (id in listOf("", "deleted", playlist.id)) {
                val result = addAutomotiveSongToPlaylist(context, database, repository, id, song)
                assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
                assertEquals(context.getString(R.string.android_auto_quick_add_playlist_not_set), result.sessionError?.message)
            }
            val missingSong = addAutomotiveSongToPlaylist(context, database, repository, playlist.id, null)
            assertEquals(SessionError.ERROR_BAD_VALUE, missingSong.resultCode)
            assertNull(database.dao().getSongById(song.id))
            assertTrue(
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun remoteFailureDoesNotReportSuccessOrWriteLocalMembership() =
        withDatabase(
            MockEngine {
                respond("{}", HttpStatusCode.InternalServerError, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { database, repository ->
            val playlist = PlaylistEntity(id = "remote", name = "Remote", browseId = "VLPLremote")
            database.dao().insert(playlist)
            val result = addAutomotiveSongToPlaylist(context, database, repository, playlist.id, song)
            assertEquals(SessionError.ERROR_IO, result.resultCode)
            assertEquals(context.getString(R.string.playlist_update_failed), result.sessionError?.message)
            assertTrue(
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .isEmpty(),
            )
            assertNull(database.dao().getSongById(song.id))
        }

    @Test
    fun cancellationPropagatesWithoutWritingMembership() =
        withDatabase(MockEngine { throw CancellationException("Cancelled") }) { database, repository ->
            val playlist = PlaylistEntity(id = "remote", name = "Remote", browseId = "VLPLremote")
            database.dao().insert(playlist)
            try {
                addAutomotiveSongToPlaylist(context, database, repository, playlist.id, song)
                error("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(
                    database
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .isEmpty(),
                )
            }
        }

    private fun withDatabase(
        engine: MockEngine = MockEngine { error("Local actions must not use the network") },
        block: suspend (MusicDatabase, YouTubeRepository) -> Unit,
    ) = runBlocking {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
        val client = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test-session" }
        try {
            block(database, YouTubeRepository(innerTube))
        } finally {
            client.close()
            database.close()
            Settings.replaceSharedForTest(null)
        }
    }
}

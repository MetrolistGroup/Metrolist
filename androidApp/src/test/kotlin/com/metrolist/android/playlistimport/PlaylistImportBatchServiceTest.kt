package com.metrolist.android.playlistimport

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTube
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class PlaylistImportBatchServiceTest {
    @Test
    fun signedOutSpotifyBatchCreatesLocalPlaylists() = verifyBatch(ImportSourceType.SPOTIFY, signedIn = false)

    @Test
    fun signedOutAppleMusicBatchCreatesLocalPlaylists() = verifyBatch(ImportSourceType.APPLE_MUSIC, signedIn = false)

    @Test
    fun signedInBatchCreatesSyncedPlaylists() = verifyBatch(ImportSourceType.SPOTIFY, signedIn = true)

    private fun verifyBatch(
        provider: ImportSourceType,
        signedIn: Boolean,
    ) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val context = notificationContext(app)
        val database =
            Room
                .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                .setDriver(AndroidSQLiteDriver())
                .build()
        val requests = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val path = request.url.encodedPath
                    val response =
                        when {
                            path.endsWith("/search") -> {
                                val query = body.getValue("query").jsonPrimitive.content
                                val playlist = playlistTitles.single { query.startsWith("$it song") }
                                requests += "search:$playlist"
                                searchResult(playlist)
                            }

                            path.endsWith("/playlist/create") -> {
                                val title = body.getValue("title").jsonPrimitive.content
                                requests += "create:$title"
                                """{"playlistId":"PL$title"}"""
                            }

                            path.endsWith("/browse/edit_playlist") -> {
                                val id = body.getValue("playlistId").jsonPrimitive.content
                                requests += "edit:$id"
                                """{"status":"STATUS_SUCCEEDED"}"""
                            }

                            else -> {
                                error("Unexpected request: $path")
                            }
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val service = Robolectric.buildService(AndroidPlaylistImportService::class.java).get()
        ReflectionHelpers.setField(service, "mBase", context)
        val previousCookie = GlobalInnerTube.instance.cookie
        val cookie = if (signedIn) "SAPISID=test" else null
        GlobalYouTube.updateCookie(cookie)
        Settings.replaceSharedForTest(Settings(MapSettings()))
        service.onCreate()
        try {
            val repository = YouTubeRepository(InnerTube(client).apply { this.cookie = cookie })
            assertEquals(signedIn, repository.canManagePlaylists())
            val playlists =
                playlistTitles.map { title ->
                    SpotifyImportManager.LoadedPlaylist(
                        uri = title,
                        name = title,
                        image = null,
                        tracks = listOf(SpotifyImportManager.ImportTrack("$title song", "Artist", "Album", 120L)),
                        reachedTrackLimit = false,
                    )
                }
            val imports = preparePlaylistBatch(playlists, provider.displayName, provider)
            val caller =
                async(start = CoroutineStart.UNDISPATCHED) {
                    AndroidPlaylistImportTransfer.startBatch(context, PlaylistImportManager(database, repository), imports)
                }
            val intent = checkNotNull(shadowOf(app).nextStartedService)
            service.onStartCommand(intent, 0, 1)
            val result = withTimeout(15_000) { caller.await() }
            val saved =
                database
                    .dao()
                    .allPlaylists()
                    .first()
                    .sortedBy { it.name }
            assertTrue(
                "Expected two imported playlists; saved=${saved.size}, failure=${result.exceptionOrNull()?.message}",
                result.isSuccess,
            )
            assertEquals(2, result.getOrThrow().importedTracks)
            assertEquals(playlistTitles, saved.map { it.name })
            assertEquals(playlistTitles.map { !signedIn }, saved.map { it.isLocal })
            assertEquals(playlistTitles.map { if (signedIn) "PL$it" else null }, saved.map { it.browseId })
            for (playlist in saved) {
                assertEquals(
                    listOf(songId(playlist.name)),
                    database
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .map { it.song.id },
                )
            }
            val expectedRequests =
                playlistTitles.flatMap { title ->
                    if (signedIn) listOf("search:$title", "create:$title", "edit:PL$title") else listOf("search:$title")
                }
            assertEquals(expectedRequests, requests)
        } finally {
            service.onDestroy()
            withTimeout(15_000) {
                ReflectionHelpers
                    .getField<CoroutineScope>(service, "serviceScope")
                    .coroutineContext.job
                    .cancelAndJoin()
            }
            PlaylistImportStatusManager.activeImport.value?.let { PlaylistImportStatusManager.clear(it.id) }
            Settings.replaceSharedForTest(null)
            GlobalYouTube.updateCookie(previousCookie)
            client.close()
            database.close()
        }
    }

    private fun searchResult(title: String): String =
        """
        {"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[
          {"musicResponsiveListItemRenderer":{
            "flexColumns":[
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title song"}]}}},
              {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                {"text":"Artist","navigationEndpoint":{"browseEndpoint":{"browseId":"artist-id"}}}
              ]}}}
            ],
            "playlistItemData":{"videoId":"${songId(title)}"}
          }}
        ]}}]}}}
        """.trimIndent()

    private fun songId(title: String): String = if (title == "First") "firstSong01" else "secondSong1"

    private val playlistTitles = listOf("First", "Second")

    private fun notificationContext(app: Application) =
        object : ContextWrapper(app) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(app.assets, app.resources.displayMetrics, app.resources.configuration) {
                    override fun getString(id: Int): String = id.toString()

                    override fun getString(
                        id: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()

                    override fun getQuantityString(
                        id: Int,
                        quantity: Int,
                    ): String = id.toString()

                    override fun getQuantityString(
                        id: Int,
                        quantity: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()
                }

            override fun getResources(): Resources = labels
        }
}

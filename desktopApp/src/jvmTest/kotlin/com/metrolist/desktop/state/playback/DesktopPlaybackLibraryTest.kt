package com.metrolist.desktop.state.playback

import com.metrolist.desktop.history.HistoryStat
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.cachedRemotePlaylistSections
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.loadAndCachePlaylistPage
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.PlaylistAddBatchResult
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopPlaybackLibraryTest {
    @Test
    fun addingToAnUnselectedCachedPlaylistUpdatesItsStoredMembership() = checkUnselectedCachedPlaylistAdd(failSecond = false)

    @Test
    fun partialAdditionToAnUnselectedCachedPlaylistKeepsAcknowledgedMembership() = checkUnselectedCachedPlaylistAdd(failSecond = true)

    private fun checkUnselectedCachedPlaylistAdd(failSecond: Boolean) =
        runBlocking {
            val previousSettings = Settings.sharedOverrideForTest()
            Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
            Dispatchers.setMain(StandardTestDispatcher())
            val previousSelection = PlaylistAlbumState.selectedPlaylistId
            PlaylistAlbumState.selectedPlaylistId = null
            val directory = Files.createTempDirectory("unselected-playlist-add")
            val database = getDatabaseBuilder(directory.resolve("songs.db").toFile()).build()
            val original = SongItem("original", "Original", null, emptyList(), null, playlistSetVideoId = "set-original")
            val additions = listOf("first", "second").map { SongItem(it, it, null, emptyList(), null) }
            val serverSongs = mutableListOf(original)
            var browseRequests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val response =
                            if (request.url.encodedPath.endsWith("/browse/edit_playlist")) {
                                val action =
                                    Json
                                        .parseToJsonElement(
                                            (request.body as TextContent).text,
                                        ).jsonObject["actions"]!!
                                        .jsonArray
                                        .single()
                                        .jsonObject
                                val id = action["addedVideoId"]!!.jsonPrimitive.content
                                if (failSecond && id == "second") {
                                    """{"status":"STATUS_FAILED"}"""
                                } else {
                                    serverSongs += additions.single { it.id == id }.copy(playlistSetVideoId = "set-$id")
                                    """{"status":"STATUS_SUCCEEDED"}"""
                                }
                            } else {
                                browseRequests++
                                val rows =
                                    serverSongs.joinToString(",") { song ->
                                        """{"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"${song.id}","playlistSetVideoId":"${song.playlistSetVideoId}"},
                        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"simpleText":"${song.title}"}}}]}}"""
                                    }
                                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Playlist"}}}}},
                    "contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$rows]}}]}}}"""
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            val tube = InnerTube(client, retryDelay = {}).apply { replaceSession("SAPISID=test", "visitor", "account", "0", true) }
            try {
                database.cacheRemotePlaylist("PLtarget", PlaylistItem("PLtarget", "Playlist", null, isEditable = true), listOf(original))
                val repository = YouTubeRepository(tube)
                val library = DesktopPlaybackLibrary(scope = this, database = { database }, repository = { repository })
                val completed = CompletableDeferred<Result<Unit>>()
                library.addToPlaylist("PLtarget", additions) { completed.complete(it) }
                val result = completed.await()
                assertEquals(!failSecond, result.isSuccess, result.toString())
                assertNull(PlaylistAlbumState.selectedPlaylistId)

                val expected = listOf(original) + additions.take(if (failSecond) 1 else 2)
                assertEquals(
                    expected.map { it.id },
                    database
                        .dao()
                        .playlistSongs("PLtarget")
                        .first()
                        .map { it.song.id },
                )
                assertNull(database.cachedRemotePlaylistSections("PLtarget"))
                assertEquals(1, browseRequests)

                val reopened =
                    database.cachedRemotePlaylistSections("PLtarget")
                        ?: database.loadAndCachePlaylistPage(repository, "PLtarget").sections
                assertEquals(expected.map { "set-${it.id}" }, reopened.playlistSongs().map { it.playlistSetVideoId })
                assertEquals(2, browseRequests)
                assertTrue(database.cachedRemotePlaylistSections("PLtarget") != null)
            } finally {
                AppState.scope.coroutineContext.cancelChildren()
                PlaylistAlbumState.selectedPlaylistId = previousSelection
                database.close()
                tube.close()
                client.close()
                directory.toFile().deleteRecursively()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(previousSettings)
            }
        }

    @Test
    fun selectedPlaylistsAddInOrderAndOnlyOnce() =
        runTest {
            val releaseFirst = CompletableDeferred<Unit>()
            val additions = mutableListOf<String>()
            val completed = mutableListOf<String>()
            var finished = false
            val library =
                DesktopPlaybackLibrary(
                    scope = this,
                    database = { error("unused") },
                    addLocalBatch = null,
                    addSong = { id, _, _ ->
                        additions += id
                        if (id == "LPfirst") releaseFirst.await()
                        Result.success(Unit)
                    },
                )
            library.addToPlaylists(
                listOf("LPfirst", "LPsecond", "LPfirst"),
                listOf(SongItem("song", "Song", null, emptyList(), null)),
                onPlaylistAdded = { completed += it },
            ) { id, result ->
                assertNull(id)
                assertTrue(result.isSuccess)
                finished = true
            }
            runCurrent()
            assertEquals(listOf("LPfirst"), additions)
            assertTrue(completed.isEmpty())
            assertFalse(finished)
            releaseFirst.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("LPfirst", "LPsecond"), additions)
            assertEquals(additions, completed)
            assertTrue(finished)
        }

    @Test
    fun selectedPlaylistsStopOnFailureAndRetainCompletedDestinations() =
        runTest {
            val additions = mutableListOf<String>()
            val completed = mutableListOf<String>()
            var failedId: String? = null
            var failure: Throwable? = null
            val library =
                DesktopPlaybackLibrary(
                    scope = this,
                    database = { error("unused") },
                    addLocalBatch = null,
                    addSong = { id, _, _ ->
                        additions += id
                        if (id == "LPfailed") Result.failure(IllegalStateException("offline")) else Result.success(Unit)
                    },
                )
            library.addToPlaylists(
                listOf("LPfirst", "LPfailed", "LPthird"),
                listOf(SongItem("song", "Song", null, emptyList(), null)),
                onPlaylistAdded = { completed += it },
            ) { id, result ->
                failedId = id
                failure = result.exceptionOrNull()
            }
            advanceUntilIdle()
            assertEquals(listOf("LPfirst", "LPfailed"), additions)
            assertEquals(listOf("LPfirst"), completed)
            assertEquals("LPfailed", failedId)
            assertEquals(0, assertIs<PlaylistAdditionStopped>(failure).completed)
            assertEquals(0, library.playlistAddTotal)
        }

    @Test
    fun cancellingSelectedPlaylistsDoesNotStartTheNextDestination() =
        runTest {
            lateinit var library: DesktopPlaybackLibrary
            val additions = mutableListOf<String>()
            var failure: Throwable? = null
            library =
                DesktopPlaybackLibrary(
                    scope = this,
                    database = { error("unused") },
                    addLocalBatch = { id, _ ->
                        additions += id
                        library.cancelPlaylistAddition()
                        PlaylistAddBatchResult(1)
                    },
                )
            library.addToPlaylists(
                listOf("LPfirst", "LPsecond"),
                List(2) { SongItem("song$it", "Song", null, emptyList(), null) },
                onPlaylistAdded = { error("Cancelled playlist cannot complete") },
            ) { id, result ->
                assertEquals("LPfirst", id)
                failure = result.exceptionOrNull()
            }
            advanceUntilIdle()
            assertEquals(listOf("LPfirst"), additions)
            val stopped = assertIs<PlaylistAdditionStopped>(failure)
            assertEquals(1, stopped.completed)
            assertFalse(stopped.uncertain)
        }

    @Test
    fun cancellationDuringTheFinalRequestRetainsCompletionAndStopsBeforeTheNextPlaylist() =
        runTest {
            for (localBatch in listOf(false, true)) {
                val releaseFirst = CompletableDeferred<Unit>()
                val additions = mutableListOf<String>()
                val completed = mutableListOf<String>()
                var failedId: String? = null
                var failure: Throwable? = null
                val library =
                    DesktopPlaybackLibrary(
                        scope = this,
                        database = { error("unused") },
                        addLocalBatch =
                            if (localBatch) {
                                { id, songs ->
                                    additions += id
                                    if (id == "LPfirst") releaseFirst.await()
                                    PlaylistAddBatchResult(songs.size)
                                }
                            } else {
                                null
                            },
                        addSong = { id, _, _ ->
                            additions += id
                            if (id == "LPfirst") releaseFirst.await()
                            Result.success(Unit)
                        },
                    )
                val songs = listOf(SongItem("song", "Song", null, emptyList(), null))
                library.addToPlaylists(listOf("LPfirst", "LPsecond"), songs, onPlaylistAdded = { completed += it }) { id, result ->
                    failedId = id
                    failure = result.exceptionOrNull()
                }
                runCurrent()
                library.cancelPlaylistAddition()
                releaseFirst.complete(Unit)
                advanceUntilIdle()

                assertEquals(listOf("LPfirst"), additions)
                assertEquals(additions, completed)
                assertEquals("LPsecond", failedId)
                val stopped = assertIs<PlaylistAdditionStopped>(failure)
                assertEquals(0, stopped.completed)
                assertFalse(stopped.uncertain)
                assertEquals(0, library.playlistAddTotal)

                library.addToPlaylists(listOf("LPsecond"), songs, onPlaylistAdded = { completed += it }) { id, result ->
                    assertNull(id)
                    assertTrue(result.isSuccess)
                }
                advanceUntilIdle()
                assertEquals(listOf("LPfirst", "LPsecond"), additions)
                assertEquals(additions, completed)
            }
        }

    @Test
    fun localRankingsLoadWithoutRemoteSyncAndSurviveSyncFailure() =
        runTest {
            val ranking = listOf(HistoryStat("song", "Song", "Artist", null, null, 1, 0L))
            for (offline in listOf(false, true)) {
                val library =
                    DesktopPlaybackLibrary(
                        scope = this,
                        database = { error("unused") },
                        readRecentHistory = { emptyList() },
                        readTopSongs = { ranking },
                        syncHistory = {
                            if (offline) error("offline")
                            false
                        },
                    )
                library.refreshHistory().join()
                assertEquals(ranking, library.topSongs)
            }
        }

    @Test
    fun localBatchCancellationReportsOnlyCommittedChunks() =
        runTest {
            lateinit var library: DesktopPlaybackLibrary
            var calls = 0
            library =
                DesktopPlaybackLibrary(
                    scope = this,
                    database = { error("Injected mutation does not use a database") },
                    addLocalBatch = { _, songs ->
                        assertEquals(250 - calls * 100, songs.size)
                        if (++calls == 2) library.cancelPlaylistAddition()
                        PlaylistAddBatchResult(100)
                    },
                    addSong = { _, _, _ -> error("Local songs must use the batch mutation") },
                )
            var result: Result<Unit>? = null
            library.addToPlaylist("LPbatch", List(250) { SongItem("song$it", "Song", null, emptyList(), null) }) { result = it }
            runCurrent()
            val stopped = assertIs<PlaylistAdditionStopped>(result?.exceptionOrNull())
            assertEquals(200, stopped.completed)
            assertFalse(stopped.uncertain)
            assertEquals(2, calls)
        }

    @Test
    fun remoteBatchLoadsMembershipOnceAndSkipsExistingAndRepeatedSongs() =
        runTest {
            var loads = 0
            val additions = mutableListOf<String>()
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("unused") },
                    playlistSongIds = {
                        loads++
                        setOf("existing")
                    },
                    addSong = { _, song, local ->
                        assertFalse(local)
                        additions += song.id
                        Result.success(Unit)
                    },
                )
            val songs = listOf("existing", "new", "new", "last").map { SongItem(it, it, null, emptyList(), null) }
            var result: Result<Unit>? = null
            library.addToPlaylist("remote", songs) { result = it }
            advanceUntilIdle()
            assertTrue(result?.isSuccess == true)
            assertEquals(1, loads)
            assertEquals(listOf("new", "last"), additions)
            assertEquals(4, library.playlistAddCompleted)
        }

    @Test
    fun membershipLoadsEachDistinctPlaylistOnceWithAtMostFourRequests() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var active = 0
            var peak = 0
            val requests = mutableListOf<String>()
            val playlists = (1..12).map { PlaylistItem("VL$it", "$it", null, isEditable = true) }
            val songs = (1..20).map { SongItem("song$it", "$it", null, emptyList(), null) }
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("unused") },
                    loadPlaylists = { _, _ -> playlists + playlists.first().copy(id = "1") },
                    playlistSongIds = { id ->
                        requests += id
                        active++
                        peak = maxOf(peak, active)
                        release.await()
                        active--
                        songs.mapTo(mutableSetOf(), SongItem::id)
                    },
                )
            library.showAddToPlaylistSongs = songs
            library.fetchUserPlaylists(songs, true)
            runCurrent()
            assertEquals(4, requests.size)
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(4, peak)
            assertEquals(12, requests.size)
            assertEquals((1..12).map { "$it" }.toSet(), library.userPlaylistMembership)
        }

    @Test
    fun largeBatchCanExceedOneMinuteAndCancellationReportsCompletedTracks() =
        runTest {
            val songs = (1..130).map { SongItem("song$it", "$it", null, emptyList(), null) }
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("unused") },
                    addSong = { _, _, _ ->
                        delay(600)
                        Result.success(Unit)
                    },
                )
            var result: Result<Unit>? = null
            library.addToPlaylist("LPlocal", songs) { result = it }
            advanceUntilIdle()
            assertTrue(result?.isSuccess == true)
            assertEquals(130, library.playlistAddCompleted)
            library.addToPlaylist("LPlocal", songs) { result = it }
            advanceTimeBy(1500)
            library.cancelPlaylistAddition()
            advanceUntilIdle()
            val stopped = assertIs<PlaylistAdditionStopped>(result.exceptionOrNull())
            assertEquals(3, stopped.completed)
            assertEquals(130, stopped.total)
            assertFalse(stopped.uncertain)
        }

    @Test
    fun playlistsAreUsableWhileMembershipIsStillLoading() =
        runTest {
            val membership = CompletableDeferred<Unit>()
            var membershipRequests = 0
            val playlists = listOf(PlaylistItem("VLplaylist", "Playlist", null, isEditable = true))
            val song = SongItem("song", "Song", null, emptyList(), null)
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("Injected loader") },
                    loadPlaylists = { _, _ -> playlists + playlists.single().copy(id = "playlist") },
                    playlistSongIds = { _ ->
                        if (++membershipRequests == 1) membership.await()
                        setOf("song")
                    },
                    addSong = { _, _, local ->
                        assertFalse(local)
                        Result.failure(PlaylistSongAlreadyPresentException())
                    },
                )
            library.showAddToPlaylistSong = song
            library.fetchUserPlaylists(song, signedIn = true)
            runCurrent()
            assertEquals(playlists, library.userPlaylists)
            assertFalse(library.isUserPlaylistsLoading)
            assertTrue(library.userPlaylistMembership.isEmpty())
            var result: Result<Unit>? = null
            library.addToPlaylist(playlists.single().id, song) { result = it }
            runCurrent()
            assertTrue(result?.isSuccess == true)
            library.dismissAddToPlaylist()
            membership.complete(Unit)
            runCurrent()
            assertTrue(library.userPlaylistMembership.isEmpty())
        }

    @Test
    fun albumAddsEveryTrackInOrderSkipsDuplicatesAndReportsFailures() =
        runTest {
            val songs =
                listOf("first", "existing", "last").map {
                    SongItem(id = it, title = it, artists = emptyList(), thumbnail = null, album = null)
                }
            val additions = mutableListOf<String>()
            var failLast = true
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("Injected actions do not use a database") },
                    loadPlaylists = { _, _ -> listOf(PlaylistItem("LPlocal", "Local", null, isEditable = true, isLocal = true)) },
                    playlistSongIds = { setOf("first") },
                    addSong = { _, song, local ->
                        assertTrue(local)
                        additions += song.id
                        when {
                            song.id == "existing" -> Result.failure(PlaylistSongAlreadyPresentException())
                            song.id == "last" && failLast -> Result.failure(IllegalStateException())
                            else -> Result.success(Unit)
                        }
                    },
                )
            library.showAddToPlaylistSongs = songs
            library.fetchUserPlaylists(songs, signedIn = false)
            runCurrent()
            assertTrue(library.userPlaylistMembership.isEmpty(), "One existing track must not disable an album addition")
            var result: Result<Unit>? = null
            library.addToPlaylist("LPlocal", songs) { result = it }
            runCurrent()
            assertTrue(result?.isFailure == true)
            assertEquals(songs.map { it.id }, additions)
            failLast = false
            additions.clear()
            library.addToPlaylist("LPlocal", songs) { result = it }
            runCurrent()
            assertTrue(result.isSuccess == true)
            assertEquals(songs.map { it.id }, additions)
            library.dismissAddToPlaylist()
            assertTrue(library.showAddToPlaylistSongs.isEmpty())
        }

    @Test
    fun replacingAndDismissingRequestsCannotPublishStaleMembership() =
        runTest {
            val old = CompletableDeferred<Unit>()
            val current = CompletableDeferred<Unit>()
            val playlists = listOf(PlaylistItem("VLplaylist", "Playlist", null, isEditable = true))
            var request = 0
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("The injected loader does not use a database") },
                    loadPlaylists = { _, _ ->
                        val pending = if (++request == 1) old else current
                        withContext(NonCancellable) { pending.await() }
                        playlists
                    },
                    playlistSongIds = { setOf("song") },
                )
            val song = SongItem(id = "song", title = "Song", artists = emptyList(), thumbnail = null, album = null)
            library.showAddToPlaylistSong = song
            library.fetchUserPlaylists(song, signedIn = true)
            runCurrent()
            library.fetchUserPlaylists(song, signedIn = true)
            runCurrent()
            old.complete(Unit)
            runCurrent()
            assertTrue(library.isUserPlaylistsLoading)
            assertTrue(library.userPlaylistMembership.isEmpty())
            current.complete(Unit)
            runCurrent()
            assertFalse(library.isUserPlaylistsLoading)
            assertEquals(setOf("playlist"), library.userPlaylistMembership)
            library.dismissAddToPlaylist()
            assertNull(library.showAddToPlaylistSong)
            assertFalse(library.isUserPlaylistsLoading)
        }

    @Test
    fun signedOutLoadsLocalPlaylistsAndAddsLocally() =
        runTest {
            val song = SongItem(id = "song", title = "Song", artists = emptyList(), thumbnail = null, album = null)
            val local = PlaylistItem("LPlocal", "Local", null, isEditable = true, isLocal = true)
            val signedInRequests = mutableListOf<Boolean>()
            val additions = mutableListOf<Triple<String, String, Boolean>>()
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("The injected actions do not use a database") },
                    loadPlaylists = { signedIn, _ ->
                        signedInRequests += signedIn
                        listOf(local)
                    },
                    playlistSongIds = { emptySet() },
                    addSong = { id, selected, isLocal ->
                        additions += Triple(id, selected.id, isLocal)
                        Result.success(Unit)
                    },
                )
            library.showAddToPlaylistSong = song
            library.fetchUserPlaylists(song, signedIn = false)
            runCurrent()
            assertEquals(listOf(false), signedInRequests)
            assertEquals(listOf(local), library.userPlaylists)

            var result: Result<Unit>? = null
            library.addToPlaylist(local.id, song) { result = it }
            runCurrent()
            assertTrue(result?.isSuccess == true)
            assertEquals(listOf(Triple("LPlocal", "song", true)), additions)
        }

    @Test
    fun storedLocalAndSyncedPlaylistsStayUsableWhenTheRemoteListNeverLoads() =
        runTest {
            val song = SongItem(id = "song", title = "Song", artists = emptyList(), thumbnail = null, album = null)
            val local = PlaylistItem("LPlocal", "Local", null, isEditable = true, isLocal = true)
            val stored = listOf(local, PlaylistItem("PLsynced", "Synced", null, isEditable = true))
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("The injected loader does not use a database") },
                    loadPlaylists = { _, publishLocal ->
                        publishLocal(stored)
                        awaitCancellation()
                    },
                    playlistSongIds = { emptySet() },
                )
            library.showAddToPlaylistSong = song
            library.fetchUserPlaylists(song, signedIn = true)
            runCurrent()
            assertEquals(stored, library.userPlaylists)
            assertFalse(library.isUserPlaylistsLoading)
            advanceTimeBy(60.seconds)
            runCurrent()
            assertEquals(stored, library.userPlaylists)
            assertFalse(library.isUserPlaylistsLoading)
        }

    @Test
    fun hungAdditionFailsInsteadOfLockingThePicker() =
        runTest {
            val song = SongItem(id = "song", title = "Song", artists = emptyList(), thumbnail = null, album = null)
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("The injected actions do not use a database") },
                    addSong = { _, _, _ -> awaitCancellation() },
                )
            var result: Result<Unit>? = null
            library.addToPlaylist("LPlocal", song) { result = it }
            runCurrent()
            assertNull(result)
            advanceTimeBy(2.minutes)
            runCurrent()
            val stopped = assertIs<PlaylistAdditionStopped>(result?.exceptionOrNull())
            assertEquals(0, stopped.completed)
            assertTrue(stopped.uncertain)
        }

    @Test
    fun newlyCreatedLocalPlaylistCanReceiveQueueBeforePickerRefresh() =
        runTest {
            val songs = listOf("first", "second").map { SongItem(it, it, null, emptyList(), null) }
            val saved = mutableListOf<String>()
            val library =
                DesktopPlaybackLibrary(
                    addLocalBatch = null,
                    scope = this,
                    database = { error("Injected actions do not use a database") },
                    addSong = { _, song, local ->
                        assertTrue(local)
                        saved += song.id
                        Result.success(Unit)
                    },
                )
            var result: Result<Unit>? = null
            library.addToPlaylist("LPnew", songs) { result = it }
            runCurrent()
            assertTrue(result?.isSuccess == true)
            assertEquals(listOf("first", "second"), saved)
        }
}

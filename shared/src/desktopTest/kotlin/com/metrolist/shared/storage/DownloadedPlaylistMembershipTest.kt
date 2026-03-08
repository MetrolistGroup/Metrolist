package com.metrolist.shared.storage

import com.metrolist.shared.db.QueryRecordingDriver
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedPlaylistMembershipTest {
    @Test
    fun persistedIntentObservesLocalEditsRemoteRefreshAndLikesWithoutTheGlobalPreference() =
        runBlocking {
            val path = Files.createTempFile("downloaded-playlist-membership", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val changes = Channel<Pair<String, List<String>>>(Channel.UNLIMITED)
            val backing = MapSettings()
            Settings.replaceSharedForTest(Settings(backing))
            SongLikeState.refreshFromSettings()
            try {
                database.dao().insert(PlaylistEntity(id = "LPlocal", name = "Local", isLocal = true))
                database.dao().insert(PlaylistEntity(id = "LPstored", name = "Remote", browseId = "PLremote"))
                DownloadedPlaylists.track("LPlocal")
                DownloadedPlaylists.track("VLPLremote")
                DownloadedPlaylists.track("auto:liked")
                Settings.replaceSharedForTest(Settings(backing))
                DownloadedPlaylists.observe(scope, database) { id, songs -> changes.send(id to songs.map(SongItem::id)) }

                suspend fun awaitMembers(
                    id: String,
                    members: List<String>,
                ) {
                    withTimeout(10_000) {
                        while (changes.receive() != id to members) { }
                    }
                }
                awaitMembers("LPlocal", emptyList())
                assertEquals(emptyList(), DownloadedPlaylists.songs("LPmissing", database))
                val local = SongItem("local-member", "Local member", null, emptyList(), null)
                database.dao().upsertHistorySong(local)
                database.dao().insert(PlaylistSongMap(playlistId = "LPlocal", songId = local.id, position = 0))
                awaitMembers("LPlocal", listOf(local.id))
                assertEquals(listOf(local.id), DownloadedPlaylists.songs("LPlocal", database).map(SongItem::id))

                val remote = local.copy(id = "remote-member")
                database.cacheRemotePlaylist("PLremote", PlaylistItem("PLremote", "Remote", null), listOf(remote))
                awaitMembers("PLremote", listOf(remote.id))
                SongLikeState.replaceLikesFromRemote(listOf(local))
                awaitMembers("LM", listOf(local.id))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                database.close()
                SongLikeState.replaceLikesFromRemote(emptyList())
                Settings.replaceSharedForTest(null)
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unrelatedSongWritesSkipRelationsWhileOrderAndEligibilityChangesReload() =
        runTest {
            val directory = Files.createTempDirectory("download-membership")
            val driver = QueryRecordingDriver()
            val database =
                getDesktopDatabaseBuilder(directory.resolve("music.db").toFile())
                    .setDriver(driver)
                    .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
                    .build()
            try {
                val dao = database.dao()
                dao.insert(PlaylistEntity(id = "stored", name = "Remote", browseId = "PLremote"))
                listOf("a", "b", "device", "episode").forEach { dao.insert(SongEntity(it, it, isLocal = it == "device")) }
                listOf("a", "device", "b", "a").forEachIndexed { position, id ->
                    dao.insert(PlaylistSongMap(playlistId = "stored", songId = id, position = position))
                }
                val observed = mutableListOf<List<String>>()
                val job =
                    backgroundScope.launch {
                        DownloadedPlaylists.membership(database, "PLremote").collect {
                            observed +=
                                it.map(SongItem::id)
                        }
                    }
                delay(500)
                assertEquals(listOf(listOf("a", "b")), observed)
                val before = driver.queries().size
                dao.updatePlaybackPosition("episode", 30_000)
                delay(500)
                val invalidatedQueries = driver.queries().drop(before)
                assertTrue(invalidatedQueries.any { it.contains("song.isLocal FROM playlist_song_map") })
                assertTrue(invalidatedQueries.none { it.startsWith("SELECT * FROM song WHERE id IN") }, invalidatedQueries.toString())
                assertEquals(1, observed.size)
                dao.move("stored", 2, 0)
                delay(500)
                assertEquals(listOf("b", "a"), observed.last())
                dao.upsert(SongEntity("b", "b", isLocal = true))
                delay(500)
                assertEquals(listOf("a"), observed.last())
                dao.insert(SongEntity("c", "c"))
                dao.insert(PlaylistSongMap(playlistId = "stored", songId = "c", position = 4))
                delay(500)
                assertEquals(listOf("a", "c"), observed.last())
                dao.clearPlaylist("stored")
                delay(500)
                assertEquals(emptyList(), observed.last())
                job.cancelAndJoin()
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
            }
        }
}

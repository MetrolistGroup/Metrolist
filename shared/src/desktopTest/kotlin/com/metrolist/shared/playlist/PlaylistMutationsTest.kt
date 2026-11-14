package com.metrolist.shared.playlist

import androidx.room3.useWriterConnection
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.PlaylistRemovalFailedException
import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.QueryRecordingDriver
import com.metrolist.shared.db.cacheRemotePlaylist
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.db.syncRemotePlaylists
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.LibraryDeletionTracker
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.toggleAlbumLiked
import com.metrolist.shared.ui.mobile.MobilePlaylistReorder
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class PlaylistMutationsTest {
    @Test
    fun chooserMembershipRequiresAllSelectedSongsAndUsesStoredIds() =
        withDatabase { database ->
            val local = PlaylistEntity(id = "local", name = "Local", isLocal = true, browseId = "ignored")
            val cached = PlaylistEntity(id = "stored", name = "Cached", browseId = "VLremote")
            val empty = PlaylistEntity(id = "empty", name = "Empty")
            val playlists = listOf(local, cached, empty)
            playlists.forEach { database.dao().insert(it) }
            database.dao().insert(SongEntity("first", "First"))
            database.dao().insert(SongEntity("second", "Second"))
            database.dao().insert(PlaylistSongMap(playlistId = local.id, songId = "first", position = 0))
            database.dao().insert(PlaylistSongMap(playlistId = cached.id, songId = "first", position = 0))
            database.dao().insert(PlaylistSongMap(playlistId = cached.id, songId = "second", position = 1))
            val engine = MockEngine { error("Cached membership must not use the network") }
            val repository = authenticatedRepository(engine)

            assertEquals(setOf("local", "stored"), storedPlaylistMembership(database, repository, playlists, setOf("first")).last())
            assertEquals(setOf("stored"), storedPlaylistMembership(database, repository, playlists, setOf("first", "second")).last())
            assertTrue(storedPlaylistMembership(database, repository, playlists, emptySet()).last().isEmpty())
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun chooserMembershipFindsUncachedRemoteSongsWithoutMarkingPartialBatches() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "VLremote")
            database.dao().insert(playlist)
            val engine = MockEngine { respondJson(PLAYLIST_WITH_TARGET) }
            val repository = authenticatedRepository(engine)
            val playlists = listOf(playlist)

            assertEquals(setOf("stored"), storedPlaylistMembership(database, repository, playlists, setOf("target")).last())
            assertTrue(storedPlaylistMembership(database, repository, playlists, setOf("target", "absent")).last().isEmpty())
            assertTrue(storedPlaylistMembership(database, repository, playlists, setOf("absent")).last().isEmpty())
            assertEquals(1, engine.requestHistory.size)
            engine.requestHistory.forEach { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("VLremote", body["browseId"]?.jsonPrimitive?.content)
            }
        }

    @Test
    fun chooserMembershipPublishesLocalMatchesWhileRemoteLookupIsPendingOrFails() =
        withDatabase { database ->
            val local = PlaylistEntity(id = "local", name = "Local", isLocal = true)
            val remote = PlaylistEntity(id = "stored", name = "Remote", browseId = "remote")
            listOf(local, remote).forEach { database.dao().insert(it) }
            database.dao().insert(SongEntity("target", "Song"))
            database.dao().insert(PlaylistSongMap(playlistId = local.id, songId = "target", position = 0))
            val release = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    release.await()
                    respondJson("{}", HttpStatusCode.Forbidden)
                }
            val repository = authenticatedRepository(engine)
            val memberships = storedPlaylistMembership(database, repository, listOf(remote, local), setOf("target"))

            assertEquals(setOf("local"), withTimeout(5_000) { memberships.first { it.isNotEmpty() } })
            release.complete(Unit)
            assertEquals(setOf("local"), memberships.last())
        }

    @Test
    fun chooserMembershipCancellationDoesNotWaitForRemoteLookup() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "remote")
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    started.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        respondJson(PLAYLIST_WITH_TARGET)
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            val repository = authenticatedRepository(engine)
            coroutineScope {
                val lookup = async { storedPlaylistMembership(database, repository, listOf(playlist), setOf("target")).last() }
                withTimeout(5_000) { started.await() }
                lookup.cancel()
                withTimeout(5_000) { lookup.join() }
                assertTrue(lookup.isCancelled)
                withTimeout(5_000) { cancelled.await() }
            }
        }

    @Test
    fun replacementAndRemovalCleanUpOnlyOwnedImages() =
        runBlocking {
            val root = Files.createTempDirectory("collection-covers").toFile()
            try {
                val directory = File(root, "covers")
                val settings = Settings(MapSettings())
                val original = File(root, "original.jpg").apply { writeBytes(byteArrayOf(1)) }
                settings.setCollectionCover("liked", original.absolutePath)
                storeCollectionCover("liked", directory, { byteArrayOf(2) }, settings)
                assertTrue(original.exists())
                val first = File(requireNotNull(settings.collectionCovers["liked"]))
                assertContentEquals(byteArrayOf(2), first.readBytes())
                storeCollectionCover("liked", directory, { byteArrayOf(3) }, settings)
                assertFalse(first.exists())
                val second = File(requireNotNull(settings.collectionCovers["liked"]))
                assertContentEquals(byteArrayOf(3), second.readBytes())
                storeCollectionCover("liked", directory, null, settings)
                assertNull(settings.collectionCovers["liked"])
                assertFalse(second.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun failedOrCancelledReplacementKeepsThePreviousImage() =
        runBlocking {
            val directory = Files.createTempDirectory("collection-covers").toFile()
            try {
                val settings = Settings(MapSettings())
                storeCollectionCover("liked", directory, { byteArrayOf(1) }, settings)
                val original = settings.collectionCovers["liked"]
                assertFailsWith<IllegalArgumentException> {
                    storeCollectionCover("liked", directory, { byteArrayOf() }, settings)
                }
                runCatching {
                    coroutineScope {
                        val scope = this
                        storeCollectionCover("liked", directory, {
                            scope.cancel()
                            byteArrayOf(2)
                        }, settings)
                    }
                }
                assertEquals(original, settings.collectionCovers["liked"])
                assertEquals(listOf(original), directory.listFiles()!!.map { it.absolutePath })
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun readOnlyRemoteCoverSurvivesRefreshAndRemovesWithoutNetwork() =
        withDatabase { database ->
            val repository = authenticatedRepository(MockEngine { error("Read-only covers must not use network") })
            val playlist = PlaylistItem(id = "VLPLreadonly", title = "Remote", thumbnail = "https://example.com/provider.jpg")
            setStoredPlaylistCover(database, repository, playlist.id, "/covers/local.jpg", playlist).getOrThrow()
            val initial = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertNull(initial.bookmarkedAt)
            assertFalse(initial.isEditable)
            assertEquals("PLreadonly", initial.browseId)
            assertTrue(uploadStoredPlaylistCover(database, repository, playlist.id, byteArrayOf(1)).isFailure)
            database.syncRemotePlaylists(listOf(playlist))
            database.cacheRemotePlaylist(playlist.id, playlist, emptyList())
            assertEquals(
                "/covers/local.jpg",
                database
                    .dao()
                    .playlist(initial.id)
                    .first()
                    ?.thumbnailUrl,
            )
            database.cacheRemotePlaylist(
                playlist.id,
                playlist.copy(thumbnail = "/covers/local.jpg", customThumbnail = "/covers/local.jpg"),
                emptyList(),
            )
            removeStoredPlaylistCover(database, repository, playlist.id).getOrThrow()
            val removed = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertNull(removed.customThumbnailUrl)
            assertEquals(playlist.thumbnail, removed.thumbnailUrl)
        }

    @Test
    fun builtInStoredPlaylistCoverDoesNotChangeEditabilityOrMembership() =
        withDatabase { database ->
            val repository = YouTubeRepository(InnerTube(HttpClient(MockEngine { error("Local covers must not use network") })))
            val playlist = PlaylistEntity(id = PlaylistEntity.LIKED_PLAYLIST_ID, name = "Liked", isLocal = true, isEditable = false)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("song", "Song"))
            database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "song", position = 0))
            setStoredPlaylistCover(database, repository, playlist.id, "/covers/liked.jpg").getOrThrow()
            removeStoredPlaylistCover(database, repository, playlist.id).getOrThrow()
            assertFalse(requireNotNull(database.dao().findPlaylistEntity(playlist.id)).isEditable)
            assertEquals(
                listOf("song"),
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .map { it.song.id },
            )
        }

    @Test
    fun rejectedRemoteRemovalPreservesCachedMembershipAndCount() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "PLremote", isEditable = true, remoteSongCount = 1)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("song", "Song"))
            database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "song", position = 0, setVideoId = "occurrence"))
            val engine = MockEngine { respondJson(PLAYLIST_EDIT_FAILED) }
            val before = database.dao().playlistSongMaps(playlist.id, 0)
            assertTrue(
                removeSongsFromStoredPlaylist(
                    database,
                    authenticatedRepository(engine),
                    playlist.id,
                    listOf(createSongItem("song", "Song", "occurrence")),
                ).isFailure,
            )
            assertEquals(before, database.dao().playlistSongMaps(playlist.id, 0))
            assertEquals(1, database.dao().findPlaylistEntity(playlist.id)?.remoteSongCount)
            assertEquals(1, engine.requestHistory.size)
        }

    @Test
    fun localReorderPersistsTheFinalDragSuccessorWithoutChangingOccurrenceIds() =
        withDatabase { database ->
            val playlist = createStoredLocalPlaylist(database, "Duplicates").getOrThrow()
            database.dao().insert(SongEntity("same", "Song"))
            repeat(5) { position ->
                database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "same", position = position))
            }
            val ids = database.dao().playlistSongMaps(playlist.id, 0).map { it.id }
            moveSongInLocalPlaylist(database, playlist.id, ids[0], successorMappingId = ids[4]).getOrThrow()
            assertEquals(listOf(ids[1], ids[2], ids[3], ids[0], ids[4]), database.dao().playlistSongMaps(playlist.id, 0).map { it.id })
            moveSongInLocalPlaylist(database, playlist.id, ids[0], successorMappingId = ids[1]).getOrThrow()
            assertEquals(ids, database.dao().playlistSongMaps(playlist.id, 0).map { it.id })
        }

    @Test
    fun localDuplicateOccurrencesReorderAndRemoveByMappingIdentity() =
        withDatabase { database ->
            val playlist = createStoredLocalPlaylist(database, "Duplicates").getOrThrow()
            val repository = authenticatedRepository(MockEngine { error("Local mutations must not use network") })
            val song = createSongItem("same", "Song", null)
            repeat(3) { addSongToStoredPlaylist(database, repository, playlist.id, song, allowDuplicate = true).getOrThrow() }
            val original = database.dao().playlistSongMaps(playlist.id, 0)
            moveSongInStoredPlaylist(database, repository, playlist.id, original[2].id, original[1].id).getOrThrow()
            assertEquals(
                listOf(original[0].id, original[2].id, original[1].id),
                database.dao().playlistSongMaps(playlist.id, 0).map { it.id },
            )
            moveSongInLocalPlaylist(database, playlist.id, original[0].id, null).getOrThrow()
            assertEquals(
                listOf(original[2].id, original[1].id, original[0].id),
                database.dao().playlistSongMaps(playlist.id, 0).map { it.id },
            )
            assertTrue(moveSongInLocalPlaylist(database, playlist.id, original[0].id, -1).isFailure)
            removeSongsFromStoredPlaylist(
                database,
                repository,
                playlist.id,
                listOf(song.copy(playlistLocalMappingId = original[1].id)),
            ).getOrThrow()
            val remaining = database.dao().playlistSongMaps(playlist.id, 0)
            assertEquals(listOf(original[2].id, original[0].id), remaining.map { it.id })
            assertEquals(listOf(0, 1), remaining.map { it.position })
        }

    @Test
    fun projectedLocalDuplicateRemovalKeepsTheFirstOccurrence() =
        withDatabase { database ->
            val playlist = createStoredLocalPlaylist(database, "Duplicates").getOrThrow()
            listOf("a", "b").forEach { database.dao().insert(SongEntity(it, it)) }
            listOf("a", "b", "a").forEachIndexed { position, id ->
                database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = id, position = position))
            }
            val rows = database.dao().playlistSongs(playlist.id).first()
            val selected = rows.last().toSongItem()
            assertEquals(rows.last().map.id, selected.playlistAddedOrder)
            removeSongsFromStoredPlaylist(
                database,
                authenticatedRepository(MockEngine { error("Local removal must not use the network") }),
                playlist.id,
                listOf(selected),
            ).getOrThrow()
            assertEquals(rows.take(2).map { it.map }, database.dao().playlistSongMaps(playlist.id, 0))
        }

    @Test
    fun projectedRemoteOccurrencesReachTheMobileReorderMutation() =
        withDatabase { database ->
            coroutineScope {
                val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "PLremote", isEditable = true)
                database.dao().insert(playlist)
                database.dao().insert(SongEntity("same", "Song"))
                repeat(3) { position ->
                    database.dao().insert(
                        PlaylistSongMap(playlistId = playlist.id, songId = "same", position = position, setVideoId = "set-$position"),
                    )
                }

                suspend fun entries() =
                    database
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .map { it.toSongItem() to it.map }
                val original = entries()
                val repository = authenticatedRepository(MockEngine { respondJson(PLAYLIST_EDIT_SUCCEEDED) })
                val reloaded = CompletableDeferred<Unit>()
                val reorder =
                    MobilePlaylistReorder(
                        this,
                        persist = { id, successor -> moveSongInStoredPlaylist(database, repository, playlist.id, id, successor) },
                        reload = { entries().also { reloaded.complete(Unit) } },
                    )
                reorder.update(original)
                val moving = checkNotNull(original.last().first.playlistLocalMappingId)
                val target = checkNotNull(original.first().first.playlistLocalMappingId)
                assertEquals("set-2", original.last().first.playlistSetVideoId)
                reorder.move(moving, target)
                reorder.commit { error("Reorder failed") }
                withTimeout(5_000) { reloaded.await() }
                assertEquals(
                    listOf(original[2].second.id, original[0].second.id, original[1].second.id),
                    database.dao().playlistSongMaps(playlist.id, 0).map { it.id },
                )
            }
        }

    @Test
    fun bulkRemovalUpdatesEachSurvivorOnceAndPreservesGapsAndOccurrenceTokens() {
        val driver = QueryRecordingDriver()
        withDatabase(driver) { database ->
            val playlist = createStoredLocalPlaylist(database, "Gaps").getOrThrow()
            database.dao().insert(SongEntity("same", "Song"))
            repeat(1_100) { position ->
                database.dao().insert(
                    PlaylistSongMap(playlistId = playlist.id, songId = "same", position = position * 2, setVideoId = "set-$position"),
                )
            }
            val original = database.dao().playlistSongs(playlist.id).first()
            val before = driver.queries().size
            removeSongsFromStoredPlaylist(
                database,
                authenticatedRepository(MockEngine { error("Local removal must not use the network") }),
                playlist.id,
                original.take(1_000).map { it.toSongItem() },
            ).getOrThrow()
            assertEquals(
                original.drop(1_000).map { it.map.copy(position = it.map.position - 1_000) },
                database.dao().playlistSongMaps(playlist.id, 0),
            )
            val writes = driver.queries().drop(before)
            assertEquals(2, writes.count { it.startsWith("DELETE FROM playlist_song_map") })
            assertEquals(100, writes.count { it.startsWith("UPDATE playlist_song_map SET position") })
        }
    }

    @Test
    fun bulkRemovalRetainsTheExistingShiftBehaviorForRepeatedPositions() =
        withDatabase { database ->
            val playlist = createStoredLocalPlaylist(database, "Repeated positions").getOrThrow()
            database.dao().insert(SongEntity("same", "Song"))
            listOf(0, 0, 1, 2, 3, 5).forEach { position ->
                database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "same", position = position))
            }
            val rows = database.dao().playlistSongs(playlist.id).first()
            removeSongsFromStoredPlaylist(
                database,
                authenticatedRepository(MockEngine { error("Local removal must not use the network") }),
                playlist.id,
                rows.take(2).map { it.toSongItem() },
            ).getOrThrow()
            val remaining = database.dao().playlistSongMaps(playlist.id, 0)
            assertEquals(rows.drop(2).map { it.map.id }, remaining.map { it.id })
            assertEquals(listOf(0, 0, 1, 3), remaining.map { it.position })
        }

    @Test
    fun localBatchBoundsTransactionsAndReportsTheCommittedDuplicatePrefix() {
        val driver = QueryRecordingDriver()
        withDatabase(driver) { database ->
            val playlist = createStoredLocalPlaylist(database, "Batch").getOrThrow()
            val songs = List(205) { createSongItem("song-$it", "Song $it", null) }
            database.dao().insert(SongEntity("tail", "Tail"))
            database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "tail", position = 4))
            val before = driver.queries().size
            val counts = mutableListOf<Int>()
            var completed = 0
            while (completed < songs.size) {
                val result = addSongsToLocalPlaylist(database, playlist.id, songs.subList(completed, songs.size))
                assertNull(result.failure)
                completed += result.addedCount
                counts += result.addedCount
            }
            assertEquals(listOf(100, 100, 5), counts)
            val queries = driver.queries().drop(before)
            assertEquals(3, queries.count { it.contains("MAX(position)") })
            var transaction = 0
            val membershipTransactions = mutableSetOf<Int>()
            queries.forEach { sql ->
                if (sql.startsWith("BEGIN IMMEDIATE")) transaction++
                if (sql.startsWith("INSERT OR IGNORE INTO `playlist_song_map`")) membershipTransactions += transaction
            }
            assertEquals(3, membershipTransactions.size)
            assertEquals(3, queries.count { it.startsWith("UPDATE") && it.contains("`playlist` SET") })
            assertEquals(listOf(4) + (5..209), database.dao().playlistSongMaps(playlist.id, 0).map { it.position })
            val fresh = createSongItem("fresh", "Fresh", null)
            val duplicate = addSongsToLocalPlaylist(database, playlist.id, listOf(fresh, songs[0], songs[1]))
            assertEquals(1, duplicate.addedCount)
            assertTrue(duplicate.failure is PlaylistSongAlreadyPresentException)
            val approved = addSongsToLocalPlaylist(database, playlist.id, listOf(songs[0], songs[1]), allowFirstDuplicate = true)
            assertEquals(1, approved.addedCount)
            assertTrue(approved.failure is PlaylistSongAlreadyPresentException)
            assertEquals(2, database.dao().checkInPlaylist(playlist.id, songs[0].id))
        }
    }

    @Test
    fun failedLocalChunkRollsBackItsSongsAndMetadata() =
        withDatabase { database ->
            val playlist = createStoredLocalPlaylist(database, "Batch").getOrThrow()
            val original = database.dao().findPlaylistEntity(playlist.id)
            database.useWriterConnection { connection ->
                connection.usePrepared(
                    "CREATE TRIGGER fail_batch BEFORE INSERT ON song WHEN NEW.id = 'bad' BEGIN SELECT RAISE(ABORT, 'write failed'); END",
                ) { it.step() }
            }
            val result =
                addSongsToLocalPlaylist(
                    database,
                    playlist.id,
                    listOf(createSongItem("good", "Good", null), createSongItem("bad", "Bad", null)),
                )
            assertEquals(0, result.addedCount)
            assertTrue(result.failure != null)
            assertTrue(database.dao().playlistSongMaps(playlist.id, 0).isEmpty())
            assertNull(database.dao().getSongById("good"))
            assertEquals(original, database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun cancellationDuringALocalChunkStillReportsItsCommittedPrefix() {
        val driver = QueryRecordingDriver()
        withDatabase(driver) { database ->
            coroutineScope {
                val playlist = createStoredLocalPlaylist(database, "Batch").getOrThrow()
                var committed = 0
                val caller =
                    async(start = CoroutineStart.LAZY) {
                        committed =
                            addSongsToLocalPlaylist(
                                database,
                                playlist.id,
                                List(200) { createSongItem("song-$it", "Song", null) },
                            ).addedCount
                    }
                driver.onQuery = { sql -> if (sql.contains("INTO `song`")) caller.cancel() }
                caller.start()
                assertFailsWith<CancellationException> { caller.await() }
                assertEquals(100, committed)
                assertEquals(100, database.dao().playlistSongMaps(playlist.id, 0).size)
            }
        }
    }

    @Test
    fun signedOutRemoteRemovalPreservesCachedMembership() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "PLremote", isEditable = true)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("song", "Song"))
            database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "song", position = 0, setVideoId = "occurrence"))
            val repository = YouTubeRepository(InnerTube(HttpClient(MockEngine { error("Signed-out edit must not reach YouTube") })))
            val before = database.dao().playlistSongMaps(playlist.id, 0)
            assertTrue(
                removeSongsFromStoredPlaylist(
                    database,
                    repository,
                    playlist.id,
                    listOf(createSongItem("song", "Song", "occurrence")),
                ).isFailure,
            )
            assertEquals(before, database.dao().playlistSongMaps(playlist.id, 0))
        }

    @Test
    fun failedDescriptionReportsPersistedRenameAsPartialSuccess() =
        withDatabase { database ->
            coroutineScope {
                val playlist = PlaylistEntity(id = "stored", name = "Old", browseId = "PLremote", isEditable = true, description = "Kept")
                database.dao().insert(playlist)
                val changedPlaylist = async(start = CoroutineStart.UNDISPATCHED) { PlaylistChanges.flow.first() }
                var calls = 0
                val repository =
                    authenticatedRepository(
                        MockEngine {
                            respondJson(PLAYLIST_EDIT_SUCCEEDED, if (++calls == 1) HttpStatusCode.OK else HttpStatusCode.Forbidden)
                        },
                    )
                val result = updateStoredPlaylist(database, repository, playlist.id, "Old", "Kept", "New", "Changed")
                val failure = result.exceptionOrNull() as PlaylistPartiallyUpdatedException
                assertEquals("New", failure.updatedName)
                val stored = database.dao().findPlaylistEntity(playlist.id)!!
                assertEquals("New", stored.name)
                assertEquals("Kept", stored.description)
                assertEquals("PLremote", changedPlaylist.await())
            }
        }

    @Test
    fun localReorderTracksOccurrencesAcrossInterveningMovesAndRejectsMissingMappings() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "LPreorder", name = "Local", isLocal = true)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("same", "Same"))
            repeat(3) { index ->
                database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "same", position = index))
            }
            val original = database.dao().playlistSongMaps(playlist.id, 0)
            assertTrue(moveSongInLocalPlaylist(database, playlist.id, original[0].id, original[2].id).isSuccess)
            assertEquals(
                listOf(original[1].id, original[2].id, original[0].id),
                database.dao().playlistSongMaps(playlist.id, 0).map { it.id },
            )
            assertTrue(moveSongInLocalPlaylist(database, playlist.id, original[0].id, original[1].id).isSuccess)
            assertEquals(original.map { it.id }, database.dao().playlistSongMaps(playlist.id, 0).map { it.id })
            assertTrue(moveSongInLocalPlaylist(database, playlist.id, -1, original[0].id).isFailure)
            database.dao().update(playlist.copy(isLocal = false, browseId = "PLremote"))
            assertTrue(moveSongInLocalPlaylist(database, playlist.id, original[0].id, original[1].id).isFailure)
            assertEquals(original.map { it.id }, database.dao().playlistSongMaps(playlist.id, 0).map { it.id })
        }

    @Test
    fun addingToAGappedPlaylistUsesMaxPositionButCountsActualEntries() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "LPgap", name = "Local", isLocal = true, remoteSongCount = null)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("old", "Old"))
            database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "old", position = 4))
            val repository = authenticatedRepository(MockEngine { error("Local addition must not use network") })
            assertTrue(addSongToStoredPlaylist(database, repository, playlist.id, createSongItem("new", "New", null)).isSuccess)
            assertEquals(listOf(4, 5), database.dao().playlistSongMaps(playlist.id, 0).map { it.position })
            assertEquals(2, database.dao().findPlaylistEntity(playlist.id)?.remoteSongCount)
        }

    @Test
    fun bulkRemovalRereadsPositionsAndCountsAfterRemoteSuspension() =
        withDatabase { database ->
            val playlist =
                PlaylistEntity(id = "stored-race", name = "Remote", browseId = "PLrace", remoteSongCount = 3)
            database.dao().insert(playlist)
            listOf("a", "b", "c").forEachIndexed { index, id ->
                database.dao().insert(SongEntity(id, id))
                database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = id, position = index, setVideoId = "set-$id"))
            }
            val repository =
                authenticatedRepository(
                    MockEngine {
                        val first = database.dao().playlistSongMaps(playlist.id, 0).first()
                        database.dao().delete(first)
                        database.dao().decrementPositionsAfter(playlist.id, first.position)
                        database.dao().update(playlist.copy(name = "Renamed", remoteSongCount = 2))
                        respondJson(PLAYLIST_EDIT_SUCCEEDED)
                    },
                )
            assertTrue(
                removeSongsFromStoredPlaylist(database, repository, playlist.id, listOf(createSongItem("b", "b", "set-b"))).isSuccess,
            )
            val remaining = database.dao().playlistSongMaps(playlist.id, 0).single()
            assertEquals("c", remaining.songId)
            assertEquals(0, remaining.position)
            assertEquals(1, database.dao().findPlaylistEntity(playlist.id)?.remoteSongCount)
            assertEquals("Renamed", database.dao().findPlaylistEntity(playlist.id)?.name)
        }

    @Test
    fun remoteReorderUsesOccurrenceIdsAndKeepsLocalOrderOnFailure() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "PLremote", isEditable = true)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("same", "Song"))
            repeat(3) { index ->
                database.dao().insert(
                    PlaylistSongMap(playlistId = playlist.id, songId = "same", position = index, setVideoId = "set-$index"),
                )
            }
            val original = database.dao().playlistSongMaps(playlist.id, 0)
            var fail = true
            val engine =
                MockEngine {
                    respondJson(if (fail) "{}" else PLAYLIST_EDIT_SUCCEEDED, if (fail) HttpStatusCode.Forbidden else HttpStatusCode.OK)
                }
            val repository = authenticatedRepository(engine)
            assertTrue(moveSongInStoredPlaylist(database, repository, "VLPLremote", original[0].id, original[2].id).isFailure)
            assertEquals(original, database.dao().playlistSongMaps(playlist.id, 0))
            fail = false
            moveSongInStoredPlaylist(database, repository, "VLPLremote", original[0].id, original[2].id).getOrThrow()
            assertEquals(
                listOf(original[1].id, original[0].id, original[2].id),
                database.dao().playlistSongMaps(playlist.id, 0).map { it.id },
            )
            val request = Json.parseToJsonElement((engine.requestHistory.last().body as TextContent).text).jsonObject
            assertEquals("PLremote", request["playlistId"]!!.jsonPrimitive.content)
            val action = request["actions"]!!.jsonArray.single().jsonObject
            assertEquals("set-0", action["setVideoId"]!!.jsonPrimitive.content)
            assertEquals("set-2", action["movedSetVideoIdSuccessor"]!!.jsonPrimitive.content)
            moveSongInStoredPlaylist(database, repository, playlist.id, original[0].id, null).getOrThrow()
            assertEquals(
                listOf(original[1].id, original[2].id, original[0].id),
                database.dao().playlistSongMaps(playlist.id, 0).map { it.id },
            )
        }

    @Test
    fun remoteReorderValidatesEditabilityAndSetVideoIdsBeforeNetwork() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "PLremote", isEditable = true)
            database.dao().insert(playlist)
            database.dao().insert(SongEntity("same", "Song"))
            repeat(2) { index -> database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "same", position = index)) }
            val original = database.dao().playlistSongMaps(playlist.id, 0)
            val engine = MockEngine { error("Invalid reorders must not call YouTube") }
            val repository = authenticatedRepository(engine)
            assertTrue(moveSongInStoredPlaylist(database, repository, playlist.id, original[0].id, original[1].id).isFailure)
            assertTrue(moveSongInStoredPlaylist(database, repository, playlist.id, original[0].id, original[0].id).isFailure)
            database.dao().update(playlist.copy(isEditable = false))
            assertTrue(moveSongInStoredPlaylist(database, repository, playlist.id, original[0].id, null).isFailure)
            assertEquals(original, database.dao().playlistSongMaps(playlist.id, 0))
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun remoteBookmarksSaveAndUnsaveCanonicalIdsAndFailuresLeaveLocalStateUntouched() =
        withDatabase { database ->
            var fail = true
            val engine =
                MockEngine {
                    respondJson("{}", if (fail) HttpStatusCode.Forbidden else HttpStatusCode.OK)
                }
            val repository = authenticatedRepository(engine)
            val playlist = PlaylistItem(id = "VLPLsaved", title = "Saved", thumbnail = null, author = null)
            assertTrue(setStoredPlaylistBookmarked(database, repository, playlist.id, playlist, true, 250).isFailure)
            assertNull(database.dao().findPlaylistEntity(playlist.id))
            fail = false
            setStoredPlaylistBookmarked(database, repository, playlist.id, playlist, true, 250).getOrThrow()
            val saved = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertTrue(saved.bookmarkedAt != null)
            assertEquals("PLsaved", saved.browseId)
            assertEquals(250, saved.remoteSongCount)
            fail = true
            assertTrue(setStoredPlaylistBookmarked(database, repository, playlist.id, playlist, false, null).isFailure)
            assertEquals(saved, database.dao().findPlaylistEntity(playlist.id))
            fail = false
            setStoredPlaylistBookmarked(database, repository, saved.id, playlist, false, null).getOrThrow()
            assertNull(database.dao().findPlaylistEntity(saved.id)?.bookmarkedAt)
            assertEquals(
                "/youtubei/v1/like/removelike",
                engine.requestHistory
                    .last()
                    .url.encodedPath,
            )
            engine.requestHistory.forEach { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("PLsaved", body["target"]!!.jsonObject["playlistId"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun signedOutBookmarksAndSignedInLocalBookmarksNeverCallYouTube() =
        withDatabase { database ->
            val engine = MockEngine { error("Local bookmarks must not issue requests") }
            val playlist = PlaylistItem(id = "PLsignedout", title = "Saved", thumbnail = null, author = null)
            val repository = YouTubeRepository(InnerTube(HttpClient(engine)))
            setStoredPlaylistBookmarked(database, repository, playlist.id, playlist, true, null).getOrThrow()
            assertTrue(database.dao().findPlaylistEntity(playlist.id)?.bookmarkedAt != null)
            val local = createStoredLocalPlaylist(database, "Local").getOrThrow()
            setStoredPlaylistBookmarked(
                database,
                authenticatedRepository(engine),
                local.id,
                playlist.copy(id = local.id, isLocal = true),
                false,
                0,
            ).getOrThrow()
            assertNull(database.dao().findPlaylistEntity(local.id)?.browseId)
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun albumBookmarksSaveCanonicalPlaylistAndKeepMetadataOnFailure() =
        withDatabase { database ->
            var fail = false
            val engine = MockEngine { respondJson("{}", if (fail) HttpStatusCode.Forbidden else HttpStatusCode.OK) }
            val repository = authenticatedRepository(engine)
            val album = AlbumItem("MPREalbum", "Album", null, listOf(ArtistTiny("UCartist", "Artist")), "2026", "OLAKalbum")
            assertTrue(database.toggleAlbumLiked(album, emptyList(), repository))
            val saved = database.dao().album(album.id).first()!!
            assertEquals(listOf("Artist"), saved.artists.map { it.name })
            val body = Json.parseToJsonElement((engine.requestHistory.single().body as TextContent).text).jsonObject
            assertEquals("OLAKalbum", body["target"]!!.jsonObject["playlistId"]!!.jsonPrimitive.content)
            fail = true
            assertTrue(runCatching { database.toggleAlbumLiked(album, emptyList(), repository) }.isFailure)
            assertEquals(saved, database.dao().album(album.id).first())
            fail = false
            assertFalse(database.toggleAlbumLiked(album, emptyList(), repository))
            assertNull(
                database
                    .dao()
                    .album(album.id)
                    .first()
                    ?.album
                    ?.bookmarkedAt,
            )
        }

    @Test
    fun customPlaylistCanBeRenamedAndDeletedWithoutSigningIn() =
        withDatabase { database ->
            val engine = MockEngine { error("Local playlist edits must not use network") }
            val repository = YouTubeRepository(InnerTube(HttpClient(engine)))
            val playlist = createStoredLocalPlaylist(database, "Original").getOrThrow()
            database.dao().insert(SongEntity(id = "local-edit-song", title = "Track"))
            database.dao().insert(PlaylistSongMap(playlistId = playlist.id, songId = "local-edit-song", position = 0))

            assertTrue(renameStoredPlaylist(database, repository, playlist.id, " Renamed ").isSuccess)
            assertEquals("Renamed", database.dao().findPlaylistEntity(playlist.id)?.name)
            assertEquals(1, database.dao().playlistSongMaps(playlist.id, 0).size)
            assertTrue(deleteStoredPlaylist(database, repository, playlist.id).isSuccess)
            assertNull(database.dao().findPlaylistEntity(playlist.id))
            assertTrue(database.dao().playlistSongMaps(playlist.id, 0).isEmpty())
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun remoteMetadataEditsNotifyOpenPlaylistViews() =
        withDatabase { database ->
            coroutineScope {
                val repository = authenticatedRepository(MockEngine { respondJson(PLAYLIST_EDIT_SUCCEEDED) })
                val playlist =
                    PlaylistEntity(
                        id = "remote-metadata",
                        name = "Original",
                        browseId = "VLPLmetadata",
                        isEditable = true,
                    )
                database.dao().insert(playlist)

                val renameNotification = async(start = CoroutineStart.UNDISPATCHED) { PlaylistChanges.flow.first() }
                renameStoredPlaylist(database, repository, playlist.id, "Renamed").getOrThrow()
                assertEquals("PLmetadata", renameNotification.await())

                val updateNotification = async(start = CoroutineStart.UNDISPATCHED) { PlaylistChanges.flow.first() }
                updateStoredPlaylist(database, repository, playlist.id, "Renamed", null, "Updated", "Description").getOrThrow()
                assertEquals("PLmetadata", updateNotification.await())

                val coverNotification = async(start = CoroutineStart.UNDISPATCHED) { PlaylistChanges.flow.first() }
                setStoredPlaylistCover(database, repository, playlist.id, "/covers/custom.jpg").getOrThrow()
                assertEquals("PLmetadata", coverNotification.await())
            }
        }

    @Test
    fun localPlaylistsNeverResolveToRemoteMutationIds() {
        assertNull(remotePlaylistId(PlaylistEntity(id = "LP123", name = "Local", isLocal = true), "LP123"))
        assertNull(remotePlaylistId(PlaylistEntity(id = "LP456", name = "Legacy local"), "LP456"))
        assertNull(remotePlaylistId(PlaylistEntity(id = "42", name = "Restored local"), "42"))
        assertNull(remotePlaylistId(null, "LP789"))
    }

    @Test
    fun remotePlaylistsUseNormalizedBrowseIds() {
        val playlist = PlaylistEntity(name = "Remote", browseId = "VLPL123", isEditable = true)

        assertEquals("PL123", remotePlaylistId(playlist, playlist.id))
        assertEquals("PL456", remotePlaylistId(null, "VLPL456"))
    }

    @Test
    fun localDescriptionPersistsAndCanBeClearedWithoutNetwork() =
        withDatabase { database ->
            val repository = YouTubeRepository(InnerTube(HttpClient(MockEngine { error("Local edits must not use network") })))
            val playlist = createStoredLocalPlaylist(database, "Local").getOrThrow()
            updateStoredPlaylist(database, repository, playlist.id, playlist.name, null, "Local", " First line\nSecond line ").getOrThrow()
            val updated = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertEquals("First line\nSecond line", updated.description)
            updateStoredPlaylist(database, repository, playlist.id, updated.name, updated.description, "Renamed", " ").getOrThrow()
            assertNull(database.dao().findPlaylistEntity(playlist.id)?.description)
            assertEquals("Renamed", database.dao().findPlaylistEntity(playlist.id)?.name)
        }

    @Test
    fun localCoverPersistsWithoutNetworkAndRejectsBlank() =
        withDatabase { database ->
            val repository = YouTubeRepository(InnerTube(HttpClient(MockEngine { error("Local cover must not use network") })))
            val playlist = createStoredLocalPlaylist(database, "Covers").getOrThrow()
            assertTrue(setStoredPlaylistCover(database, repository, playlist.id, "  ").isFailure)
            assertTrue(setStoredPlaylistCover(database, repository, "missing", "/covers/a.jpg").isFailure)
            assertTrue(setStoredPlaylistCover(database, repository, playlist.id, " /covers/a.jpg ").isSuccess)
            assertEquals("/covers/a.jpg", database.dao().findPlaylistEntity(playlist.id)?.customThumbnailUrl)
        }

    @Test
    fun metadataEditPreservesCoverChangedWhileRemoteRequestIsPending() =
        withDatabase { database ->
            val requestStarted = CompletableDeferred<Unit>()
            val finishRequest = CompletableDeferred<Unit>()
            val engine =
                MockEngine {
                    requestStarted.complete(Unit)
                    finishRequest.await()
                    respondJson(PLAYLIST_EDIT_SUCCEEDED)
                }
            val repository = authenticatedRepository(engine)
            val playlist = PlaylistEntity(id = "pending-edit", name = "Old", browseId = "VLPLedit", isEditable = true)
            database.dao().insert(playlist)
            coroutineScope {
                withTimeout(5_000.milliseconds) {
                    val edit = async { updateStoredPlaylist(database, repository, playlist.id, "Old", null, "New", "") }
                    requestStarted.await()
                    setStoredPlaylistCover(database, repository, playlist.id, "/covers/new.jpg").getOrThrow()
                    finishRequest.complete(Unit)
                    edit.await().getOrThrow()
                }
            }
            val stored = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertEquals("New", stored.name)
            assertEquals("/covers/new.jpg", stored.customThumbnailUrl)
        }

    @Test
    fun coverUploadPreservesMetadataChangedWhileRemoteRequestIsPending() =
        withDatabase { database ->
            val requestStarted = CompletableDeferred<Unit>()
            val finishRequest = CompletableDeferred<Unit>()
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.contains("playlist_image_upload")) {
                        requestStarted.complete(Unit)
                        finishRequest.await()
                        respond(
                            content = """{"encryptedBlobId":"blob123"}""",
                            status = HttpStatusCode.OK,
                            headers =
                                headersOf(
                                    HttpHeaders.ContentType to listOf("application/json"),
                                    "X-Goog-Upload-Id" to listOf("upload123"),
                                ),
                        )
                    } else {
                        respondJson(PLAYLIST_EDIT_SUCCEEDED)
                    }
                }
            val repository = authenticatedRepository(engine)
            val playlist = PlaylistEntity(id = "pending-cover", name = "Old", browseId = "VLPLcover", isEditable = true)
            database.dao().insert(playlist)
            coroutineScope {
                withTimeout(5_000.milliseconds) {
                    val upload = async { uploadStoredPlaylistCover(database, repository, playlist.id, byteArrayOf(1, 2, 3)) }
                    requestStarted.await()
                    database.dao().update(playlist.copy(name = "New", description = "Description"))
                    setStoredPlaylistCover(database, repository, playlist.id, "/covers/new.jpg").getOrThrow()
                    finishRequest.complete(Unit)
                    upload.await().getOrThrow()
                }
            }
            val stored = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertEquals("New", stored.name)
            assertEquals("Description", stored.description)
            assertEquals("/covers/new.jpg", stored.customThumbnailUrl)
        }

    @Test
    fun ownedRemoteCoverEditsWorkBeforeBookmarking() =
        withDatabase { database ->
            for (remove in listOf(false, true)) {
                val engine = coverUploadEngine()
                val playlist =
                    PlaylistItem(
                        id = "VLPLunsaved$remove",
                        title = "Remote",
                        thumbnail = "https://example.com/original",
                        description = "Description",
                        isEditable = true,
                    )
                val repository = authenticatedRepository(engine)
                if (remove) {
                    removeStoredPlaylistCover(database, repository, playlist.id, playlist).getOrThrow()
                } else {
                    uploadStoredPlaylistCover(database, repository, playlist.id, byteArrayOf(1, 2, 3), playlist).getOrThrow()
                }
                val stored = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
                assertEquals(playlist.title, stored.name)
                assertEquals(playlist.description, stored.description)
                assertEquals(playlist.id.removePrefix("VL"), stored.browseId)
                assertTrue(stored.isEditable)
                assertNull(stored.bookmarkedAt)
                if (remove) {
                    assertNull(
                        stored.customThumbnailUrl,
                    )
                } else {
                    assertEquals("https://example.com/custom=s0-w200", stored.customThumbnailUrl)
                }
                assertTrue(engine.requestHistory.any { it.url.encodedPath.endsWith("/browse/edit_playlist") })
            }
        }

    @Test
    fun remoteCoverUploadStoresRemoteThumbnail() =
        withDatabase { database ->
            val engine = coverUploadEngine()
            val playlist = PlaylistEntity(id = "remote-cover", name = "Remote", browseId = "VLPLcover", isEditable = true)
            database.dao().insert(playlist)
            val result = uploadStoredPlaylistCover(database, authenticatedRepository(engine), playlist.id, byteArrayOf(1, 2, 3))
            assertTrue(result.isSuccess)
            assertEquals("https://example.com/custom=s0-w200", result.getOrNull())
            val stored = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertEquals("https://example.com/custom=s0-w200", stored.customThumbnailUrl)
            assertEquals("https://example.com/custom=s0-w200", stored.thumbnailUrl)
        }

    @Test
    fun ownedRemoteCoverUploadUsesLoadedPlaylistWhenStoredRowIsStale() =
        withDatabase { database ->
            database.dao().insert(PlaylistEntity(id = "VLPLstale", name = "Remote", browseId = "PLstale", isEditable = false))
            val playlist = PlaylistItem(id = "VLPLstale", title = "Remote", thumbnail = null, isEditable = true)
            val result =
                uploadStoredPlaylistCover(database, authenticatedRepository(coverUploadEngine()), playlist.id, byteArrayOf(1), playlist)
            assertEquals("https://example.com/custom=s0-w200", result.getOrThrow())
            val stored = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertTrue(stored.isEditable)
            assertEquals("https://example.com/custom=s0-w200", stored.customThumbnailUrl)
        }

    @Test
    fun rejectedRemoteCoverUploadsReturnFailureAndPreserveStoredCover() =
        withDatabase { database ->
            val playlist =
                PlaylistEntity(
                    id = "rejected-cover",
                    name = "Remote",
                    browseId = "VLPLcover",
                    isEditable = true,
                    thumbnailUrl = "https://example.com/old.jpg",
                    customThumbnailUrl = "/covers/local.jpg",
                )
            database.dao().insert(playlist)
            val storedBeforeUpload = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            for (rejectUpload in listOf(true, false)) {
                val engine =
                    MockEngine { request ->
                        if (request.url.encodedPath.contains("playlist_image_upload") && !rejectUpload) {
                            respond(
                                content = """{"encryptedBlobId":"blob123"}""",
                                status = HttpStatusCode.OK,
                                headers =
                                    headersOf(
                                        HttpHeaders.ContentType to listOf("application/json"),
                                        "X-Goog-Upload-Id" to listOf("upload123"),
                                    ),
                            )
                        } else {
                            respondJson("{}", HttpStatusCode.Forbidden)
                        }
                    }

                val result = uploadStoredPlaylistCover(database, authenticatedRepository(engine), playlist.id, byteArrayOf(1, 2, 3))

                assertTrue(result.isFailure)
                assertEquals(storedBeforeUpload, database.dao().findPlaylistEntity(playlist.id))
                assertEquals(!rejectUpload, engine.requestHistory.any { it.url.encodedPath.endsWith("/browse/edit_playlist") })
            }
        }

    @Test
    fun coverRemovalDoesNotRestoreAPlaylistDeletedDuringTheRequest() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "pending-removal", name = "Remote", browseId = "PLcover", isEditable = true)
            database.dao().insert(playlist)
            val engine =
                MockEngine {
                    database.dao().delete(playlist)
                    respondJson(PLAYLIST_EDIT_SUCCEEDED)
                }
            assertTrue(removeStoredPlaylistCover(database, authenticatedRepository(engine), playlist.id).isFailure)
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun remoteCoverRemovalClearsCustomAndKeepsFallback() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse/edit_playlist") -> {
                            respondJson(
                                """{"newHeader":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/default=s0","width":60}]}}}}}}}}""",
                            )
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist =
                PlaylistEntity(
                    id = "remote-remove",
                    name = "Remote",
                    browseId = "VLPLremove",
                    isEditable = true,
                    thumbnailUrl = "https://example.com/old=s0",
                    customThumbnailUrl = "/covers/local.jpg",
                )
            database.dao().insert(playlist)
            val result = removeStoredPlaylistCover(database, authenticatedRepository(engine), playlist.id)
            assertTrue(result.isSuccess)
            val stored = requireNotNull(database.dao().findPlaylistEntity(playlist.id))
            assertNull(stored.customThumbnailUrl)
            assertEquals("https://example.com/default=s0", stored.thumbnailUrl)
        }

    @Test
    fun localCreationValidatesNamesAndUsesIndependentEntityIds() =
        withDatabase { database ->
            assertTrue(createStoredLocalPlaylist(database, "  ").isFailure)
            val created = listOf(" First ", " Second ").map { createStoredLocalPlaylist(database, it).getOrThrow() }
            assertEquals(2, created.map { it.id }.toSet().size)
            assertTrue(created.all { it.isLocal && it.browseId == null && it.id.matches(Regex("LP[a-zA-Z0-9]{8}")) })
            assertEquals(
                listOf("First", "Second"),
                database
                    .dao()
                    .allPlaylists()
                    .first()
                    .map { it.name }
                    .sorted(),
            )
        }

    @Test
    fun loggedOutCreationStoresEditableLocalPlaylistWithoutNetwork() =
        withDatabase { database ->
            val repository = YouTubeRepository(InnerTube(HttpClient(MockEngine { error("Local creation must not use network") })))
            assertTrue(createStoredPlaylist(database, repository, "  ").isFailure)
            val id = createStoredPlaylist(database, repository, " Local playlist ").getOrThrow()
            val stored = requireNotNull(database.dao().findPlaylistEntity(id))
            assertTrue(id.startsWith("LP"))
            assertEquals("Local playlist", stored.name)
            assertTrue(stored.isLocal && stored.isEditable)
            assertFalse(stored.isAutoSync)
            assertNull(stored.browseId)
            assertTrue(stored.bookmarkedAt != null)
            assertEquals(
                1,
                database
                    .dao()
                    .allPlaylists()
                    .first()
                    .size,
            )
        }

    @Test
    fun signedInCreationStoresRemotePlaylistAndDoesNotFallBackOnFailure() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    assertEquals("/youtubei/v1/playlist/create", request.url.encodedPath)
                    assertEquals(
                        "Remote playlist",
                        Json
                            .parseToJsonElement((request.body as TextContent).text)
                            .jsonObject["title"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                    respondJson("""{"playlistId":"PLcreated"}""")
                }
            val repository = authenticatedRepository(engine)
            assertTrue(createStoredPlaylist(database, repository, "  ").isFailure)
            assertTrue(engine.requestHistory.isEmpty())
            assertEquals("PLcreated", createStoredPlaylist(database, repository, " Remote playlist ").getOrThrow())
            val stored =
                database
                    .dao()
                    .allPlaylists()
                    .first()
                    .single()
            assertEquals("Remote playlist", stored.name)
            assertEquals("PLcreated", stored.browseId)
            assertTrue(stored.isEditable && stored.isAutoSync)
            assertFalse(stored.isLocal)

            val failedRepository = authenticatedRepository(MockEngine { respondJson("{}", HttpStatusCode.InternalServerError) })
            assertTrue(createStoredPlaylist(database, failedRepository, "Failed playlist").isFailure)
            assertEquals(listOf(stored), database.dao().allPlaylists().first())
        }

    @Test
    fun localRemovalObservesExactOccurrencesAndRefreshesStalePositions() =
        withDatabase { database ->
            val dao = database.dao()
            val playlist = createStoredLocalPlaylist(database, "Duplicates").getOrThrow()
            listOf("a", "b", "c").forEach { dao.insert(SongEntity(id = it, title = it)) }
            listOf("a", "b", "a", "c").forEachIndexed { index, id ->
                dao.insert(PlaylistSongMap(playlistId = playlist.id, songId = id, position = index))
            }
            val original = dao.playlistSongs(playlist.id).first()
            withTimeout(5_000.milliseconds) {
                val observed =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        dao.playlistSongs(playlist.id).first { it.size == 2 }
                    }
                removeSongFromLocalPlaylist(database, original[1].map).getOrThrow()
                removeSongFromLocalPlaylist(database, original[2].map).getOrThrow()
                val remaining = observed.await()
                assertEquals(listOf(original[0].map.id, original[3].map.id), remaining.map { it.map.id })
                assertEquals(listOf(0, 1), remaining.map { it.map.position })
            }
            removeSongFromLocalPlaylist(database, original[2].map).getOrThrow()
            assertEquals(listOf("a", "c"), dao.playlistSongs(playlist.id).first().map { it.song.id })
            removeSongFromLocalPlaylist(database, original[0].map).getOrThrow()
            removeSongFromLocalPlaylist(database, original[3].map).getOrThrow()
            assertTrue(dao.playlistSongs(playlist.id).first().isEmpty())
            assertEquals(0, dao.findPlaylistEntity(playlist.id)?.remoteSongCount)
        }

    @Test
    fun ownedPlaylistUsesDeleteEndpoint() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson(
                                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"editHeader":{"musicPlaylistEditHeaderRenderer":{}}}}}""",
                            )
                        }

                        request.url.encodedPath.endsWith("/playlist/delete") -> {
                            respondJson("{}")
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist = PlaylistEntity(id = "stored-owned", name = "Owned", browseId = "VLPLowned", isEditable = true)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isSuccess)

            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/playlist/delete"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun ownedPlaylistRecognizedByDeletePlaylistEndpoint() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson(
                                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"editHeader":{"menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"service":{"deletePlaylistEndpoint":{}}}}]}}}}}}""",
                            )
                        }

                        request.url.encodedPath.endsWith("/playlist/delete") -> {
                            respondJson("{}")
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist = PlaylistEntity(id = "stored-owned-affordance", name = "Owned", browseId = "VLPLowned2", isEditable = true)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isSuccess)

            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/playlist/delete"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun ownedDeletionFallsBackToUnlikeOnBadRequest() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson(
                                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"editHeader":{"musicPlaylistEditHeaderRenderer":{}}}}}""",
                            )
                        }

                        request.url.encodedPath.endsWith("/playlist/delete") -> {
                            respondJson("{}", HttpStatusCode.BadRequest)
                        }

                        request.url.encodedPath.endsWith("/like/removelike") -> {
                            respondJson("{}")
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist = PlaylistEntity(id = "stored-owned-fallback", name = "Owned", browseId = "VLPLowned3", isEditable = true)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isSuccess)

            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/playlist/delete", "/youtubei/v1/like/removelike"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun sharedRemovalFallsBackToDeleteOnBadRequest() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson("{}")
                        }

                        request.url.encodedPath.endsWith("/like/removelike") -> {
                            respondJson("{}", HttpStatusCode.BadRequest)
                        }

                        request.url.encodedPath.endsWith("/playlist/delete") -> {
                            respondJson("{}")
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist = PlaylistEntity(id = "stored-shared-fallback", name = "Shared", browseId = "VLPLshared2", isEditable = false)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isSuccess)

            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/like/removelike", "/youtubei/v1/playlist/delete"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun failedFallbackKeepsCachedPlaylistAndStableFailure() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson(
                                """{"header":{"musicEditablePlaylistDetailHeaderRenderer":{"editHeader":{"musicPlaylistEditHeaderRenderer":{}}}}}""",
                            )
                        }

                        request.url.encodedPath.endsWith("/playlist/delete") -> {
                            respondJson("{}", HttpStatusCode.BadRequest)
                        }

                        request.url.encodedPath.endsWith("/like/removelike") -> {
                            respondJson("{}", HttpStatusCode.BadRequest)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist = PlaylistEntity(id = "stored-fallback-fail", name = "Owned", browseId = "VLPLowned4", isEditable = true)
            database.dao().insert(playlist)

            val result = deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id)

            assertTrue(result.exceptionOrNull() is PlaylistRemovalFailedException)
            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/playlist/delete", "/youtubei/v1/like/removelike"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
        }

    @Test
    fun sharedPlaylistUsesUnlikeEndpoint() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> respondJson("{}")
                        request.url.encodedPath.endsWith("/like/removelike") -> respondJson("{}")
                        else -> error("Unexpected request: ${request.url}")
                    }
                }
            val playlist = PlaylistEntity(id = "stored-shared", name = "Shared", browseId = "VLPLshared", isEditable = false)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isSuccess)

            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/like/removelike"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun collaborativePlaylistUsesUnlikeEndpoint() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson("""{"header":{"musicEditablePlaylistDetailHeaderRenderer":{}}}""")
                        }

                        request.url.encodedPath.endsWith("/like/removelike") -> {
                            respondJson("{}")
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist =
                PlaylistEntity(id = "stored-collaborative", name = "Shared", browseId = "VLPLshared", isEditable = true)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isSuccess)

            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/like/removelike"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun failedCollaborativeRemovalUsesStableFailure() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson("""{"header":{"musicEditablePlaylistDetailHeaderRenderer":{}}}""")
                        }

                        request.url.encodedPath.endsWith("/like/removelike") -> {
                            respondJson("{}", HttpStatusCode.InternalServerError)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist =
                PlaylistEntity(id = "stored-collaborative", name = "Shared", browseId = "VLPLshared", isEditable = true)
            database.dao().insert(playlist)

            val result = deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id)

            assertTrue(result.exceptionOrNull() is PlaylistRemovalFailedException)
            assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
        }

    @Test
    fun expiredRemovalSessionKeepsCachedPlaylist() {
        listOf(
            HttpStatusCode.Unauthorized to false,
            HttpStatusCode.Forbidden to true,
        ).forEach { (status, expectSessionValid) ->
            withDatabase { database ->
                val engine =
                    MockEngine { request ->
                        when {
                            request.url.encodedPath.endsWith("/browse") -> respondJson("{}")
                            request.url.encodedPath.endsWith("/like/removelike") -> respondJson("{}", status)
                            else -> error("Unexpected request: ${request.url}")
                        }
                    }
                val repository = authenticatedRepository(engine)
                val playlist =
                    PlaylistEntity(id = "stored-expired", name = "Cached", browseId = "VLPLexpired", isEditable = false)
                database.dao().insert(playlist)

                assertTrue(deleteStoredPlaylist(database, repository, playlist.id).isFailure)

                assertEquals(
                    listOf("/youtubei/v1/browse", "/youtubei/v1/like/removelike"),
                    engine.requestHistory.map { it.url.encodedPath },
                )
                assertEquals(expectSessionValid, repository.canManagePlaylists())
                assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
            }
        }
    }

    @Test
    fun expiredOwnershipSessionKeepsCachedPlaylist() {
        listOf(
            HttpStatusCode.Unauthorized to false,
            HttpStatusCode.Forbidden to true,
        ).forEach { (status, expectSessionValid) ->
            withDatabase { database ->
                val engine = MockEngine { respondJson("{}", status) }
                val repository = authenticatedRepository(engine)
                val playlist = PlaylistEntity(id = "stored-expired-lookup", name = "Cached", browseId = "VLPLexpired")
                database.dao().insert(playlist)

                assertTrue(deleteStoredPlaylist(database, repository, playlist.id).isFailure)

                assertTrue(engine.requestHistory.all { it.url.encodedPath == "/youtubei/v1/browse" })
                assertEquals(expectSessionValid, repository.canManagePlaylists())
                assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
            }
        }
    }

    @Test
    fun malformedOwnershipLookupKeepsCachedPlaylist() =
        withDatabase { database ->
            val engine = MockEngine { respondJson("{") }
            val playlist =
                PlaylistEntity(
                    id = "stored-unavailable",
                    name = "Cached",
                    browseId = "VLPLunavailable",
                    isEditable = false,
                )
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isFailure)

            assertEquals(listOf("/youtubei/v1/browse"), engine.requestHistory.map { it.url.encodedPath })
            assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
        }

    @Test
    fun unsuccessfulOwnershipLookupKeepsCachedPlaylist() =
        withDatabase { database ->
            val engine = MockEngine { respondJson("{}", HttpStatusCode.InternalServerError) }
            val playlist =
                PlaylistEntity(
                    id = "stored-server-error",
                    name = "Cached",
                    browseId = "VLPLservererror",
                    isEditable = true,
                )
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isFailure)

            assertTrue(engine.requestHistory.all { it.url.encodedPath == "/youtubei/v1/browse" })
            assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
        }

    @Test
    fun unsuccessfulSharedRemovalKeepsCachedPlaylist() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse") -> {
                            respondJson("{}")
                        }

                        request.url.encodedPath.endsWith("/like/removelike") -> {
                            respondJson("{}", HttpStatusCode.InternalServerError)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist = PlaylistEntity(id = "stored-failed-remove", name = "Cached", browseId = "VLPLfailed")
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, authenticatedRepository(engine), playlist.id).isFailure)

            val requests = engine.requestHistory.map { it.url.encodedPath }
            assertEquals("/youtubei/v1/browse", requests.first())
            assertTrue(requests.drop(1).all { it == "/youtubei/v1/like/removelike" })
            assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
        }

    @Test
    fun accountSwitchDuringOwnershipLookupAbortsRemoval() =
        withDatabase { database ->
            lateinit var innerTube: InnerTube
            val handledPaths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    handledPaths += request.url.encodedPath
                    if (request.url.encodedPath.endsWith("/browse")) {
                        innerTube.cookie = "SAPISID=new-session"
                        respondJson("{}")
                    } else {
                        error("Account switch must abort before mutation: ${request.url}")
                    }
                }
            innerTube = authenticatedInnerTube(engine)
            val playlist = PlaylistEntity(id = "stored-switch", name = "Cached", browseId = "VLPLswitch")
            database.dao().insert(playlist)

            var cancelled = false
            try {
                deleteStoredPlaylist(database, YouTubeRepository(innerTube), playlist.id)
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertEquals(listOf("/youtubei/v1/browse"), handledPaths)
            assertTrue(database.dao().findPlaylistEntity(playlist.id) != null)
        }

    @Test
    fun signedOutDeletionPreservesOwnedRemotePlaylist() =
        withDatabase { database ->
            val engine = MockEngine { error("Signed-out deletion must not issue a request") }
            val playlist = PlaylistEntity(id = "stored-owned-offline", name = "Owned", browseId = "PLowned", isEditable = true)
            database.dao().insert(playlist)
            val stored = database.dao().findPlaylistEntity(playlist.id)

            assertTrue(deleteStoredPlaylist(database, YouTubeRepository(InnerTube(HttpClient(engine))), playlist.id).isFailure)
            assertTrue(engine.requestHistory.isEmpty())
            assertEquals(stored, database.dao().findPlaylistEntity(playlist.id))
            assertFalse(LibraryDeletionTracker.isDeleted("playlist:PLowned"))
        }

    @Test
    fun signedOutRemovalKeepsRemotePlaylistUntouched() =
        withDatabase { database ->
            val engine = MockEngine { error("Signed-out removal must not issue a request") }
            val playlist = PlaylistEntity(id = "stored-offline", name = "Cached", browseId = "VLPLoffline", isEditable = false)
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, YouTubeRepository(InnerTube(HttpClient(engine))), playlist.id).isSuccess)

            assertTrue(engine.requestHistory.isEmpty())
            assertNull(database.dao().findPlaylistEntity(playlist.id))
            assertFalse(LibraryDeletionTracker.isDeleted("playlist:PLoffline"))
            assertFalse(LibraryDeletionTracker.isDeleted("playlist:${playlist.id}"))
        }

    @Test
    fun notFoundRemotePlaylistDeletesLocalEntitySuccessfully() =
        withDatabase { database ->
            val engine = MockEngine { respondJson("{}", HttpStatusCode.NotFound) }
            val repository = authenticatedRepository(engine)
            val playlist = PlaylistEntity(id = "stored-404", name = "Missing", browseId = "VLPLmissing")
            database.dao().insert(playlist)

            assertTrue(deleteStoredPlaylist(database, repository, playlist.id).isSuccess)

            assertEquals(listOf("/youtubei/v1/browse"), engine.requestHistory.map { it.url.encodedPath })
            assertNull(database.dao().findPlaylistEntity(playlist.id))
        }

    @Test
    fun removeSongsFromStoredPlaylistRemovesFromRemoteAndLocalDatabase() =
        withDatabase { database ->
            coroutineScope {
                val engine =
                    MockEngine { request ->
                        when {
                            request.url.encodedPath.endsWith("/browse/edit_playlist") -> respondJson(PLAYLIST_EDIT_SUCCEEDED)
                            else -> error("Unexpected request: ${request.url}")
                        }
                    }
                val playlist =
                    PlaylistEntity(
                        id = "remote-1",
                        name = "My Remote",
                        browseId = "VLPLremote1",
                        isEditable = true,
                        remoteSongCount = 2,
                    )
                database.dao().insert(SongEntity(id = "song1", title = "Song 1"))
                database.dao().insert(SongEntity(id = "song2", title = "Song 2"))
                database.dao().insert(playlist)
                database.dao().insert(
                    PlaylistSongMap(playlistId = "remote-1", songId = "song1", position = 0, setVideoId = "set1"),
                )
                database.dao().insert(
                    PlaylistSongMap(playlistId = "remote-1", songId = "song2", position = 1, setVideoId = "set2"),
                )
                val changedPlaylist = async(start = CoroutineStart.UNDISPATCHED) { PlaylistChanges.flow.first() }

                val result =
                    removeSongsFromStoredPlaylist(
                        database,
                        authenticatedRepository(engine),
                        "remote-1",
                        listOf(createSongItem("song1", "Song 1", "set1")),
                    )

                assertTrue(result.isSuccess)
                assertEquals(listOf("/youtubei/v1/browse/edit_playlist"), engine.requestHistory.map { it.url.encodedPath })
                val remaining = database.dao().playlistSongs("remote-1").first()
                assertEquals(1, remaining.size)
                assertEquals("song2", remaining[0].song.id)
                assertEquals(0, remaining[0].map.position)
                assertEquals(1, database.dao().findPlaylistEntity("remote-1")?.remoteSongCount)
                assertEquals("PLremote1", changedPlaylist.await())
            }
        }

    @Test
    fun removeSongsFromStoredPlaylistResolvesMissingSetVideoIdFromDatabase() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse/edit_playlist") -> respondJson(PLAYLIST_EDIT_SUCCEEDED)
                        else -> error("Unexpected request: ${request.url}")
                    }
                }
            val playlist =
                PlaylistEntity(id = "remote-2", name = "My Remote 2", browseId = "PLremote2", isEditable = true, remoteSongCount = 2)
            database.dao().insert(SongEntity(id = "song1", title = "Song 1"))
            database.dao().insert(SongEntity(id = "song2", title = "Song 2"))
            database.dao().insert(playlist)
            database.dao().insert(PlaylistSongMap(playlistId = "remote-2", songId = "song1", position = 0, setVideoId = "set1"))
            database.dao().insert(PlaylistSongMap(playlistId = "remote-2", songId = "song2", position = 1, setVideoId = "set2"))

            val result =
                removeSongsFromStoredPlaylist(
                    database,
                    authenticatedRepository(engine),
                    "VLPLremote2",
                    listOf(createSongItem("song1", "Song 1", playlistSetVideoId = null)),
                )

            assertTrue(result.isSuccess)
            assertEquals(listOf("/youtubei/v1/browse/edit_playlist"), engine.requestHistory.map { it.url.encodedPath })
            val remaining = database.dao().playlistSongs("remote-2").first()
            assertEquals(1, remaining.size)
            assertEquals("song2", remaining[0].song.id)
        }

    @Test
    fun removeSongsFromStoredPlaylistOnLocalPlaylistNeverCallsNetwork() =
        withDatabase { database ->
            val engine = MockEngine { error("Local playlist removal must not issue network request") }
            val playlist = PlaylistEntity(id = "LP1", name = "Local", isLocal = true, browseId = null)
            database.dao().insert(SongEntity(id = "song1", title = "Song 1"))
            database.dao().insert(playlist)
            database.dao().insert(PlaylistSongMap(playlistId = "LP1", songId = "song1", position = 0, setVideoId = null))

            val result =
                removeSongsFromStoredPlaylist(
                    database,
                    authenticatedRepository(engine),
                    "LP1",
                    listOf(createSongItem("song1", "Song 1", null)),
                )

            assertTrue(result.isSuccess)
            assertTrue(engine.requestHistory.isEmpty())
            assertTrue(
                database
                    .dao()
                    .playlistSongs("LP1")
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun removeSongsFromStoredPlaylistAbortsLocalDeletionWhenRemoteFails() =
        withDatabase { database ->
            val engine =
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/browse/edit_playlist") -> {
                            respondJson("{}", HttpStatusCode.InternalServerError)
                        }

                        else -> {
                            error("Unexpected request: ${request.url}")
                        }
                    }
                }
            val playlist =
                PlaylistEntity(id = "remote-fail", name = "Remote Fail", browseId = "VLPLfail", isEditable = true, remoteSongCount = 2)
            database.dao().insert(SongEntity(id = "song1", title = "Song 1"))
            database.dao().insert(SongEntity(id = "song2", title = "Song 2"))
            database.dao().insert(playlist)
            database.dao().insert(PlaylistSongMap(playlistId = "remote-fail", songId = "song1", position = 0, setVideoId = "set1"))
            database.dao().insert(PlaylistSongMap(playlistId = "remote-fail", songId = "song2", position = 1, setVideoId = "set2"))

            val result =
                removeSongsFromStoredPlaylist(
                    database,
                    authenticatedRepository(engine),
                    "remote-fail",
                    listOf(createSongItem("song1", "Song 1", "set1")),
                )

            assertTrue(result.isFailure)
            assertEquals(listOf("/youtubei/v1/browse/edit_playlist"), engine.requestHistory.map { it.url.encodedPath })
            val remaining = database.dao().playlistSongs("remote-fail").first()
            assertEquals(2, remaining.size)
            assertEquals(2, database.dao().findPlaylistEntity("remote-fail")?.remoteSongCount)
        }

    @Test
    fun failedRemoteAddLeavesStoredPlaylistAndSongUntouched() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored-add", name = "Remote", browseId = "VLPLadd", remoteSongCount = 0)
            database.dao().insert(playlist)
            val song = SongEntity(id = "add-song", title = "Original title")
            database.dao().insert(song)
            val storedPlaylist = database.dao().findPlaylistEntity(playlist.id)
            val storedSong = database.dao().getSongById(song.id)?.song
            val engine =
                MockEngine { request ->
                    respondJson(if (request.url.encodedPath.endsWith("/browse")) "{}" else PLAYLIST_EDIT_FAILED)
                }

            val result =
                addSongToStoredPlaylist(
                    database,
                    authenticatedRepository(engine),
                    playlist.id,
                    createSongItem(song.id, "Updated title", null),
                )

            assertTrue(result.isFailure)
            assertEquals(storedPlaylist, database.dao().findPlaylistEntity(playlist.id))
            assertEquals(storedSong, database.dao().getSongById(song.id)?.song)
            assertTrue(
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .isEmpty(),
            )
        }

    @Test
    fun remoteAddCommitsLocallyOnlyAfterRemoteSuccess() =
        withDatabase { database ->
            coroutineScope {
                val playlist = PlaylistEntity(id = "stored-add", name = "Remote", browseId = "VLPLadd", remoteSongCount = 0)
                database.dao().insert(playlist)
                val engine =
                    MockEngine { request ->
                        assertTrue(request.url.encodedPath in listOf("/youtubei/v1/browse", "/youtubei/v1/browse/edit_playlist"))
                        assertTrue(
                            database
                                .dao()
                                .playlistSongs(playlist.id)
                                .first()
                                .isEmpty(),
                        )
                        assertNull(database.dao().getSongById("add-song"))
                        if (request.url.encodedPath.endsWith("/browse/edit_playlist")) {
                            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                            val action = body["actions"]!!.jsonArray.single().jsonObject
                            assertEquals("PLadd", body["playlistId"]?.jsonPrimitive?.content)
                            assertEquals("ACTION_ADD_VIDEO", action["action"]?.jsonPrimitive?.content)
                            assertEquals("add-song", action["addedVideoId"]?.jsonPrimitive?.content)
                            respondJson(PLAYLIST_EDIT_SUCCEEDED)
                        } else {
                            respondJson("{}")
                        }
                    }

                val notification =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        PlaylistChanges.flow.first().also {
                            assertEquals(
                                listOf("add-song"),
                                database
                                    .dao()
                                    .playlistSongs(playlist.id)
                                    .first()
                                    .map { it.song.id },
                            )
                        }
                    }
                assertTrue(
                    addSongToStoredPlaylist(
                        database,
                        authenticatedRepository(engine),
                        playlist.id,
                        createSongItem("add-song", "Song", null),
                    ).isSuccess,
                )

                assertEquals(
                    listOf("add-song"),
                    database
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .map { it.song.id },
                )
                assertEquals("PLadd", withTimeout(5_000) { notification.await() })
                assertEquals(1, database.dao().findPlaylistEntity(playlist.id)?.remoteSongCount)
            }
        }

    @Test
    fun addingDisplayedCustomMetadataKeepsCanonicalPlaylistMetadata() =
        withDatabase { database ->
            val dao = database.dao()
            val playlist = PlaylistEntity(id = "LPcustom", name = "Custom", isLocal = true)
            dao.insert(playlist)
            dao.upsert(SongEntity(id = "other-song", title = "Other song"))
            dao.upsert(ArtistEntity(id = "shared-artist", name = "Canonical artist"))
            dao.upsert(SongArtistMap(songId = "other-song", artistId = "shared-artist", position = 0))
            val displayed =
                SongItem(
                    id = "custom-song",
                    title = "Custom title",
                    thumbnail = null,
                    artists = listOf(ArtistTiny("shared-artist", "Custom artist")),
                    album = null,
                    customTitle = "Custom title",
                    customArtist = "Custom artist",
                    canonicalTitle = "Canonical title",
                    canonicalArtists = listOf(ArtistTiny("shared-artist", "Canonical artist")),
                )

            val result =
                addSongToStoredPlaylist(
                    database,
                    YouTubeRepository(InnerTube(HttpClient(MockEngine { error("Local add must not use network") }))),
                    playlist.id,
                    displayed,
                )

            assertTrue(result.isSuccess)
            val stored = requireNotNull(dao.getSongById(displayed.id))
            assertEquals("Canonical title", stored.song.title)
            assertEquals("Custom title", stored.song.customTitle)
            assertEquals("Custom artist", stored.song.customArtist)
            assertEquals("Canonical artist", dao.getArtistById("shared-artist")?.name)
            assertEquals(listOf("shared-artist"), stored.artistMaps.map(SongArtistMap::artistId))
        }

    @Test
    fun readOnlyAndSignedOutRemoteAddsDoNotMutateAnything() =
        withDatabase { database ->
            val engine = MockEngine { error("Unavailable destinations must not issue requests") }
            val song = createSongItem("add-song", "Song", null)
            listOf(false, true).forEach { editable ->
                val playlist =
                    PlaylistEntity(id = "stored-$editable", name = "Remote", browseId = "VLPLadd", isEditable = editable)
                database.dao().insert(playlist)
                val storedPlaylist = database.dao().findPlaylistEntity(playlist.id)
                val repository =
                    if (editable) YouTubeRepository(InnerTube(HttpClient(engine))) else authenticatedRepository(engine)

                assertTrue(addSongToStoredPlaylist(database, repository, playlist.id, song).isFailure)
                assertEquals(storedPlaylist, database.dao().findPlaylistEntity(playlist.id))
                assertTrue(
                    database
                        .dao()
                        .playlistSongs(playlist.id)
                        .first()
                        .isEmpty(),
                )
            }
            assertNull(database.dao().getSongById(song.id))
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun localDuplicatesRequireConfirmationEvenWithConcurrentAdds() =
        withDatabase { database ->
            val engine = MockEngine { error("Local playlist adds must not issue requests") }
            val playlist = PlaylistEntity(id = "LPadd", name = "Local", isLocal = true)
            database.dao().insert(playlist)

            val repository = YouTubeRepository(InnerTube(HttpClient(engine)))
            val song = createSongItem("add-song", "Song", null)
            val results =
                withTimeout(5_000.milliseconds) {
                    (1..2).map { async { addSongToStoredPlaylist(database, repository, playlist.id, song) } }.awaitAll()
                }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.exceptionOrNull() is PlaylistSongAlreadyPresentException })

            assertEquals(
                listOf("add-song"),
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .map { it.song.id },
            )
            assertTrue(addSongToStoredPlaylist(database, repository, playlist.id, song, allowDuplicate = true).isSuccess)
            assertEquals(
                2,
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .size,
            )
            assertTrue(engine.requestHistory.isEmpty())
        }

    @Test
    fun uncachedRemoteDuplicatesRequireConfirmationWithoutChangingLocalData() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "VLPLremote", remoteSongCount = 1)
            database.dao().insert(playlist)
            val stored = database.dao().findPlaylistEntity(playlist.id)
            val song = createSongItem("target", "Song", null)
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/browse")) {
                        respondJson(PLAYLIST_WITH_TARGET)
                    } else {
                        respondJson(PLAYLIST_EDIT_SUCCEEDED)
                    }
                }
            val repository = authenticatedRepository(engine)

            assertTrue(
                addSongToStoredPlaylist(database, repository, playlist.id, song).exceptionOrNull() is PlaylistSongAlreadyPresentException,
            )
            assertEquals(listOf("/youtubei/v1/browse"), engine.requestHistory.map { it.url.encodedPath })
            assertEquals(stored, database.dao().findPlaylistEntity(playlist.id))
            assertNull(database.dao().getSongById(song.id))
            assertTrue(
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .isEmpty(),
            )

            assertTrue(addSongToStoredPlaylist(database, repository, playlist.id, song, allowDuplicate = true).isSuccess)
            assertEquals(
                listOf("/youtubei/v1/browse", "/youtubei/v1/browse/edit_playlist"),
                engine.requestHistory.map { it.url.encodedPath },
            )
            assertEquals(
                1,
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .size,
            )
            assertEquals(2, database.dao().findPlaylistEntity(playlist.id)?.remoteSongCount)

            assertTrue(
                addSongToStoredPlaylist(database, repository, playlist.id, song).exceptionOrNull() is PlaylistSongAlreadyPresentException,
            )
            assertEquals(2, engine.requestHistory.size)
        }

    @Test
    fun failedMembershipAndSessionChangesDoNotAddSongs() =
        withDatabase { database ->
            val playlist = PlaylistEntity(id = "stored", name = "Remote", browseId = "VLPLremote")
            database.dao().insert(playlist)
            val song = createSongItem("target", "Song", null)
            val failedEngine =
                MockEngine { request ->
                    assertEquals("/youtubei/v1/browse", request.url.encodedPath)
                    respondJson("{}", HttpStatusCode.InternalServerError)
                }
            assertTrue(addSongToStoredPlaylist(database, authenticatedRepository(failedEngine), playlist.id, song).isFailure)

            lateinit var innerTube: InnerTube
            val switchedEngine =
                MockEngine { request ->
                    assertEquals("/youtubei/v1/browse", request.url.encodedPath)
                    innerTube.cookie = "SAPISID=new-session"
                    respondJson("{}")
                }
            innerTube = authenticatedInnerTube(switchedEngine)
            var cancelled = false
            try {
                addSongToStoredPlaylist(database, YouTubeRepository(innerTube), playlist.id, song)
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertNull(database.dao().getSongById(song.id))
            assertTrue(
                database
                    .dao()
                    .playlistSongs(playlist.id)
                    .first()
                    .isEmpty(),
            )
        }

    private fun createSongItem(
        id: String,
        title: String,
        playlistSetVideoId: String?,
    ): SongItem =
        SongItem(
            id = id,
            title = title,
            thumbnail = null,
            artists = emptyList(),
            album = null,
            playlistSetVideoId = playlistSetVideoId,
        )

    private fun coverUploadEngine(): MockEngine =
        MockEngine { request ->
            when {
                request.url.encodedPath.contains("playlist_image_upload") -> {
                    respond(
                        content = """{"encryptedBlobId":"blob123"}""",
                        status = HttpStatusCode.OK,
                        headers =
                            io.ktor.http.Headers.build {
                                append(HttpHeaders.ContentType, "application/json")
                                append("X-Goog-Upload-Id", "upload123")
                            },
                    )
                }

                request.url.encodedPath.endsWith("/browse/edit_playlist") -> {
                    respondJson(
                        """{"newHeader":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":{"thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/custom=s0","width":60},{"url":"https://example.com/custom=s0-w200","width":200}]}}}}}}}}""",
                    )
                }

                else -> {
                    error("Unexpected request: ${request.url}")
                }
            }
        }

    private fun authenticatedRepository(engine: MockEngine): YouTubeRepository = YouTubeRepository(authenticatedInnerTube(engine))

    private fun authenticatedInnerTube(engine: MockEngine): InnerTube =
        InnerTube(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            },
            retryDelay = {},
        ).apply {
            cookie = "SAPISID=session"
            visitorData = "visitor-data"
            dataSyncId = "channel-sync-id"
            authUser = "0"
            useLoginForBrowse = true
        }

    private fun withDatabase(
        driver: QueryRecordingDriver? = null,
        block: suspend (MusicDatabase) -> Unit,
    ) = runBlocking {
        val path = Files.createTempFile("metrolist-playlist-mutation", ".db")
        Files.deleteIfExists(path)
        val database = getDesktopDatabaseBuilder(path.toFile()).apply { if (driver != null) setDriver(driver) }.build()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        LibraryDeletionTracker.clear()
        try {
            block(database)
        } finally {
            LibraryDeletionTracker.clear()
            Settings.replaceSharedForTest(null)
            database.close()
            Files.deleteIfExists(path)
        }
    }

    companion object {
        private const val PLAYLIST_EDIT_FAILED = """{"status":"STATUS_FAILED"}"""
        private const val PLAYLIST_EDIT_SUCCEEDED = """{"status":"STATUS_SUCCEEDED"}"""
        private const val PLAYLIST_WITH_TARGET =
            """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],"playlistItemData":{"videoId":"target"}}}]}}]}}}}}"""
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}

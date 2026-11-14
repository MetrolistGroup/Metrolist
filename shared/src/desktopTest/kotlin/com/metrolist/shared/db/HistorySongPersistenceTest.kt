package com.metrolist.shared.db

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import com.metrolist.shared.api.YouTubeRadioParser
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.editSongMetadata
import com.metrolist.shared.model.refetchSongMetadata
import com.metrolist.shared.model.resetSongMetadata
import com.metrolist.shared.model.toEntity
import com.metrolist.shared.state.SongDisplayMetadata
import com.metrolist.shared.state.withCustomMetadata
import com.metrolist.shared.ui.menu.SongEditor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistorySongPersistenceTest {
    @Test
    fun freshQueueMetadataReplacesAPersistedUnlinkedTypeLabel() =
        runBlocking {
            val path = Files.createTempFile("metrolist-song-type-label", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val stale = SongItem("track", "No Surprises", null, listOf(ArtistTiny(null, "Titel")), null)
                database.dao().upsertHistorySong(stale)
                val fresh =
                    requireNotNull(
                        YouTubeRadioParser.parsePlaylistPanelItem(
                            Json
                                .parseToJsonElement(
                                    """{"playlistPanelVideoRenderer":{
                                  "videoId":"track",
                                  "title":{"runs":[{"text":"No Surprises"}]},
                                  "shortBylineText":{"runs":[{"text":"Radiohead"}]}
                                }}""",
                                ).jsonObject,
                        ),
                    )

                database.dao().upsertHistorySong(fresh)
                assertEquals(
                    "Radiohead",
                    requireNotNull(database.dao().getSongById(stale.id))
                        .toSongItem()
                        .artists
                        .single()
                        .name,
                )

                database.dao().upsertHistorySong(stale)
                refetchSongMetadata(database, MusicVideoResolver(), stale) { fresh }
                val stored = requireNotNull(database.dao().getSongById(stale.id)).toSongItem()
                assertEquals("Radiohead", stored.artists.single().name)
                assertEquals(1, database.dao().songArtistMap(stale.id).size)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun historyRoundTripPreservesKnownAndUnknownExplicitness() =
        runBlocking {
            val path = Files.createTempFile("metrolist-history-explicitness", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val songs =
                    listOf(
                        SongItem("explicit", "Explicit", null, emptyList(), null, isExplicit = true, explicitness = true),
                        SongItem("clean", "Clean", null, emptyList(), null, explicitness = false),
                        SongItem("unknown", "Unknown", null, emptyList(), null),
                    )
                songs.forEach {
                    val cached = it.toEntity().copy(isCached = true)
                    assertEquals(it.artworkExplicitness, cached.explicitness)
                    database.dao().upsert(cached)
                    database.dao().upsertHistorySong(it)
                }

                assertEquals(
                    listOf(true, false, null),
                    songs.map {
                        assertNotNull(database.dao().getSongById(it.id))
                            .toSongItem()
                            .artworkExplicitness
                    },
                )
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun customSongMetadataPersistsWithTheSong() =
        runBlocking {
            val path = Files.createTempFile("metrolist-custom-song", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.dao().upsert(
                    SongEntity(
                        id = "song",
                        title = "Imported title",
                        customTitle = "Edited title",
                        customArtist = "Edited artist",
                    ),
                )
            } finally {
                database.close()
            }
            val reopened = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val stored = reopened.dao().getSongById("song")?.song
                assertEquals("Edited title", stored?.customTitle)
                assertEquals("Edited artist", stored?.customArtist)
            } finally {
                reopened.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun remoteSongUpsertPreservesCustomMetadata() =
        runBlocking {
            val path = Files.createTempFile("metrolist-custom-song-sync", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                dao.upsert(
                    SongEntity(
                        id = "song",
                        title = "Old remote title",
                        customTitle = "Edited title",
                        customArtist = "Edited artist",
                    ),
                )

                dao.upsert(SongEntity(id = "song", title = "Fresh remote title"))

                val stored = dao.getSongById("song")?.song
                assertEquals("Fresh remote title", stored?.title)
                assertEquals("Edited title", stored?.customTitle)
                assertEquals("Edited artist", stored?.customArtist)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun editingSongMetadataKeepsRemoteMetadataAsFallback() =
        runBlocking {
            val path = Files.createTempFile("metrolist-song-edit", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val song =
                    SongItem(
                        id = "song",
                        title = "Remote title",
                        thumbnail = null,
                        artists = listOf(ArtistTiny("artist", "Remote artist")),
                        album = null,
                    )
                val dao = database.dao()
                dao.upsert(SongEntity("song", "Remote title"))
                dao.upsert(ArtistEntity("artist", "Remote artist"))
                dao.upsert(SongArtistMap("song", "artist", 0))

                editSongMetadata(database, song, "Edited title", "Edited artist")

                val stored = dao.getSongById("song")
                val displayed = requireNotNull(stored).toSongItem()
                assertEquals("Remote title", stored.song.title)
                assertEquals("Edited title", stored.song.customTitle)
                assertEquals("Edited artist", stored.song.customArtist)
                assertEquals("Remote title", displayed.canonicalTitle)
                assertEquals("Remote artist", displayed.canonicalArtists?.single()?.name)
                assertEquals("Edited title", displayed.title)
                assertEquals("Edited artist", displayed.artists.single().name)
                assertEquals("artist", displayed.artists.single().id)
                assertEquals(listOf(SongArtistMap("song", "artist", 0)), dao.songArtistMap("song"))
                assertEquals("Remote artist", dao.getArtistById("artist")?.name)

                resetSongMetadata(database, song.id)

                val reset = requireNotNull(dao.getSongById(song.id)).toSongItem()
                assertEquals("Remote title", reset.title)
                assertEquals("Remote artist", reset.artists.single().name)
                assertEquals("artist", reset.artists.single().id)
                assertEquals(null, reset.customTitle)
                assertEquals(null, reset.customArtist)
                assertEquals(listOf(SongArtistMap("song", "artist", 0)), dao.songArtistMap("song"))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun songEditorWaitsForDatabaseTransactionBeforeDismissing() =
        runBlocking {
            withTimeout(15_000) {
                val path = Files.createTempFile("metrolist-song-edit-pending", ".db")
                Files.deleteIfExists(path)
                val database = getDesktopDatabaseBuilder(path.toFile()).build()
                val scope = CoroutineScope(coroutineContext + Job())
                val locked = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Unit>()
                try {
                    val song = SongItem("song", "Remote title", null, emptyList(), null)
                    val blocker =
                        launch {
                            database.withDatabaseTransaction {
                                locked.complete(Unit)
                                release.await()
                            }
                        }
                    locked.await()
                    val editor =
                        SongEditor(scope, "Edited title", "Edited artist", { title, artist ->
                            started.complete(Unit)
                            editSongMetadata(database, song, title, artist)
                        }, {
                            completed.complete(Unit)
                            scope.cancel()
                        }, {
                            scope.cancel()
                        })
                    editor.confirm()
                    started.await()
                    editor.dismiss()
                    assertTrue(editor.busy)
                    assertFalse(completed.isCompleted)

                    release.complete(Unit)
                    blocker.join()
                    completed.await()
                    val stored = database.dao().getSongById(song.id)?.song
                    assertEquals("Edited title", stored?.customTitle)
                    assertEquals("Edited artist", stored?.customArtist)
                } finally {
                    release.complete(Unit)
                    scope.cancel()
                    database.close()
                    Files.deleteIfExists(path)
                }
            }
        }

    @Test
    fun metadataRefetchClearsThePreviousEditAndRestoresCanonicalDisplay() =
        runBlocking {
            val path = Files.createTempFile("metrolist-song-refetch-reset", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val song = SongItem("song", "Old remote title", null, listOf(ArtistTiny("old-artist", "Old artist")), null)
                dao.upsert(SongEntity("song", "Old remote title", customTitle = "Edited title", customArtist = "Edited artist"))
                dao.upsert(ArtistEntity("old-artist", "Old artist"))
                dao.upsert(SongArtistMap("song", "old-artist", 0))
                val stale = requireNotNull(dao.getSongById("song")).toSongItem()

                val result =
                    refetchSongMetadata(database, MusicVideoResolver(), stale) {
                        SongItem("song", "Fresh remote title", null, listOf(ArtistTiny("fresh-artist", "Fresh artist")), null)
                    }

                val stored = requireNotNull(dao.getSongById("song"))
                assertNull(stored.song.customTitle)
                assertNull(stored.song.customArtist)
                assertEquals("Fresh remote title", result?.title)
                assertEquals("Fresh artist", result?.artists?.single()?.name)
                assertEquals("Fresh remote title", result?.canonicalTitle)
                assertEquals("Fresh artist", result?.canonicalArtists?.single()?.name)
                val resetDisplay =
                    stale.withCustomMetadata(
                        SongDisplayMetadata(
                            canonicalTitle = "Fresh remote title",
                            canonicalArtists = listOf(ArtistTiny("fresh-artist", "Fresh artist")),
                            customTitle = null,
                            customArtist = null,
                        ),
                    )
                assertEquals("Fresh remote title", resetDisplay.title)
                assertEquals("Fresh artist", resetDisplay.artists.single().name)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun metadataRefetchPreservesAnEditAcceptedWhileTheRequestWasRunning() =
        runBlocking {
            val path = Files.createTempFile("metrolist-song-refetch", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val song = SongItem("song", "Old remote title", null, listOf(ArtistTiny("old-artist", "Old artist")), null)
                dao.upsert(SongEntity("song", "Old remote title", customTitle = "Initial edit"))
                dao.upsert(ArtistEntity("old-artist", "Old artist"))
                dao.upsert(SongArtistMap("song", "old-artist", 0))

                val result =
                    refetchSongMetadata(database, MusicVideoResolver(), song) {
                        dao.upsert(
                            requireNotNull(dao.getSongById("song")).song.copy(
                                customTitle = "Concurrent edit",
                                customArtist = "Concurrent artist",
                            ),
                        )
                        SongItem("song", "Fresh remote title", null, listOf(ArtistTiny("fresh-artist", "Fresh artist")), null)
                    }

                val stored = requireNotNull(dao.getSongById("song"))
                assertEquals("Fresh remote title", stored.song.title)
                assertEquals("Concurrent edit", stored.song.customTitle)
                assertEquals("Concurrent artist", stored.song.customArtist)
                assertEquals("Concurrent edit", result?.title)
                assertEquals("Concurrent artist", result?.artists?.single()?.name)
                assertEquals("Fresh remote title", result?.canonicalTitle)
                assertEquals("Fresh artist", result?.canonicalArtists?.single()?.name)
                assertEquals(listOf("fresh-artist"), dao.songArtistMap("song").map(SongArtistMap::artistId))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun failedMetadataEditRollsBackTheSongAndArtistMappingsBeforeRetry() =
        runBlocking {
            val path = Files.createTempFile("metrolist-history-edit", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val original = SongEntity("song", "Original", albumName = "Album", liked = true)
                val song = original.copy(title = "Retitled")
                val maps = listOf(SongArtistMap(song.id, "original-artist", 0), SongArtistMap(song.id, "featured-artist", 1))
                dao.upsert(original)
                dao.upsert(ArtistEntity(maps[0].artistId, "Original artist"))
                dao.upsert(ArtistEntity(maps[1].artistId, "Featured artist"))
                maps.forEach { dao.upsert(it) }
                database.updateHistorySong(song.id, song.title, "Original artist, Featured artist", song.albumName)
                assertEquals(song, dao.getSongById(song.id)?.song)
                assertEquals(maps, dao.songArtistMap(song.id).sortedBy { it.position })
                database.useWriterConnection { connection ->
                    connection.usePrepared(
                        """
                        CREATE TRIGGER fail_history_edit BEFORE INSERT ON song_artist_map
                        WHEN NEW.artistId = 'LH:song'
                        BEGIN SELECT RAISE(ABORT, 'history edit failure'); END
                        """.trimIndent(),
                    ) { it.step() }
                }
                assertFailsWith<SQLiteException> {
                    database.updateHistorySong(song.id, "Edited", " New artist ", "New album")
                }
                assertEquals(song, dao.getSongById(song.id)?.song)
                assertEquals(maps, dao.songArtistMap(song.id).sortedBy { it.position })
                assertNull(dao.getArtistById("LH:song"))

                database.useWriterConnection { connection ->
                    connection.usePrepared("DROP TRIGGER fail_history_edit") { it.step() }
                }
                database.updateHistorySong(song.id, "Edited", " New artist ", "New album")
                assertEquals(song.copy(title = "Edited", albumName = "New album"), dao.getSongById(song.id)?.song)
                assertEquals(listOf(SongArtistMap(song.id, "LH:song", 0)), dao.songArtistMap(song.id))
                assertEquals("New artist", dao.getArtistById("LH:song")?.name)

                database.updateHistorySong(song.id, "Edited", " ", null)
                assertEquals(emptyList(), dao.songArtistMap(song.id))
                assertNull(dao.getSongById(song.id)?.song?.albumName)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun failedRemoteDeletionRetainsLocalHistoryForRetry() =
        runBlocking {
            val path = Files.createTempFile("metrolist-history-delete", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                dao.upsert(SongEntity("song", "Song"))
                val remote = Event(1, "song", LocalDateTime(2026, 1, 1, 0, 0), 30_000L, remoteFeedbackToken = "feedback")
                val local = remote.copy(id = 2, remoteFeedbackToken = null)
                dao.insert(remote)
                dao.insert(local)
                val events = listOf(remote, local)
                assertFailsWith<IllegalStateException> {
                    database.deleteHistoryEvents(events) { error("Remote request failed") }
                }
                assertEquals(2, dao.eventCount().first())
                database.deleteHistoryEvents(events) { tokens ->
                    assertEquals(listOf("feedback"), tokens)
                    assertEquals(2, dao.eventCount().first())
                }
                assertEquals(0, dao.eventCount().first())
                dao.insert(remote)
                database.deleteHistoryEvents(listOf(remote))
                assertEquals(0, dao.eventCount().first())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun albumStatisticsIncludeHistoryWithoutLibraryAlbumRelations() =
        runBlocking {
            val path = Files.createTempFile("metrolist-album-history", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                database.withDatabaseTransaction {
                    listOf("album-a", "album-a", "album-b", null).forEachIndexed { index, albumId ->
                        val song =
                            SongItem(
                                id = "song-$index",
                                title = "Song $index",
                                thumbnail = "artwork-$index.jpg",
                                artists = listOf(ArtistTiny("artist", "Artist")),
                                album = albumId?.let { AlbumTiny(it, it) },
                            )
                        dao.upsertHistorySong(song)
                        dao.insert(Event(index + 1L, song.id, LocalDateTime(2026, 1, 1, 0, 0), (index + 1) * (index + 1) * 30_000L))
                    }
                }
                val events = dao.events().first()
                events.forEach { assertNull(it.song.album) }
                val albums = historyAlbumStats(events)
                assertEquals(listOf("album-b", "album-a"), albums.map { it.id })
                val album = albums.first { it.id == "album-a" }
                assertEquals("album-a", album.title)
                assertEquals(2, album.playCount)
                assertEquals(150_000L, album.playTime)
                assertNotNull(album.thumbnailUrl)
                assertEquals(listOf("artist"), album.artists.map { it.id })
                assertEquals(3, albums.sumOf { it.playCount })
                assertEquals(listOf("album-b"), historyAlbumStats(events.filter { it.song.id == "song-2" }).map { it.id })
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun skeletalPlaysReuseCanonicalArtistsAndRemoveReplacedPlaceholders() =
        runBlocking {
            val path = Files.createTempFile("metrolist-history", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                val stored = SongEntity("song", "Stored title", duration = 245, liked = true, isEpisode = true)
                database.withDatabaseTransaction {
                    dao.upsert(stored)
                    dao.upsert(ArtistEntity("UC-real", "Artist", thumbnailUrl = "stored.jpg"))
                    dao.upsert(ArtistEntity("LH:song:0", "Artist", isLocal = true))
                    dao.upsert(ArtistEntity("unknown", "Unknown", isLocal = true))
                    dao.upsert(SongArtistMap("song", "UC-real", 0))
                    dao.upsert(SongArtistMap("song", "LH:song:0", 0))
                    dao.upsert(SongArtistMap("song", "unknown", 0))
                    dao.upsertHistorySong(SongItem("song", "Sparse title", null, listOf(ArtistTiny(null, "Artist")), album = null))
                }
                val result = assertNotNull(dao.getSongById("song"))
                assertEquals(stored, result.song)
                assertEquals(listOf(SongArtistMap("song", "UC-real", 0)), result.artistMaps)
                assertEquals("stored.jpg", result.artists.single().thumbnailUrl)

                database.withDatabaseTransaction {
                    dao.upsertHistorySong(SongItem("song", "Sparse title", null, emptyList(), album = null))
                }
                assertEquals(result.artistMaps, dao.getSongById("song")?.artistMaps)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}

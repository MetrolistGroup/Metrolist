package com.metrolist.shared.playlistimport

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.constants.LikedSongsRecencyJsonKey
import com.metrolist.shared.db.QueryRecordingDriver
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class PlaylistImportManagerTest {
    @Test
    fun favoritesImportCannotSwitchAccountsWhileWaitingForVotes() =
        runBlocking {
            val path = Files.createTempFile("favorites-account", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("A cancelled import must not send votes") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.refreshFromSettings()
            Settings.shared.dataSyncId = "account-a"
            try {
                val release = CompletableDeferred<Unit>()
                val syncing =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        SongLikeState.withImportVoteSync { release.await() }
                    }
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val song = SongItem("song", "Song", null, emptyList(), null)
                val importing =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        manager.importVerbose("Favorites", listOf(song), PlaylistImportManager.Destination.Favorites)
                    }
                Settings.shared.dataSyncId = "account-b"
                release.complete(Unit)
                syncing.join()
                val error = assertFailsWith<PlaylistImportManager.CancelledImportException> { importing.await() }
                assertEquals(0, error.importedSongCount)
                assertEquals(null, database.dao().getSongById(song.id))
                assertEquals(false, SongLikeState.isLiked(song.id))
            } finally {
                SongLikeState.awaitPersistence()
                Settings.replaceSharedForTest(null)
                SongLikeState.refreshFromSettings()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun fileTitlesSurviveResolutionPersistenceAndResumeWithoutReplacingLocalEdits() =
        runBlocking {
            val destinations =
                listOf(
                    PlaylistImportManager.Destination.NewLocal,
                    PlaylistImportManager.Destination.NewSynced,
                    PlaylistImportManager.Destination.Favorites,
                )
            for (destination in destinations) {
                for (existingTitle in listOf(null, "Canonical title", "Local edit")) {
                    val path = Files.createTempFile("import-custom-title", ".db")
                    Files.delete(path)
                    val database = getDatabaseBuilder(path.toFile()).build()
                    var metadataRequests = 0
                    var remoteContainsSong = false
                    val client =
                        HttpClient(
                            MockEngine { request ->
                                val response =
                                    when {
                                        request.url.encodedPath.endsWith("/music/get_queue") -> {
                                            metadataRequests++
                                            """{"queueDatas":[{"content":{"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Canonical title"}]}}}}]}"""
                                        }

                                        request.url.encodedPath.endsWith("/playlist/create") -> {
                                            """{"playlistId":"PLcreated"}"""
                                        }

                                        request.url.encodedPath.endsWith("/browse/edit_playlist") -> {
                                            remoteContainsSong = true
                                            """{"status":"STATUS_SUCCEEDED"}"""
                                        }

                                        request.url.encodedPath.endsWith("/browse") -> {
                                            val songs =
                                                if (remoteContainsSong) {
                                                    """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Canonical title"}]}}}],"playlistItemData":{"videoId":"abcdefghijk","playlistSetVideoId":"set-song"}}}"""
                                                } else {
                                                    ""
                                                }
                                            """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$songs]}}]}}}"""
                                        }

                                        else -> {
                                            error("Unexpected endpoint: ${request.url.encodedPath}")
                                        }
                                    }
                                respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                            },
                        ) { install(ContentNegotiation) { json() } }
                    Settings.replaceSharedForTest(Settings(MapSettings()))
                    SongLikeState.refreshFromSettings()
                    try {
                        val innerTube =
                            InnerTube(client).apply {
                                if (destination == PlaylistImportManager.Destination.NewSynced) cookie = "SAPISID=test"
                            }
                        val manager = PlaylistImportManager(database, YouTubeRepository(innerTube))
                        val canonical = SongItem("abcdefghijk", "Canonical title", null, emptyList(), null)
                        val prior =
                            if (existingTitle != null) {
                                manager
                                    .importVerbose(
                                        "Prior",
                                        listOf(canonical.copy(customTitle = existingTitle.takeUnless { it == canonical.title })),
                                        destination,
                                    ).getOrThrow()
                            } else {
                                null
                            }
                        val target =
                            if (prior != null && destination != PlaylistImportManager.Destination.Favorites) {
                                PlaylistImportManager.Destination.EXISTING(prior.playlist)
                            } else {
                                destination
                            }
                        val tracks =
                            UniversalPlaylistParser.parse(
                                """[{"title":"Imported rename","videoId":"abcdefghijk"}]""",
                            )
                        val matches = manager.resolveImportTracks(tracks, target, ImportSourceType.FILE_OR_TEXT)
                        assertEquals("Canonical title", matches.single().second.canonicalTitle)
                        assertEquals("Imported rename", matches.single().second.title)
                        val result = manager.importResolvedTracks("Import", matches, target, ImportSourceType.FILE_OR_TEXT).getOrThrow()
                        val expectedTitle = if (existingTitle == "Local edit") existingTitle else "Imported rename"
                        val stored = requireNotNull(database.dao().getSongById(canonical.id))
                        assertEquals("Canonical title", stored.song.title)
                        assertEquals(expectedTitle, stored.song.customTitle)
                        assertEquals(expectedTitle, stored.toSongItem().title)
                        assertEquals("Canonical title", stored.toSongItem().canonicalTitle)

                        val resumeTarget =
                            if (destination == PlaylistImportManager.Destination.Favorites) {
                                target
                            } else {
                                PlaylistImportManager.Destination.EXISTING(result.playlist)
                            }
                        val resumed = manager.resolveImportTracks(tracks, resumeTarget, ImportSourceType.FILE_OR_TEXT)
                        assertEquals("Canonical title", resumed.single().second.canonicalTitle)
                        if (destination != PlaylistImportManager.Destination.Favorites) assertEquals(1, metadataRequests)
                        assertEquals(
                            0,
                            manager
                                .importResolvedTracks("Import", resumed, resumeTarget, ImportSourceType.FILE_OR_TEXT)
                                .getOrThrow()
                                .importedSongCount,
                        )
                        assertEquals(
                            expectedTitle,
                            database
                                .dao()
                                .getSongById(canonical.id)
                                ?.toSongItem()
                                ?.title,
                        )
                    } finally {
                        SongLikeState.replaceLikesFromRemote(emptyList())
                        Settings.replaceSharedForTest(null)
                        client.close()
                        database.close()
                        Files.deleteIfExists(path)
                    }
                }
            }
        }

    @Test
    fun oversizedBatchFailsBeforeAnyTrackCanBeSilentlyDropped() =
        runBlocking {
            val path = Files.createTempFile("import-track-limit", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Oversized imports must not issue requests") })
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val tracks = List(PlaylistImportManager.MAX_PLAYLIST_TRACKS + 1) { PlaylistImportManager.CsvTrack("Song", "Artist", null) }
                val error = assertFailsWith<ImportException> { manager.resolveImportTracks(tracks, null, null) }
                assertEquals(ImportErrorKey.TooManyTracks, error.errorKey)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unifiedImportCountsRepeatedSourceOccurrences() =
        runBlocking {
            val path = Files.createTempFile("unified-playlist-occurrences", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Local import must not request the network") })
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val track =
                    PlaylistImportManager.CsvTrack(
                        title = "Song",
                        artist = "Artist",
                        videoId = null,
                        directReference = "file:///music/song.flac",
                        isResolvedLocalReference = true,
                    )
                val states = mutableListOf<ImportProgressState>()
                val result =
                    UnifiedPlaylistImporter
                        .executeImport(
                            manager,
                            "List",
                            listOf(track, track),
                            PlaylistImportManager.Destination.NewLocal,
                            onStateUpdate = states::add,
                        ).getOrThrow()
                assertEquals(2, result.totalSourceTracks)
                assertEquals(2, result.resolvedTracks)
                assertEquals(2, result.importedTracks)
                assertEquals(2, states.filterIsInstance<ImportProgressState.SavingToLibrary>().first().total)
            } finally {
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun resolutionFailuresAreDistinctFromEmptySearchesAndDoNotSaveAPlaylist() =
        runBlocking {
            for (fail in listOf(false, true)) {
                val path = Files.createTempFile("import-search", ".db")
                Files.deleteIfExists(path)
                val database = getDatabaseBuilder(path.toFile()).build()
                var requests = 0
                val client =
                    HttpClient(
                        MockEngine {
                            requests++
                            if (fail) throw IllegalStateException("Network unavailable")
                            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json() } }
                Settings.replaceSharedForTest(Settings(MapSettings()))
                try {
                    val states = mutableListOf<ImportProgressState>()
                    val result =
                        UnifiedPlaylistImporter.executeImport(
                            PlaylistImportManager(database, YouTubeRepository(InnerTube(client))),
                            "Import",
                            listOf(PlaylistImportManager.CsvTrack("Song", "Artist", null)),
                            PlaylistImportManager.Destination.NewLocal,
                            onStateUpdate = states::add,
                        )
                    val expected = if (fail) ImportErrorKey.ResolutionFailed else ImportErrorKey.NoLibraryMatches
                    assertEquals(expected, (result.exceptionOrNull() as ImportException).errorKey)
                    assertEquals(expected, (states.last() as ImportProgressState.Failed).errorKey)
                    assertEquals(if (fail) 3 else 1, requests)
                    assertEquals(null, database.dao().findPlaylistByName("Import"))
                } finally {
                    Settings.replaceSharedForTest(null)
                    client.close()
                    database.close()
                    Files.deleteIfExists(path)
                }
            }
        }

    @Test
    fun staleLocalOccurrencesDoNotSuppressRemoteRepairs() =
        runBlocking {
            val path = Files.createTempFile("playlist-remote-repair", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            var remoteCopies = 0
            var additions = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val response =
                            when {
                                request.url.encodedPath.endsWith("/playlist/create") -> {
                                    """{"playlistId":"PLcreated"}"""
                                }

                                request.url.encodedPath.endsWith("/browse/edit_playlist") -> {
                                    additions++
                                    remoteCopies += if ((request.body as TextContent).text.contains("ACTION_ADD_PLAYLIST")) 2 else 1
                                    if (additions == 3) """{"status":"STATUS_FAILED"}""" else """{"status":"STATUS_SUCCEEDED"}"""
                                }

                                request.url.encodedPath.endsWith("/browse") -> {
                                    val copies = if ((request.body as TextContent).text.contains("PLsource")) 2 else remoteCopies
                                    val items =
                                        (1..copies).joinToString(",") { occurrence ->
                                            """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],"playlistItemData":{"videoId":"song","playlistSetVideoId":"set-$occurrence"}}}"""
                                        }
                                    """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$items]}}]}}}"""
                                }

                                else -> {
                                    error("Unexpected endpoint: ${request.url.encodedPath}")
                                }
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" }))
                val song = SongItem("song", "Song", null, emptyList(), null)
                val songs = listOf(song, song)
                val first = manager.importVerbose("List", songs, PlaylistImportManager.Destination.NewSynced).getOrThrow()
                remoteCopies = 0
                val destination = PlaylistImportManager.Destination.EXISTING(first.playlist)
                assertEquals(2, manager.importVerbose("List", songs, destination).getOrThrow().importedSongCount)
                assertEquals(2, remoteCopies)
                assertEquals(4, additions)
                assertEquals(2, database.dao().checkInPlaylist(first.playlist.id, song.id))
                assertEquals(0, manager.importVerbose("List", songs, destination).getOrThrow().importedSongCount)
                assertEquals(4, additions)
                remoteCopies = 0
                val copied = manager.importVerbose("List", songs, destination, sourceYouTubePlaylistId = "PLsource").getOrThrow()
                assertEquals(2, copied.importedSongCount)
                assertEquals(2, remoteCopies)
                assertEquals(5, additions)
                assertEquals(2, database.dao().checkInPlaylist(first.playlist.id, song.id))
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun syncedImportReconcilesLostResponsesByOccurrenceCount() =
        runBlocking {
            val path = Files.createTempFile("playlist-remote-occurrences", ".db")
            Files.delete(path)
            val driver = QueryRecordingDriver()
            val database = getDatabaseBuilder(path.toFile()).setDriver(driver).build()
            var added = 0
            var browses = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        val response =
                            when {
                                request.url.encodedPath.endsWith("/playlist/create") -> {
                                    """{"playlistId":"PLcreated"}"""
                                }

                                request.url.encodedPath.endsWith("/browse/edit_playlist") -> {
                                    added++
                                    if (added == 2) """{"status":"STATUS_FAILED"}""" else """{"status":"STATUS_SUCCEEDED"}"""
                                }

                                request.url.encodedPath.endsWith("/browse") -> {
                                    browses++
                                    val items =
                                        (1..added).joinToString(",") { occurrence ->
                                            """{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],"playlistItemData":{"videoId":"song","playlistSetVideoId":"set-$occurrence"}}}"""
                                        }
                                    """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$items]}}]}}}"""
                                }

                                else -> {
                                    error("Unexpected endpoint: ${request.url.encodedPath}")
                                }
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val innerTube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                val manager = PlaylistImportManager(database, YouTubeRepository(innerTube))
                val song = SongItem("song", "Song", null, emptyList(), null)
                val result = manager.importVerbose("List", listOf(song, song), PlaylistImportManager.Destination.NewSynced).getOrThrow()
                assertEquals(2, result.importedSongCount)
                assertEquals(2, database.dao().checkInPlaylist(result.playlist.id, song.id))
                assertEquals(2, added)
                assertEquals(1, browses)
                val allocations = driver.queries().count { it.contains("MAX(position)") }
                val reimported =
                    manager
                        .importVerbose(
                            "List",
                            listOf(song, song),
                            PlaylistImportManager.Destination.EXISTING(result.playlist),
                        ).getOrThrow()
                assertEquals(0, reimported.importedSongCount)
                assertEquals(2, added)
                assertEquals(allocations, driver.queries().count { it.contains("MAX(position)") })
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun localImportsPreserveOccurrencesAndReimportsOnlyAddMissingCopies() =
        runBlocking {
            val path = Files.createTempFile("playlist-occurrences", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Local import must not request the network") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val song = SongItem("song", "Song", null, emptyList(), null)
                val other = song.copy(id = "other")
                val result =
                    manager
                        .importVerbose(
                            "List",
                            listOf(song, other, song),
                            PlaylistImportManager.Destination.NewLocal,
                            coverUrl = "https://example.com/source-cover.jpg",
                        ).getOrThrow()
                assertEquals(3, result.importedSongCount)
                val dao = database.dao()
                assertEquals("https://example.com/source-cover.jpg", result.playlist.customThumbnailUrl)
                assertEquals(listOf("song", "other", "song"), dao.playlistSongs(result.playlist.id).first().map { it.song.id })
                dao.move(result.playlist.id, 0, 2)
                val reordered = dao.playlistSongs(result.playlist.id).first().map { it.toSongItem() }
                assertEquals(3, mapOf("songs" to reordered).playlistSongs().size)
                assertEquals(
                    listOf(
                        "song",
                        "other",
                        "song",
                    ),
                    reordered.sortedForPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.ASCENDING).map {
                        it.id
                    },
                )
                val tracks = listOf(song, other, song, song).map { PlaylistImportManager.CsvTrack(it.title, "", it.id) to it }
                val repeated =
                    manager
                        .importResolvedTracks(
                            "List",
                            tracks,
                            PlaylistImportManager.Destination.EXISTING(result.playlist),
                            null,
                        ).getOrThrow()
                assertEquals(1, repeated.importedSongCount)
                assertEquals(3, dao.checkInPlaylist(result.playlist.id, "song"))
                assertEquals(
                    0,
                    manager
                        .importResolvedTracks(
                            "List",
                            tracks,
                            PlaylistImportManager.Destination.EXISTING(result.playlist),
                            null,
                        ).getOrThrow()
                        .importedSongCount,
                )
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun failedLocalImportReportsCommittedBatchAndLeavesRemainingSongsUnsaved() =
        runBlocking {
            val path = Files.createTempFile("import-partial", ".db")
            Files.deleteIfExists(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Local import must not use the network") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val songs = (1..300).map { SongItem("song-$it", "Song $it", null, emptyList(), null) }
                val result =
                    PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                        .importVerbose("Partial", songs, PlaylistImportManager.Destination.NewLocal) { count, _, _ ->
                            if (count == 250) error("Write failed")
                        }
                val failure = result.exceptionOrNull() as PlaylistImportManager.IncompleteImportException
                assertEquals(250, failure.importedSongCount)
                assertEquals(50, failure.failedSongCount)
                assertEquals(failure.playlist.id, database.dao().findPlaylistByName("Partial")?.id)
                assertEquals(250, database.dao().playlistSongMaps(failure.playlist.id, 0).size)
                assertEquals(
                    "song-1",
                    database
                        .dao()
                        .getSongById("song-1")
                        ?.song
                        ?.id,
                )
                assertEquals(null, database.dao().getSongById("song-251"))
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun youtubeImportKeepsPlaylistEpisodesAndExcludesSuggestions() =
        runBlocking {
            val path = Files.createTempFile("metrolist-playlist-import", ".db")
            Files.deleteIfExists(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val body =
                """
                {"header":{"musicDetailHeaderRenderer":{"title":{"simpleText":"Source playlist"},"thumbnail":{"croppedSquareThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/playlist-cover.jpg"}]}}}}},
                "contents":{"sectionListRenderer":{"contents":[
                  {"musicPlaylistShelfRenderer":{"title":{"simpleText":"Tracks"},"contents":[
                    {"musicResponsiveListItemRenderer":{
                      "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
                      "playlistItemData":{"videoId":"song"}
                    }},
                    {"musicMultiRowListItemRenderer":{
                      "onTap":{"watchEndpoint":{"videoId":"episode","watchEndpointMusicSupportedConfigs":{
                        "watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_PODCAST_EPISODE"}
                      }}},
                      "title":{"runs":[{"text":"Episode"}]}
                    }}
                  ]}},
                  {"musicShelfRenderer":{"title":{"simpleText":"Suggestions"},"contents":[
                    {"musicResponsiveListItemRenderer":{
                      "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Suggestion"}]}}}],
                      "playlistItemData":{"videoId":"suggestion"}
                    }}
                  ]}}
                ]}}}
                """.trimIndent()
            val client =
                HttpClient(MockEngine { respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
                    install(ContentNegotiation) { json() }
                }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val (title, songs, coverUrl) = manager.loadYouTubePlaylist("PL1234567890").getOrThrow()

                assertEquals("Source playlist", title)
                assertEquals("https://example.com/playlist-cover.jpg", coverUrl)
                assertEquals(listOf("song", "episode"), songs.map(SongItem::id))
                assertEquals("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", songs.last().musicVideoType)
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun parsesQuotedCsvAndYouTubeLinks() {
        val tracks =
            PlaylistImportManager.parseCsv(
                """
                Track name,Artist name,URL
"Song, One",Artist,https://music.youtube.com/watch?v=abcdefghijk
Song Two,"Artist Two",
                """.trimIndent(),
            )

        assertEquals("Song, One", tracks[0].title)
        assertEquals("abcdefghijk", tracks[0].videoId)
        assertEquals("Artist Two", tracks[1].artist)
    }

    @Test
    fun providerReferencesWithMetadataRetainSearchFallback() {
        val spotify = PlaylistImportManager.CsvTrack("Song one", "Artist one", null, "spotify:track:abc")
        val apple =
            PlaylistImportManager.CsvTrack(
                "Song two",
                "Artist two",
                null,
                "https://music.apple.com/us/song/example/123",
            )
        val local = PlaylistImportManager.CsvTrack("Song three", "Artist three", null, "file:///music/song.mp3")
        val referenceOnly = PlaylistImportManager.CsvTrack("", "", null, "https://www.deezer.com/track/123")

        assertEquals("Song one Artist one", PlaylistImportManager.resolutionSearchQuery(spotify))
        assertEquals("Song two Artist two", PlaylistImportManager.resolutionSearchQuery(apple))
        assertEquals(null, PlaylistImportManager.resolutionSearchQuery(local))
        assertEquals(null, PlaylistImportManager.resolutionSearchQuery(referenceOnly))
    }

    @Test
    fun cancelledLocalImportRetainsCommittedBatchAndCanResumeIntoSameDestination() =
        runBlocking {
            val path = Files.createTempFile("metrolist-local-cancel", ".db")
            Files.deleteIfExists(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Local import must not use the network") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val songs = (1..251).map { SongItem("song-$it", "Song $it", null, emptyList(), null) }
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val cancelled =
                    try {
                        manager.importVerbose("Destination", songs, PlaylistImportManager.Destination.NewLocal) { saved, _, _ ->
                            if (saved == 250) throw CancellationException()
                        }
                        throw AssertionError("Expected cancellation")
                    } catch (error: PlaylistImportManager.CancelledImportException) {
                        error
                    }
                assertEquals(250, cancelled.importedSongCount)
                assertEquals(250, database.dao().playlistSongMaps(cancelled.playlist.id, 0).size)
                val resumed =
                    manager
                        .importResolvedTracks(
                            "Destination",
                            songs.map { PlaylistImportManager.CsvTrack(it.title, "", it.id) to it },
                            PlaylistImportManager.Destination.EXISTING(cancelled.playlist),
                            null,
                        ).getOrThrow()
                assertEquals(1, resumed.importedSongCount)
                assertEquals(251, database.dao().playlistSongMaps(cancelled.playlist.id, 0).size)
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun cancelledFavoritesImportPublishesCommittedLikesInOneSettingsBatch() =
        runBlocking {
            val path = Files.createTempFile("metrolist-favorites-cancel", ".db")
            Files.deleteIfExists(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Signed-out favorites must not use the network") })
            val backing = CountingSettings()
            Settings.replaceSharedForTest(Settings(backing))
            SongLikeState.replaceLikesFromRemote(emptyList())
            SongLikeState.awaitPersistence()
            backing.stringWrites.set(0)
            val songs =
                (1..3).map { index ->
                    SongItem("song-$index", "Song $index", null, listOf(ArtistTiny(null, "Artist")), null)
                }
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                try {
                    manager.importVerbose("Favorites", songs, PlaylistImportManager.Destination.Favorites) { imported, _, _ ->
                        if (imported == 2) throw CancellationException()
                    }
                    throw AssertionError("Import should have been cancelled")
                } catch (error: PlaylistImportManager.CancelledImportException) {
                    assertEquals(2, error.importedSongCount)
                    assertEquals("favorites", error.playlist.id)
                }

                SongLikeState.awaitPersistence()
                assertEquals(listOf("song-1", "song-2"), SongLikeState.likedSongsFlow.value.map(SongItem::id))
                assertEquals(4, backing.stringWrites.get())
                assertEquals(
                    listOf("song-1", "song-2"),
                    Json.decodeFromString<List<String>>(backing.getString(LikedSongsRecencyJsonKey, "[]")),
                )
                assertEquals(
                    true,
                    database
                        .dao()
                        .getSongById("song-1")
                        ?.song
                        ?.liked,
                )
                assertEquals(
                    true,
                    database
                        .dao()
                        .getSongById("song-2")
                        ?.song
                        ?.liked,
                )
                assertEquals(null, database.dao().getSongById("song-3"))
            } finally {
                SongLikeState.replaceLikesFromRemote(emptyList())
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun resolvedSpotifyFavoritesImportDeduplicatesLikesWithoutCreatingAPlaylistOrUsingTheNetwork() =
        runBlocking {
            val path = Files.createTempFile("metrolist-favorites-import", ".db")
            Files.deleteIfExists(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("Signed-out favorites must not use the network")
                    },
                )
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.refreshFromSettings()
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))
                val song = SongItem("abcdefghijk", "Song", null, listOf(ArtistTiny(null, "Artist")), null)

                val matches = listOf(PlaylistImportManager.CsvTrack(song.title, "Artist", null) to song)
                val result =
                    manager
                        .importResolvedTracks(
                            "Favorites",
                            matches + matches,
                            PlaylistImportManager.Destination.Favorites,
                            ImportSourceType.SPOTIFY,
                        ).getOrThrow()
                val repeated =
                    manager
                        .importResolvedTracks(
                            "Favorites",
                            matches,
                            PlaylistImportManager.Destination.Favorites,
                            ImportSourceType.SPOTIFY,
                        ).getOrThrow()

                assertEquals(1, result.importedSongCount)
                assertEquals(0, repeated.importedSongCount)
                assertEquals(null, database.dao().playlist("favorites").first())
                assertEquals(
                    true,
                    database
                        .dao()
                        .getSongById(song.id)
                        ?.song
                        ?.liked,
                )
                assertEquals(0, requests)
            } finally {
                Settings.replaceSharedForTest(null)
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun favoritesRemoteFailurePublishesOnlyAcknowledgedLikesAndCanResume() =
        runBlocking {
            val path = Files.createTempFile("favorites-partial", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            var rejectSecond = true
            val client =
                HttpClient(
                    MockEngine { request ->
                        val second = (request.body as TextContent).text.contains("second")
                        respond(
                            "{}",
                            if (rejectSecond && second) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.refreshFromSettings()
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" }))
                val songs = listOf("first", "second").map { SongItem(it, it, null, emptyList(), null) }

                suspend fun import() = manager.importVerbose("Favorites", songs, PlaylistImportManager.Destination.Favorites)
                val failure = import().exceptionOrNull() as PlaylistImportManager.IncompleteImportException
                assertEquals(1, failure.importedSongCount)
                assertEquals(1, failure.failedSongCount)
                assertEquals(listOf("first"), SongLikeState.likedSongs.map { it.id })
                assertEquals(null, database.dao().getSongById("second"))
                assertEquals(
                    mapOf("first" to SongVote.LIKE),
                    Json.decodeFromString<Map<String, SongVote>>(Settings.shared.pendingSongVotesJson),
                )
                rejectSecond = false
                assertEquals(1, import().getOrThrow().importedSongCount)
                assertEquals(listOf("first", "second"), SongLikeState.likedSongs.map { it.id })
            } finally {
                SongLikeState.awaitPersistence()
                Settings.replaceSharedForTest(null)
                SongLikeState.refreshFromSettings()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun unchangedSharedLikesStillPersistImportMetadataWithoutNewVotes() =
        runBlocking {
            val path = Files.createTempFile("favorites-metadata", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("An unchanged like must not request a vote") })
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.refreshFromSettings()
            try {
                val song = SongItem("song", "Song", null, emptyList(), null, customTitle = "Imported title")
                SongLikeState.replaceLikesLocally(listOf(song))
                val manager = PlaylistImportManager(database, YouTubeRepository(InnerTube(client)))

                suspend fun import() =
                    manager
                        .importVerbose("Favorites", listOf(song), PlaylistImportManager.Destination.Favorites)
                        .getOrThrow()
                        .importedSongCount
                assertEquals(0, import())
                val stored = requireNotNull(database.dao().getSongById(song.id)).song
                assertEquals("Imported title", stored.customTitle)
                assertEquals(true, stored.liked)
                assertEquals(0, import())
                assertEquals(
                    stored.likedDate,
                    database
                        .dao()
                        .getSongById(song.id)
                        ?.song
                        ?.likedDate,
                )
            } finally {
                SongLikeState.awaitPersistence()
                Settings.replaceSharedForTest(null)
                SongLikeState.refreshFromSettings()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun favoritesReimportRestoresAnUnlikedSongDespiteItsStoredLikedFlag() =
        runBlocking {
            for (authenticated in listOf(false, true)) {
                val path = Files.createTempFile("favorites-reimport", ".db")
                Files.delete(path)
                val database = getDatabaseBuilder(path.toFile()).build()
                val requests = mutableListOf<String>()
                val client =
                    HttpClient(
                        MockEngine { request ->
                            check(authenticated) { "Signed-out import must not request votes" }
                            requests += request.url.encodedPath
                            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ) { install(ContentNegotiation) { json() } }
                Settings.replaceSharedForTest(Settings(MapSettings()))
                SongLikeState.refreshFromSettings()
                try {
                    val repository = YouTubeRepository(InnerTube(client).apply { if (authenticated) cookie = "SAPISID=test" })
                    val manager = PlaylistImportManager(database, repository)
                    val song = SongItem("song", "Song", null, emptyList(), null)

                    suspend fun import() =
                        manager
                            .importVerbose(
                                "Favorites",
                                listOf(song, song),
                                PlaylistImportManager.Destination.Favorites,
                            ).getOrThrow()
                            .importedSongCount

                    assertEquals(1, import())
                    assertEquals(0, import())
                    assertTrue(SongLikeState.removeLike(song.id))
                    assertEquals(
                        true,
                        database
                            .dao()
                            .getSongById(song.id)
                            ?.song
                            ?.liked,
                    )
                    SongLikeState.syncPendingVotes(repository)
                    assertEquals(1, import())
                    assertTrue(SongLikeState.isLiked(song.id))
                    assertEquals(
                        mapOf(song.id to SongVote.LIKE),
                        Json.decodeFromString<Map<String, SongVote>>(Settings.shared.pendingSongVotesJson),
                    )
                    assertEquals(0, import())
                    assertEquals(if (authenticated) 2 else 0, requests.count { it.endsWith("/like/like") })
                    assertEquals(if (authenticated) 1 else 0, requests.count { it.endsWith("/like/removelike") })
                } finally {
                    SongLikeState.awaitPersistence()
                    Settings.replaceSharedForTest(null)
                    SongLikeState.refreshFromSettings()
                    client.close()
                    database.close()
                    Files.deleteIfExists(path)
                }
            }
        }

    @Test
    fun skipsRemoteMembershipScanForNewPlaylist() {
        assertEquals(false, PlaylistImportManager.shouldLoadRemotePlaylistSongs("new-id", "new-id"))
        assertEquals(true, PlaylistImportManager.shouldLoadRemotePlaylistSongs("existing-id", null))
        assertEquals(false, PlaylistImportManager.shouldLoadRemotePlaylistSongs(null, null))
    }

    @Test
    fun retriesTrackResolutionTwice() {
        assertEquals(true, PlaylistImportManager.shouldRetryTrackResolution(0))
        assertEquals(true, PlaylistImportManager.shouldRetryTrackResolution(1))
        assertEquals(false, PlaylistImportManager.shouldRetryTrackResolution(2))
    }

    @Test
    fun retriesRemoteMutationOnlyAfterAConclusiveMembershipCheck() {
        assertEquals(true, PlaylistImportManager.shouldRetryRemoteAdd(0, verificationSucceeded = true))
        assertEquals(true, PlaylistImportManager.shouldRetryRemoteAdd(1, verificationSucceeded = true))
        assertEquals(false, PlaylistImportManager.shouldRetryRemoteAdd(2, verificationSucceeded = true))
        assertEquals(false, PlaylistImportManager.shouldRetryRemoteAdd(0, verificationSucceeded = false))
    }

    @Test
    fun parsesPlaylistUrlAndBrowseId() {
        assertEquals("PL1234567890", PlaylistImportManager.parsePlaylistId("https://youtube.com/playlist?list=PL1234567890"))
        assertEquals("PL1234567890", PlaylistImportManager.parsePlaylistId("VLPL1234567890"))
    }

    @Test
    fun largeResolutionUsesBoundedWorkersAndKeepsOrderAndOccurrences() =
        runBlocking {
            val path = Files.createTempFile("import-workers", ".db")
            Files.delete(path)
            val database = getDatabaseBuilder(path.toFile()).build()
            val client = HttpClient(MockEngine { error("Local references must not use the network") })
            val innerTube = InnerTube(client)
            try {
                val manager = PlaylistImportManager(database, YouTubeRepository(innerTube))
                val tracks =
                    List(100_000) {
                        PlaylistImportManager.CsvTrack(
                            "Song $it",
                            "Artist",
                            null,
                            directReference = "file:///music/${it % 17}.flac",
                            isResolvedLocalReference = true,
                        )
                    }
                val owner = coroutineContext.job
                var progress = 0
                val result =
                    manager.resolveImportTracks(tracks, null, null) { completed, total, _ ->
                        assertEquals(++progress, completed)
                        assertEquals(tracks.size, total)
                        assertTrue(
                            owner.children
                                .single()
                                .children
                                .count() <= 4,
                        )
                    }
                assertEquals(tracks, result.map { it.first })
                assertEquals(tracks.map { it.directReference }, result.map { it.second.id })
                assertEquals(tracks.size, progress)
            } finally {
                innerTube.close()
                client.close()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}

private class CountingSettings(
    private val delegate: KmpSettings = MapSettings(),
) : KmpSettings by delegate {
    val stringWrites = AtomicInteger()

    override fun putString(
        key: String,
        value: String,
    ) {
        stringWrites.incrementAndGet()
        delegate.putString(key, value)
    }
}

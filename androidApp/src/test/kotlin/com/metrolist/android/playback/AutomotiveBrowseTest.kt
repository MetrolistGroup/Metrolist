package com.metrolist.android.playback

import android.content.ContextWrapper
import android.content.res.Resources
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.snapshots.Snapshot
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.disk.DiskCache
import com.metrolist.android.R
import com.metrolist.android.constants.MediaSessionConstants
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playlist.removeSongsFromStoredPlaylist
import com.metrolist.shared.settings.AutomotivePinnedPlaylist
import com.metrolist.shared.settings.AutomotivePlaylistSource
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.setAutomotivePinnedPlaylists
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.utils.bucketedArtworkUrl
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutomotiveBrowseTest {
    private lateinit var database: MusicDatabase
    private lateinit var client: HttpClient
    private lateinit var repository: YouTubeRepository
    private lateinit var audio: File
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                    override fun getString(id: Int): String = if (id == R.string.downloads) "Pobrane" else id.toString()
                }

            override fun getResources(): Resources = labels
        }
    }

    @Before
    fun setUp() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            SongLikeState.refreshFromSettings()
            Settings.shared.hidePodcasts = false
            database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            client =
                HttpClient(MockEngine { respond(browseResponse, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            repository = YouTubeRepository(InnerTube(client))
            val date = LocalDateTime(2026, 1, 1, 0, 0)
            val dao = database.dao()
            dao.insert(SongEntity("song", "Song", albumId = "album", inLibrary = date, liked = true, likedDate = date, isUploaded = true))
            dao.insert(SongEntity("episode", "Episode", isEpisode = true, inLibrary = date))
            dao.upsert(AlbumEntity("album", title = "Album", songCount = 1, duration = 100))
            dao.upsert(ArtistEntity("artist", "Artist"))
            dao.upsert(SongAlbumMap("song", "album", 0))
            dao.upsert(SongArtistMap("song", "artist", 0))
            dao.insert(Event(songId = "song", timestamp = date, playTime = 1000))
            dao.insert(PlaylistEntity("local", "main", isLocal = true))
            dao.insert(PlaylistSongMap(playlistId = "local", songId = "song"))
            dao.insert(PlaylistEntity("stored", "Remote", browseId = "VLPLremote", remoteSongCount = 1))
            Settings.shared.setAutomotivePinnedPlaylists(
                listOf(AutomotivePinnedPlaylist(AutomotivePlaylistSource.LOCAL, "stored", "Remote")),
            )
            audio = File.createTempFile("automotive", ".audio").apply { writeText("audio") }
            for (kind in listOf(KIND_CACHE, KIND_DOWNLOAD)) {
                dao.upsert(
                    CachedAudioEntity(
                        songId = kind,
                        title = kind,
                        artist = "Artist",
                        filePath = audio.path,
                        fileSize = audio.length(),
                        cachedAt = 0,
                        lastAccessedAt = 0,
                        cacheLocation = "INTERNAL",
                        kind = kind,
                    ),
                )
            }
        }

    @After
    fun tearDown() {
        Settings.shared.likedSongsJson = "[]"
        SongLikeState.refreshFromSettings()
        database.close()
        client.close()
        audio.delete()
        Settings.replaceSharedForTest(null)
    }

    private fun library() = AutomotiveMediaLibrary(context, database, repository)

    @Test
    fun builtinFoldersPublishTheirCollectionArtwork() {
        val roots = library().rootChildren(childLimit = Int.MAX_VALUE).associateBy { it.mediaId }
        mapOf(
            AutomotiveMediaIds.LIKED to "auto_playlist_icon_liked",
            AutomotiveMediaIds.DOWNLOADS to "auto_playlist_icon_downloaded",
            AutomotiveMediaIds.TOP to "auto_playlist_icon_top100",
            AutomotiveMediaIds.CACHED to "auto_playlist_icon_cached",
            AutomotiveMediaIds.UPLOADED to "auto_playlist_icon_uploaded",
            AutomotiveMediaIds.PODCASTS to "auto_playlist_icon_podcasts",
        ).forEach { (id, icon) ->
            assertEquals(mediaArtworkUri(context, icon), roots.getValue(id).mediaMetadata.artworkUri)
        }
        Settings.shared.setCollectionCover("liked", "content://covers/liked")
        assertEquals(
            Uri.parse("content://covers/liked"),
            library()
                .rootChildren(childLimit = Int.MAX_VALUE)
                .first { it.mediaId == AutomotiveMediaIds.LIKED }
                .mediaMetadata.artworkUri,
        )
    }

    @Test
    fun collectionShuffleIsFirstPlayableRowAndResolvesAfterServiceRestart() =
        runBlocking {
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "episode", position = 1))
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "song", position = 2))
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "episode", position = 3))
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "song", position = 4))
            for ((parent, expectedSongs) in listOf(
                AutomotiveMediaIds.localPlaylist("local") to listOf("song", "episode", "song", "episode", "song"),
                AutomotiveMediaIds.youtubePlaylist("VLPLremote") to listOf("remote-song"),
                AutomotiveMediaIds.album("album") to listOf("song"),
                AutomotiveMediaIds.LIKED to listOf("song"),
                AutomotiveMediaIds.DOWNLOADS to listOf(KIND_DOWNLOAD),
            )) {
                val library = library()
                val items = library.children(parent, 0, 100)
                val shuffle = items.first()
                assertEquals("$parent/__shuffle__", shuffle.mediaId)
                assertEquals(context.getString(R.string.shuffle), shuffle.mediaMetadata.title.toString())
                assertEquals(true, shuffle.mediaMetadata.isPlayable)
                assertEquals(false, shuffle.mediaMetadata.isBrowsable)
                assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, shuffle.mediaMetadata.mediaType)
                assertEquals("android.resource", shuffle.mediaMetadata.artworkUri?.scheme)
                assertTrue(shuffle.mediaMetadata.supportedCommands.isEmpty())
                assertNull(AutomotiveMediaIds.songId(shuffle.mediaId))
                val pages = (0..expectedSongs.size).flatMap { library.children(parent, it, 1) }
                assertEquals(items.map { it.mediaId }, pages.map { it.mediaId })
                assertTrue(library.children(parent, expectedSongs.size + 1, 1).isEmpty())
                assertEquals(expectedSongs.indices.toList(), items.drop(1).map { AutomotiveMediaIds.browseSong(it.mediaId)!!.index })
                assertFalse(library.resolveSongQueue(items[1])!!.shuffle)

                val restored = library()
                assertEquals(shuffle.mediaId, restored.item(shuffle.mediaId)?.mediaId)
                val request = MediaItem.Builder().setMediaId(shuffle.mediaId).build()
                val queue = restored.resolveSongQueue(request, Random(4))!!
                val expectedStartIndex = Random(4).nextInt(expectedSongs.size)
                assertEquals(expectedSongs, queue.songs.map { it.id })
                assertEquals(expectedStartIndex, queue.startIndex)
                if (expectedSongs.size > 1) assertEquals(1, queue.startIndex)
                assertTrue(queue.shuffle)
                assertNull(restored.resolveSong(request))
                assertTrue(restored.resolveAddition(request).isEmpty())
                val resolution = resolveAutomotiveSongRequest(1, 0, null, queue, emptyList())!!
                val gate = AutomotivePlaybackRequestGate()
                val result =
                    gate.resolve(
                        gate.start(false),
                        resolution.songs.map(restored::playableItem),
                        resolution.startIndex,
                        C.TIME_UNSET,
                        shuffleModeEnabled = queue.shuffle,
                        playlistPage = queue.playlistPage,
                    )
                val applied =
                    gate.consume(
                        result.mediaItems,
                        result.startIndex,
                        result.startPositionMs,
                    ) as AutomotiveMediaItemsApplication.Apply
                assertEquals(true, applied.shuffleModeEnabled)
                assertEquals(expectedSongs, applied.mediaItems.map { AutomotiveMediaIds.songId(it.mediaId) })
                assertEquals(C.TIME_UNSET, result.startPositionMs)
                for (currentShuffle in listOf(false, true)) {
                    for (persistentShuffle in listOf(false, true)) {
                        val playback = MusicQueue().apply { setQueue(resolution.songs, result.startIndex) }
                        if (newQueueShuffleEnabled(applied.shuffleModeEnabled, currentShuffle, persistentShuffle)) {
                            playback.toggleShuffle(preserveHistory = applied.shuffleModeEnabled != true)
                        }
                        assertTrue(playback.isShuffled)
                        assertEquals(0, playback.currentIndex.value)
                        assertEquals(expectedStartIndex, playback.snapshot().originalIndices.first())
                        val playbackIds = playback.items.value.map { it.id }
                        assertEquals(expectedSongs[expectedStartIndex], playbackIds.first())
                        assertEquals(expectedSongs.sorted(), playbackIds.sorted())
                        assertEquals(queue.songs, playback.snapshot().originalItems)
                        playback.toggleShuffle()
                        assertFalse(playback.isShuffled)
                        assertEquals(0, playback.currentIndex.value)
                        assertEquals(
                            listOf(queue.songs[expectedStartIndex]) + queue.songs.filterIndexed { index, _ -> index != expectedStartIndex },
                            playback.items.value,
                        )
                    }
                }
            }
        }

    @Test
    fun shuffleRemotePlaylistRetainsContinuationAndDuplicateOccurrences() =
        runBlocking {
            client.close()
            val initialIds = List(50) { "same" }
            var requests = 0
            client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val continued = (request.body as TextContent).text.contains("continuation")
                        respond(
                            if (continued) {
                                playlistResponse(listOf("last"), continuationPage = true)
                            } else {
                                playlistResponse(initialIds, continuation = "next")
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            val library = library()
            val request = MediaItem.Builder().setMediaId(AutomotiveMediaIds.shuffle(AutomotiveMediaIds.youtubePlaylist("PLremote"))).build()
            val queue = library.resolveSongQueue(request)!!
            assertTrue(queue.shuffle)
            assertEquals(initialIds, queue.songs.map { it.id })
            assertEquals(1, requests)
            assertTrue(queue.playlistPage!!.hasMore)
            val completed = repository.continuePlaylistPage(queue.playlistPage, applyContentFilters = false)
            assertEquals(initialIds + "last", completed.songs.map { it.id })
        }

    @Test
    fun emptyAndBlockedCollectionsHaveNoShuffleAndUnsupportedParentsRejectIt() =
        runBlocking {
            Settings.shared.blockedSongIds = "song"
            val library = library()
            for (parent in listOf(
                AutomotiveMediaIds.LIKED,
                AutomotiveMediaIds.album("album"),
                AutomotiveMediaIds.localPlaylist("missing"),
            )) {
                assertTrue(library.children(parent, 0, 10).isEmpty())
                assertNull(library.resolveSongQueue(MediaItem.Builder().setMediaId(AutomotiveMediaIds.shuffle(parent)).build()))
            }
            for (parent in listOf(
                AutomotiveMediaIds.ROOT,
                AutomotiveMediaIds.HOME,
                AutomotiveMediaIds.PLAYLISTS,
                AutomotiveMediaIds.SONGS,
            )) {
                val id = AutomotiveMediaIds.shuffle(parent)
                assertNull(AutomotiveMediaIds.shuffleParent(id))
                assertNull(library.resolveSongQueue(MediaItem.Builder().setMediaId(id).build()))
            }
        }

    @Test
    fun browseSongsAdvertiseQueueActionAndResolveOnlyTheSelectedSong() =
        runBlocking {
            val library = library()
            for (parent in listOf(
                AutomotiveMediaIds.HOME,
                AutomotiveMediaIds.HISTORY,
                AutomotiveMediaIds.SONGS,
                AutomotiveMediaIds.LIKED,
                AutomotiveMediaIds.localPlaylist("local"),
                AutomotiveMediaIds.youtubePlaylist("VLPLremote"),
            )) {
                val children = library.children(parent, 0, 100)
                val songs = children.filter { AutomotiveMediaIds.songId(it.mediaId) != null }
                assertTrue(parent, songs.isNotEmpty())
                for (song in songs) {
                    assertEquals(listOf(MediaSessionConstants.ACTION_ADD_TO_QUEUE), song.mediaMetadata.supportedCommands)
                    val request = MediaItem.Builder().setMediaId(song.mediaId).build()
                    assertEquals(listOf(AutomotiveMediaIds.songId(song.mediaId)), library.resolveAddition(request).map { it.id })
                }
                assertTrue(children.filter { it.mediaMetadata.isBrowsable == true }.all { it.mediaMetadata.supportedCommands.isEmpty() })
            }
        }

    @Test
    fun evictedMediaDropsSongAndQueueAliasesAndRebuildsSelection() =
        runBlocking {
            val library = library()
            val selected = library.children(AutomotiveMediaIds.LIKED, 0, 20)[1]
            assertNotNull(library.resolveCachedSong(selected))
            repeat(2_100) { index ->
                library.playableItem(SongItem("cache-$index", "Track", null, emptyList(), null))
            }
            val cache = ReflectionHelpers.getField<LruCache<String, *>>(library, "mediaItems")
            assertEquals(2_000, cache.size())
            assertNotNull(library.resolveCachedSong(selected))
            assertNull(library.resolveCachedSong(MediaItem.Builder().setMediaId(selected.mediaId).build()))
            for (name in listOf("songQueuesByMediaId", "songQueueParents", "likedQueueContexts")) {
                assertFalse(ReflectionHelpers.getField<Map<String, *>>(library, name).containsKey(selected.mediaId))
            }
            assertEquals("song", library.resolveSong(selected)?.id)
            assertEquals(
                "song",
                library
                    .resolveSongQueue(selected)
                    ?.songs
                    ?.first()
                    ?.id,
            )
            assertTrue(cache.size() <= 2_000)
        }

    @Test
    fun querySpecificAliasesAreEvictedAndOldSearchSelectionsCanBeRebuilt() =
        runBlocking {
            val library = AutomotiveMediaLibrary(context, database, repository, { false })
            val selected = library.search("Song", 0, 20).first()
            repeat(2_100) { index -> library.search("Song" + " ".repeat(index + 1), 0, 20) }
            val cache = ReflectionHelpers.getField<LruCache<String, *>>(library, "mediaItems")
            assertEquals(2_000, cache.size())
            assertFalse(cache.snapshot().containsKey(selected.mediaId))
            for (name in listOf("songQueuesByMediaId", "songQueueParents", "likedQueueContexts")) {
                val entries = ReflectionHelpers.getField<Map<String, *>>(library, name)
                assertTrue(entries.size <= 2_000)
                assertFalse(entries.containsKey(selected.mediaId))
            }
            assertEquals(
                "song",
                library
                    .resolveSongQueue(selected)
                    ?.songs
                    ?.first()
                    ?.id,
            )
        }

    @Test
    fun largeBrowseBatchRetainsEveryQueueSongWhileSessionCacheStaysBounded() =
        runBlocking {
            val songs = List(2_100) { SongItem("large-$it", "Track $it", null, emptyList(), null) }
            val library = library()
            val items = songs.map(library::playableItem)
            val cached =
                ReflectionHelpers.callInstanceMethod<List<MediaItem>>(
                    library,
                    "cacheSongQueue",
                    ReflectionHelpers.ClassParameter.from(List::class.java, items),
                    ReflectionHelpers.ClassParameter.from(String::class.java, "auto:search:large"),
                    ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                )
            val selected = cached.last()
            val queue = library.resolveSongQueue(selected)!!
            assertEquals(songs, queue.songs)
            assertEquals(songs.lastIndex, queue.startIndex)
            assertEquals(2_000, ReflectionHelpers.getField<LruCache<String, *>>(library, "mediaItems").size())
            val gate = AutomotivePlaybackRequestGate()
            val result = gate.resolve(gate.start(false), queue.songs.map(library::playableItem), queue.startIndex, C.TIME_UNSET)
            repeat(2_100) { index -> library.playableItem(songs.first().copy(id = "replacement-$index")) }
            val applied =
                gate.consume(
                    result.mediaItems,
                    result.startIndex,
                    result.startPositionMs,
                ) as AutomotiveMediaItemsApplication.Apply
            assertEquals(songs, applied.mediaItems.map(library::resolveCachedSong))
            assertEquals(AutomotiveMediaItemsApplication.Reject, gate.consume(result.mediaItems, result.startIndex, result.startPositionMs))
        }

    @Test
    fun homeRecommendationsRemainBrowsableWhenThereAreNoSongShelves() =
        runBlocking {
            client.close()
            val cards =
                listOf("MUSIC_PAGE_TYPE_ALBUM" to "MPREb_album", "MUSIC_PAGE_TYPE_ARTIST" to "UC_artist")
                    .joinToString(",") { (type, id) ->
                        """{"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$id"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"$id","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"$type"}}}}}}"""
                    }
            client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"title":{"runs":[{"text":"Recommendations"}]},"contents":[$cards]}}]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            val items = library().children(AutomotiveMediaIds.HOME, 0, 100)
            assertEquals(listOf(AutomotiveMediaIds.album("MPREb_album"), AutomotiveMediaIds.artist("UC_artist")), items.map { it.mediaId })
            assertTrue(items.all { it.mediaMetadata.isBrowsable == true })
        }

    @Test
    fun concurrentHomePagesShareASnapshotAndTransientEmptyRefreshesKeepIt() =
        runBlocking {
            client.close()
            var requests = 0
            var response = browseResponse
            client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            var now = 1L
            var connected = true
            val library = AutomotiveMediaLibrary(context, database, repository, { connected }, { now })
            val pages = (0..1).map { page -> async { library.children(AutomotiveMediaIds.HOME, page, 1) } }.awaitAll()
            assertEquals(1, requests)
            assertEquals("remote-song", AutomotiveMediaIds.songId(pages[0].single().mediaId))
            assertEquals(AutomotiveMediaIds.youtubePlaylist("VLPLremote"), pages[1].single().mediaId)
            val original = pages.flatten().map { it.mediaId }
            now += 120_001L
            response = "{}"
            assertEquals(original, library.children(AutomotiveMediaIds.HOME, 0, 100).map { it.mediaId })
            connected = false
            assertEquals(original, library.children(AutomotiveMediaIds.HOME, 0, 100).map { it.mediaId })
            Settings.shared.dataSyncId = "different-account"
            assertTrue(library.children(AutomotiveMediaIds.HOME, 0, 100).isEmpty())
        }

    @Test
    fun failedHomeRefreshRetainsItemsAndCancelledRequestCanBeRetried() =
        runBlocking {
            client.close()
            var failure: Exception? = CancellationException("browse cancelled")
            client =
                HttpClient(
                    MockEngine {
                        failure?.let { throw it }
                        respond(browseResponse, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            var now = 1L
            val library = AutomotiveMediaLibrary(context, database, repository, { true }, { now })
            try {
                library.children(AutomotiveMediaIds.HOME, 0, 100)
                error("Cancellation must propagate")
            } catch (_: CancellationException) {
            }
            failure = null
            val original = library.children(AutomotiveMediaIds.HOME, 0, 100).map { it.mediaId }
            assertFalse(original.isEmpty())
            now += 120_001L
            failure = IOException("offline")
            assertEquals(original, library.children(AutomotiveMediaIds.HOME, 0, 100).map { it.mediaId })
        }

    @Test
    fun artistAndAlbumBrowseKeepIdOnlyBlockingAndRefreshBetweenOperations() =
        runBlocking {
            database.dao().upsert(AlbumArtistMap("album", "artist", 0))
            val library = library()
            Settings.shared.blockedArtistsJson = """[{"id":"other-id","name":"Artist"}]"""
            assertEquals(1, library.children(AutomotiveMediaIds.ALBUMS, 0, 100).size)
            assertEquals(1, library.children(AutomotiveMediaIds.ARTISTS, 0, 100).size)
            Settings.shared.blockedArtistIds = "artist"
            assertTrue(library.children(AutomotiveMediaIds.ALBUMS, 0, 100).isEmpty())
            assertTrue(library.children(AutomotiveMediaIds.ARTISTS, 0, 100).isEmpty())
            Settings.shared.blockedArtistIds = ""
            assertEquals(1, library.children(AutomotiveMediaIds.ARTISTS, 0, 100).size)
        }

    @Test
    fun restoredCollectionsResolveBeforeTheirParentIsBrowsed() =
        runBlocking {
            for (id in listOf(
                AutomotiveMediaIds.localPlaylist("local"),
                AutomotiveMediaIds.youtubePlaylist("VLPLremote"),
                AutomotiveMediaIds.album("album"),
                AutomotiveMediaIds.artist("artist"),
                AutomotiveMediaIds.PINNED_PLAYLISTS,
            )) {
                val restored = library().item(id)
                assertNotNull(id, restored)
                assertEquals(id, restored!!.mediaId)
                assertEquals(id, true, restored.mediaMetadata.isBrowsable)
            }
        }

    @Test
    fun everyAdvertisedFolderReturnsChildrenWhenItsSourceHasData() =
        runBlocking {
            val library = library()
            for (folder in library.rootChildren(browsableOnly = true)) {
                val children = library.children(folder.mediaId, 0, Int.MAX_VALUE)
                assertFalse(folder.mediaId, children.isEmpty())
                for (child in children.filter { it.mediaMetadata.isBrowsable == true }) {
                    assertFalse(child.mediaId, library.children(child.mediaId, 0, Int.MAX_VALUE).isEmpty())
                }
            }
        }

    @Test
    fun savedRemotePinsAndOldLocalIdsFetchRemoteSongsWithoutRoomMembership() =
        runBlocking {
            assertTrue(
                database
                    .dao()
                    .playlistSongs("stored")
                    .first()
                    .isEmpty(),
            )
            val library = library()
            val pin = MediaItem.Builder().setMediaId(AutomotiveMediaIds.pinnedLocalPlaylist("stored")).build()
            assertEquals(listOf("remote-song"), library.resolvePinnedPlaylist(pin)!!.songs.map { it.id })
            assertEquals(
                listOf("remote-song"),
                library.children(AutomotiveMediaIds.localPlaylist("stored"), 0, 100).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) },
            )
            assertEquals(
                listOf("song"),
                library.children(AutomotiveMediaIds.localPlaylist("local"), 0, 100).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) },
            )
        }

    @Test
    fun youtubePlaylistFolderUsesOnlySavedRemotePlaylistsOfflineWithPaging() =
        runBlocking {
            client.close()
            var requests = 0
            client =
                HttpClient(
                    MockEngine {
                        requests++
                        throw IOException("offline")
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            val dao = database.dao()
            dao.insert(PlaylistEntity("another", "Another", browseId = "PLanother"))
            dao.insert(PlaylistEntity("local-copy", "Local copy", browseId = "PLcopy", isLocal = true))
            dao.insert(PlaylistEntity("missing-id", "Missing ID"))
            dao.insert(PlaylistEntity("blank-id", "Blank ID", browseId = ""))
            dao.insert(PlaylistEntity("unsaved", "Unsaved", browseId = "PLunsaved", bookmarkedAt = null))
            dao.insert(PlaylistSongMap(playlistId = "stored", songId = "song", position = 0))
            dao.insert(PlaylistSongMap(playlistId = "stored", songId = "episode", position = 1))
            val library = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { false })

            val first = library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 0, 1).single()
            val second = library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 1, 1).single()
            assertEquals(AutomotiveMediaIds.youtubePlaylist("PLanother"), first.mediaId)
            assertEquals(AutomotiveMediaIds.youtubePlaylist("VLPLremote"), second.mediaId)
            assertEquals("Remote", second.mediaMetadata.title.toString())
            assertTrue(second.mediaMetadata.isBrowsable == true)
            assertTrue(library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 2, 1).isEmpty())
            assertEquals(
                listOf("song", "episode"),
                library.children(second.mediaId, 0, 100).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) },
            )
            assertEquals(0, requests)
        }

    @Test
    fun youtubePlaylistFolderFallsBackOnRequestFailureButPropagatesCancellation() =
        runBlocking {
            client.close()
            var requests = 0
            var failure: Exception = IOException("offline")
            client =
                HttpClient(
                    MockEngine {
                        requests++
                        throw failure
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            val library = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { true })

            assertEquals(
                listOf(AutomotiveMediaIds.youtubePlaylist("VLPLremote")),
                library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 0, 100).map { it.mediaId },
            )
            assertTrue(requests > 0)
            failure = CancellationException("browse cancelled")
            try {
                library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 0, 100)
                error("Cancellation must propagate")
            } catch (_: CancellationException) {
            }
        }

    @Test
    fun youtubePlaylistFolderKeepsOnlineResultsAndSwitchesToStoredPlaylistsAfterDisconnecting() =
        runBlocking {
            client.close()
            var response = browseResponse.replace("VLPLremote", "VLPLonline")
            client =
                HttpClient(MockEngine { respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json")) }) {
                    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            var connected = true
            val library = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { connected })

            assertEquals(
                listOf(AutomotiveMediaIds.youtubePlaylist("VLPLonline")),
                library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 0, 100).map { it.mediaId },
            )
            response = """{"contents":{"sectionListRenderer":{"contents":[]}}}"""
            assertTrue(library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 0, 100).isEmpty())
            connected = false
            assertEquals(
                listOf(AutomotiveMediaIds.youtubePlaylist("VLPLremote")),
                library.children(AutomotiveMediaIds.YOUTUBE_PLAYLISTS, 0, 100).map { it.mediaId },
            )
        }

    @Test
    fun remoteBrowseIdsUseStoredMembershipOfflineWithPaging() =
        runBlocking {
            client.close()
            client = HttpClient(MockEngine { throw IOException("offline") })
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "song", position = 0))
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "episode", position = 1))
            val library = library()
            for (id in listOf("PLremote", "VLPLremote")) {
                assertEquals(
                    listOf("song"),
                    library.children(AutomotiveMediaIds.youtubePlaylist(id), 1, 1).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) },
                )
                assertEquals(
                    listOf("episode"),
                    library.children(AutomotiveMediaIds.youtubePlaylist(id), 2, 1).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) },
                )
                assertTrue(library.children(AutomotiveMediaIds.youtubePlaylist(id), 3, 1).isEmpty())
            }
        }

    @Test
    fun failedContinuationKeepsAlreadyLoadedSongsInsteadOfReplacingThemWithRoom() =
        runBlocking {
            client.close()
            var requests = 0
            client =
                HttpClient(
                    MockEngine {
                        if (requests++ > 0) throw IOException("offline")
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Remote song"}]}}}],"playlistItemData":{"videoId":"remote-song"}}}],"continuations":[{"nextContinuationData":{"continuation":"next"}}]}}]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "song", position = 0))
            val library = library()
            assertEquals(
                listOf("remote-song"),
                library
                    .children(
                        AutomotiveMediaIds.youtubePlaylist("VLPLremote"),
                        0,
                        100,
                    ).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) },
            )
            assertTrue(requests > 1)
        }

    @Test
    fun browsePagesKeepTheEntireLocalPlaylistAsPlaybackContext() =
        runBlocking {
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "episode", position = 1))
            val library = library()
            val first = library.children(AutomotiveMediaIds.localPlaylist("local"), 1, 1).single()
            val second = library.children(AutomotiveMediaIds.localPlaylist("local"), 2, 1).single()
            assertEquals(listOf("song", "episode"), library.resolveSongQueue(first)!!.songs.map { it.id })
            assertEquals(0, library.resolveSongQueue(first)!!.startIndex)
            assertEquals(1, library.resolveSongQueue(second)!!.startIndex)
        }

    @Test
    fun offlineRemotePinsAndPreviouslyBrowsedSongsUseCompleteStoredOrder() =
        runBlocking {
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "song", position = 0))
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "episode", position = 1))
            Settings.shared.setAutomotivePinnedPlaylists(
                listOf(AutomotivePinnedPlaylist(AutomotivePlaylistSource.YOUTUBE, "PLremote", "Remote")),
            )
            var connected = true
            val library = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { connected })
            val onlineItem = library.children(AutomotiveMediaIds.youtubePlaylist("PLremote"), 1, 1).single()
            connected = false
            assertEquals(null, library.resolveSongQueue(onlineItem))
            val item = library.children(AutomotiveMediaIds.youtubePlaylist("PLremote"), 2, 1).single()
            assertEquals(listOf("song", "episode"), library.resolveSongQueue(item)!!.songs.map { it.id })
            assertEquals(1, library.resolveSongQueue(item)!!.startIndex)
            val pin = MediaItem.Builder().setMediaId(AutomotiveMediaIds.pinnedYoutubePlaylist("PLremote")).build()
            assertEquals(listOf("song", "episode"), library.resolvePinnedPlaylist(pin)!!.songs.map { it.id })
        }

    @Test
    fun browsingHistoryDoesNotReplaceTheSelectedFavouritesQueue() =
        runBlocking {
            val liked =
                listOf(
                    SongItem("song", "First", null, emptyList(), null),
                    SongItem("next-favourite", "Second", null, emptyList(), null),
                    SongItem("last-favourite", "Third", null, emptyList(), null),
                )
            Settings.shared.likedSongsJson = Json.encodeToString(liked)
            SongLikeState.refreshFromSettings()
            try {
                val library = library()
                val favourites = library.children(AutomotiveMediaIds.LIKED, 0, 20)
                val history = library.children(AutomotiveMediaIds.HISTORY, 0, 20)
                assertEquals("song", AutomotiveMediaIds.songId(history.first().mediaId))
                val selection = library.resolveSongQueue(favourites[1])!!
                val queue = MusicQueue().apply { setQueue(selection.songs, selection.startIndex) }

                assertEquals(liked.map { it.id }, selection.songs.map { it.id })
                assertEquals("song", queue.currentItem()?.id)
                assertEquals("next-favourite", queue.next()?.id)
            } finally {
                Settings.shared.likedSongsJson = "[]"
                SongLikeState.refreshFromSettings()
            }
        }

    @Test
    fun favouritesSelectionRebuildsInvalidatedQueueAfterLikesChange() =
        runBlocking {
            val liked = listOf("first", "selected", "last").map { SongItem(it, it, null, emptyList(), null) }
            Settings.shared.likedSongsJson = Json.encodeToString(liked)
            SongLikeState.refreshFromSettings()
            try {
                val library = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { true })
                val selection = library.children(AutomotiveMediaIds.LIKED, 0, 3)[2]
                val updated = listOf(SongItem("new", "New", null, emptyList(), null)) + liked
                Settings.shared.likedSongsJson = Json.encodeToString(updated)
                SongLikeState.refreshFromSettings()

                val resolved = library.resolveSongQueue(selection)!!
                val queue = MusicQueue().apply { setQueue(resolved.songs, resolved.startIndex) }
                assertEquals(updated.map { it.id }, resolved.songs.map { it.id })
                assertEquals("selected", queue.currentItem()?.id)
                assertEquals("last", queue.next()?.id)
            } finally {
                Settings.shared.likedSongsJson = "[]"
                SongLikeState.refreshFromSettings()
            }
        }

    @Test
    fun restoredBrowseSelectionReconstructsItsPlaylistOnlineAndOffline() =
        runBlocking {
            val liked = listOf("first", "selected", "last").map { SongItem(it, it, null, emptyList(), null) }
            Settings.shared.likedSongsJson = Json.encodeToString(liked)
            SongLikeState.refreshFromSettings()
            try {
                val selection = library().children(AutomotiveMediaIds.LIKED, 0, 3)[2]
                for (connected in listOf(true, false)) {
                    val restored = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { connected })
                    val resolved = restored.resolveSongQueue(selection)!!
                    val queue = MusicQueue().apply { setQueue(resolved.songs, resolved.startIndex) }
                    assertEquals(liked.map { it.id }, resolved.songs.map { it.id })
                    assertEquals("selected", queue.currentItem()?.id)
                    assertEquals("last", queue.next()?.id)
                }
            } finally {
                Settings.shared.likedSongsJson = "[]"
                SongLikeState.refreshFromSettings()
            }
        }

    @Test
    fun offlineLikedSelectionKeepsSongsBeyondTheBrowsePageAndSkipsOnlyMissingAudio() =
        runBlocking {
            val liked =
                listOf("first", "missing", "second", "third", "fourth", "fifth").map {
                    SongItem(it, it, null, emptyList(), null)
                }
            Settings.shared.likedSongsJson = Json.encodeToString(liked)
            SongLikeState.refreshFromSettings()
            try {
                val library = AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { false })
                val firstPage = library.children(AutomotiveMediaIds.LIKED, 0, 2)
                val secondPage = library.children(AutomotiveMediaIds.LIKED, 1, 2)
                library.children(AutomotiveMediaIds.DOWNLOADS, 0, 2)

                for ((item, expectedIndex) in listOf(firstPage[1] to 0, secondPage.first() to 1)) {
                    val context = library.resolveSongQueue(item)!!
                    val playable =
                        offlineAutomotiveQueue(AutomotiveSongResolution(context.songs, context.startIndex), false) {
                            it != "missing"
                        }!!
                    assertEquals(listOf("first", "second", "third", "fourth", "fifth"), playable.songs.map { it.id })
                    assertEquals(expectedIndex, playable.startIndex)
                }
            } finally {
                Settings.shared.likedSongsJson = "[]"
                SongLikeState.refreshFromSettings()
            }
        }

    @Test
    @OptIn(DelicateCoilApi::class)
    fun browseArtworkRefreshesWhenASizeVariantBecomesCached() =
        runBlocking {
            val directory = Files.createTempDirectory("automotive-artwork").toFile()
            val cache =
                DiskCache
                    .Builder()
                    .directory(directory.toOkioPath())
                    .maxSizeBytes(1024 * 1024)
                    .build()
            val loader = ImageLoader.Builder(context).diskCache(cache).build()
            SingletonImageLoader.setUnsafe(loader)
            try {
                val url = "https://lh3.googleusercontent.com/album=s1200"
                database.dao().upsert(AlbumEntity("album", title = "Album", songCount = 1, duration = 100, thumbnailUrl = url))
                val library = library()
                assertEquals(
                    "content",
                    library
                        .children(AutomotiveMediaIds.ALBUMS, 0, 10)
                        .single()
                        .mediaMetadata.artworkUri!!
                        .scheme,
                )
                cache.openEditor(bucketedArtworkUrl(url, 256))!!.apply {
                    data.toFile().writeBytes(byteArrayOf(1, 2, 3))
                    commit()
                }
                assertEquals(
                    "content",
                    library
                        .children(AutomotiveMediaIds.ALBUMS, 0, 10)
                        .single()
                        .mediaMetadata.artworkUri!!
                        .scheme,
                )
            } finally {
                SingletonImageLoader.reset()
                loader.shutdown()
                directory.deleteRecursively()
            }
        }

    @Test
    fun rootLimitsKeepEveryCollectionReachableAndReflectChangedPins() =
        runBlocking {
            val library = library()
            val all = library.rootChildren(browsableOnly = true, childLimit = Int.MAX_VALUE).map { it.mediaId }.toSet()
            for (limit in listOf(1, 2, 4, 20)) {
                val roots = library.rootChildren(browsableOnly = true, childLimit = limit)
                assertTrue(roots.size <= limit)
                assertTrue(roots.all { it.mediaMetadata.isBrowsable == true && it.mediaMetadata.isPlayable == false })
                val reachable =
                    roots
                        .flatMap {
                            if (it.mediaId == AutomotiveMediaIds.COLLECTIONS) library.children(it.mediaId, 0, Int.MAX_VALUE) else listOf(it)
                        }.map { it.mediaId }
                        .toSet()
                assertEquals(all, reachable)
            }
            Settings.shared.setAutomotivePinnedPlaylists(emptyList())
            assertFalse(library.rootChildren().any { it.mediaId == AutomotiveMediaIds.PINNED_PLAYLISTS })
            assertTrue(library.children(AutomotiveMediaIds.PINNED_PLAYLISTS, 0, 100).isEmpty())
        }

    @Test
    fun repeatedSongsAndOtherCollectionsKeepTheirOwnPlaybackPositions() =
        runBlocking {
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "episode", position = 1))
            database.dao().insert(PlaylistSongMap(playlistId = "local", songId = "song", position = 2))
            var connected = true
            val library = AutomotiveMediaLibrary(context, database, repository, { connected })
            val items = library.children(AutomotiveMediaIds.localPlaylist("local"), 0, 100).drop(1)
            assertEquals(3, items.map { it.mediaId }.distinct().size)
            library.children(AutomotiveMediaIds.SONGS, 0, 100)
            for (online in listOf(true, false)) {
                connected = online
                for (index in items.indices) {
                    val queue = library.resolveSongQueue(items[index])!!
                    assertEquals(listOf("song", "episode", "song"), queue.songs.map { it.id })
                    assertEquals(index, queue.startIndex)
                }
            }
        }

    @Test
    fun completedRemotePlaylistsRefreshOnReopenAndDiscardOtherAccountSnapshots() =
        runBlocking {
            client.close()
            var response = playlistResponse(listOf("first", "second"))
            var requests = 0
            client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            var connected = true
            val library = AutomotiveMediaLibrary(context, database, repository, { connected })
            val parent = AutomotiveMediaIds.youtubePlaylist("PLfresh")
            assertEquals(listOf("first", "second"), library.children(parent, 0, 100).mapNotNull { AutomotiveMediaIds.songId(it.mediaId) })
            response = playlistResponse(listOf("second", "new"))
            val refreshed = library.children(parent, 0, 100).drop(1)
            assertEquals(listOf("second", "new"), refreshed.map { AutomotiveMediaIds.songId(it.mediaId) })
            assertEquals(listOf("second", "new"), library.resolveSongQueue(refreshed.first())!!.songs.map { it.id })
            assertEquals(2, requests)
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "song", position = 0))
            response = playlistResponse(emptyList())
            assertTrue(library.children(AutomotiveMediaIds.youtubePlaylist("PLremote"), 0, 100).isEmpty())
            connected = false
            Settings.shared.dataSyncId = "another-account"
            assertTrue(library.children(parent, 0, 100).isEmpty())
            assertEquals(null, library.resolveSongQueue(refreshed.first()))
        }

    @Test
    fun pinnedContinuationSurvivesQueueApplicationAndAdditionsExpandEveryPage() =
        runBlocking {
            client.close()
            val initialIds = (0 until 50).map { "track-$it" }
            client =
                HttpClient(
                    MockEngine { request ->
                        val continuation =
                            (request.body as TextContent).text.contains("continuation")
                        val response =
                            if (continuation) {
                                playlistResponse(listOf("last-track"), continuationPage = true)
                            } else {
                                playlistResponse(initialIds, continuation = "next")
                            }
                        respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            Settings.shared.setAutomotivePinnedPlaylists(
                listOf(AutomotivePinnedPlaylist(AutomotivePlaylistSource.YOUTUBE, "PLremote", "Remote")),
            )
            val library = library()
            val item = MediaItem.Builder().setMediaId(AutomotiveMediaIds.pinnedYoutubePlaylist("PLremote")).build()
            val pinned = library.resolvePinnedPlaylist(item)!!
            assertEquals(initialIds, pinned.songs.map { it.id })
            assertTrue(pinned.playlistPage!!.hasMore)
            var installed: PlaylistPage? = null
            val gate =
                AutomotivePlaybackRequestGate { _, _, _, _, page, replacement ->
                    installed = page
                    replacement()
                }
            val generation = gate.start(false)
            val result = gate.resolve(generation, pinned.songs.map(library::playableItem), 0, 0, playlistPage = pinned.playlistPage)
            val application = gate.consume(result.mediaItems, 0, 0) as AutomotiveMediaItemsApplication.Apply
            gate.replaceQueue(application, pinned.songs, 0) {}
            assertEquals(pinned.playlistPage, installed)
            val completed = repository.continuePlaylistPage(installed!!, applyContentFilters = false)
            assertEquals(initialIds + "last-track", completed.songs.map { it.id })
            assertFalse(completed.hasMore)
            assertEquals(initialIds + "last-track", library.resolveAddition(item).map { it.id })
            Settings.shared.setAutomotivePinnedPlaylists(
                listOf(
                    AutomotivePinnedPlaylist(AutomotivePlaylistSource.LOCAL, "local", "Local"),
                    AutomotivePinnedPlaylist(AutomotivePlaylistSource.AUTO, "downloaded", "Downloaded"),
                ),
            )
            val localPin = MediaItem.Builder().setMediaId(AutomotiveMediaIds.pinnedLocalPlaylist("local")).build()
            assertEquals(listOf("song"), library.resolveAddition(localPin).map { it.id })
            val autoPin = MediaItem.Builder().setMediaId(AutomotiveMediaIds.pinnedAutoPlaylist("downloaded")).build()
            assertEquals(listOf(KIND_DOWNLOAD), library.resolveAddition(autoPin).map { it.id })
            assertEquals(
                listOf("song"),
                library.resolveAddition(library.playableItem(database.dao().getSongById("song")!!.toSongItem())).map { it.id },
            )
        }

    @Test
    fun voicePlaylistReturnsFirstPageAndPreservesOccurrences() =
        runBlocking {
            client.close()
            var requests = 0
            client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val continued = (request.body as TextContent).text.contains("continuation")
                        respond(
                            if (continued) {
                                playlistResponse(listOf("last"), continuationPage = true)
                            } else {
                                playlistResponse(listOf("same", "same"), continuation = "next")
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            val result = library().resolveVoiceSearch(VoiceSearchRequest("Remote", VoiceSearchFocus.PLAYLIST))
            assertEquals(1, requests)
            assertEquals(listOf("same", "same"), result.songs.map { it.id })
            assertTrue(result.playlistPage!!.hasMore)
            val completed = repository.continuePlaylistPage(result.playlistPage, applyContentFilters = false)
            assertEquals(listOf("same", "same", "last"), completed.songs.map { it.id })
        }

    @Test
    fun voicePlaylistSkipsUnavailableFirstTrackAndKeepsDuplicateOccurrences() = verifyVoicePlaylistUnavailableTracks(false)

    @Test
    fun voicePlaylistContinuesPastUnavailablePage() = verifyVoicePlaylistUnavailableTracks(true)

    private fun verifyVoicePlaylistUnavailableTracks(unavailablePage: Boolean) =
        runBlocking {
            Settings.shared.blockedSongIds = "blocked"
            client.close()
            var requests = 0
            client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val body = (request.body as TextContent).text
                        val continued = body.contains("continuation")
                        respond(
                            if (continued) {
                                if (body.contains("\"after\"")) {
                                    playlistResponse(listOf("last"), continuationPage = true, occurrencePrefix = "after")
                                } else {
                                    playlistResponse(listOf("same", "same"), continuation = "after", continuationPage = true)
                                }
                            } else {
                                playlistResponse(
                                    if (unavailablePage) listOf("unavailable") else listOf("unavailable", "blocked", "same", "same"),
                                    continuation = "next",
                                    unavailable = setOf("unavailable"),
                                )
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            val result = library().resolveVoiceSearch(VoiceSearchRequest("Remote", VoiceSearchFocus.PLAYLIST))
            assertEquals(if (unavailablePage) 2 else 1, requests)
            assertEquals(listOf("same", "same"), result.songs.map { it.id })
            assertTrue(result.playlistPage!!.hasMore)
            assertEquals(!unavailablePage, result.playlistPage.songs.any { it.isUnavailable })
            val completed = repository.continuePlaylistPage(result.playlistPage, applyContentFilters = false)
            val prefix = if (unavailablePage) emptyList() else listOf("unavailable", "blocked")
            val expected = if (unavailablePage) listOf("same", "same", "last") else prefix + listOf("same", "same", "same", "same")
            assertEquals(expected, completed.songs.map { it.id })
            assertEquals(!unavailablePage, completed.hasMore)
        }

    @Test
    fun remotePagesKeepAbsoluteOccurrenceIndicesAndRefreshEarlierQueueContexts() =
        runBlocking {
            client.close()
            client =
                HttpClient(
                    MockEngine { request ->
                        val continued = (request.body as TextContent).text.contains("continuation")
                        respond(
                            if (continued) {
                                playlistResponse(listOf("same", "last"), continuationPage = true)
                            } else {
                                playlistResponse(listOf("first", "same"), continuation = "next")
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            val library = library()
            val parent = AutomotiveMediaIds.youtubePlaylist("PLremote")
            val first = library.children(parent, 0, 3).drop(1)
            assertTrue(library.resolveSongQueue(first[1])!!.playlistPage!!.hasMore)
            val second = library.children(parent, 1, 3)
            assertEquals(listOf("same", "last"), second.map { AutomotiveMediaIds.songId(it.mediaId) })
            val queue = library.resolveSongQueue(second[0])!!
            assertEquals(2, queue.startIndex)
            assertEquals(listOf("first", "same", "same", "last"), queue.songs.map { it.id })
            assertEquals(1, library.resolveSongQueue(first[1])!!.startIndex)
            assertEquals(queue.songs, library.resolveSongQueue(first[1])!!.songs)
            assertFalse(queue.playlistPage!!.hasMore)

            val restored = library().resolveSongQueue(first[1])!!
            assertEquals(listOf("first", "same"), restored.songs.map { it.id })
            assertEquals(1, restored.startIndex)
            assertTrue(restored.playlistPage!!.hasMore)
        }

    @Test
    fun downloadStatusUsesTargetSongAndRequiresCompleteCollectionMembership() =
        runBlocking {
            val dao = database.dao()
            assertTrue(dao.isSongDownloaded(KIND_DOWNLOAD).first())
            assertFalse(dao.isSongDownloaded("song").first())
            dao.insert(SongEntity(KIND_DOWNLOAD, KIND_DOWNLOAD))
            dao.insert(PlaylistEntity("partial", "Partial", browseId = "PLpartial", remoteSongCount = 2))
            dao.insert(PlaylistSongMap(playlistId = "partial", songId = KIND_DOWNLOAD, position = 0))
            assertFalse(dao.isPlaylistDownloaded("PLpartial").first())
            assertEquals(listOf(KIND_DOWNLOAD), dao.playlistDownloadIds("PLpartial"))
            dao.insert(PlaylistEntity("complete", "Complete", browseId = "VLPLcomplete", remoteSongCount = 1))
            dao.insert(PlaylistSongMap(playlistId = "complete", songId = KIND_DOWNLOAD, position = 0))
            assertTrue(dao.isPlaylistDownloaded("PLcomplete").first())
            assertEquals(listOf(KIND_DOWNLOAD), dao.playlistDownloadIds("PLcomplete"))
            assertFalse(dao.isAlbumDownloaded("album").first())
            dao.insert(PlaylistSongMap(playlistId = "local", songId = KIND_DOWNLOAD, position = 1))
            assertFalse(dao.isPlaylistDownloaded("local").first())
        }

    @Test
    fun removingLoadedRemoteOccurrenceDoesNotFetchOrDeleteDifferentCachedOccurrence() =
        runBlocking {
            client.close()
            var edits = 0
            client =
                HttpClient(
                    MockEngine { request ->
                        check(request.url.encodedPath.endsWith("/browse/edit_playlist"))
                        edits++
                        respond("""{"status":"STATUS_SUCCEEDED"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository =
                YouTubeRepository(
                    InnerTube(client).apply {
                        cookie = "SAPISID=session"
                        visitorData = "visitor"
                        dataSyncId = "channel"
                        authUser = "0"
                        useLoginForBrowse = true
                    },
                )
            database.dao().insert(PlaylistSongMap(playlistId = "stored", songId = "song", position = 0, setVideoId = "cached"))
            val song =
                database
                    .dao()
                    .getSongById("song")!!
                    .toSongItem()
                    .copy(playlistSetVideoId = "loaded")
            removeSongsFromStoredPlaylist(database, repository, "stored", listOf(song)).getOrThrow()
            assertEquals(1, edits)
            assertEquals(
                "cached",
                database
                    .dao()
                    .playlistSongs("stored")
                    .first()
                    .single()
                    .map.setVideoId,
            )
            assertEquals(null, database.dao().findPlaylistEntity("stored")!!.remoteSongCount)
            removeSongsFromStoredPlaylist(database, repository, "local", listOf(song)).getOrThrow()
            assertTrue(
                database
                    .dao()
                    .playlistSongs("local")
                    .first()
                    .isEmpty(),
            )
            assertEquals(1, edits)
        }

    @Test
    fun canonicalAlbumMembershipIsBrowsableAndVoicePlayableWithoutAMap() =
        runBlocking {
            val date = LocalDateTime(2026, 1, 1, 0, 0)
            database.dao().upsert(AlbumEntity("canonical-album", title = "Canonical album", songCount = 1, duration = 1))
            database.dao().insert(SongEntity("canonical", "Canonical", albumId = "canonical-album", inLibrary = date))
            val library = AutomotiveMediaLibrary(context, database, repository, { false })
            assertEquals(
                listOf("canonical"),
                library.children(AutomotiveMediaIds.album("canonical-album"), 0, 20).mapNotNull {
                    AutomotiveMediaIds.songId(it.mediaId)
                },
            )
            assertEquals(
                listOf(
                    "canonical",
                ),
                library.resolveVoiceSearch(VoiceSearchRequest("Canonical album", VoiceSearchFocus.ALBUM)).songs.map {
                    it.id
                },
            )
        }

    @Test
    fun songPagesUseDisplayedTitlesAndRebuildTheFullFilteredQueueOnlyOnSelection() =
        runBlocking {
            val date = LocalDateTime(2026, 1, 1, 0, 0)
            database.dao().insert(SongEntity("renamed", "Zzz", customTitle = "Aaa", inLibrary = date))
            repeat(210) { index -> database.dao().insert(SongEntity("track-$index", "Track %03d".format(index), inLibrary = date)) }
            Settings.shared.blockedSongIds = "episode,track-0"
            val library = AutomotiveMediaLibrary(context, database, repository, { false })
            val first = library.children(AutomotiveMediaIds.SONGS, 0, 2)
            assertEquals(listOf("renamed", "song"), first.map { AutomotiveMediaIds.songId(it.mediaId) })
            val second = library.children(AutomotiveMediaIds.SONGS, 1, 2)
            assertEquals(listOf("track-1", "track-2"), second.map { AutomotiveMediaIds.songId(it.mediaId) })
            val selected = library.resolveSongQueue(second.last())!!
            assertEquals(3, selected.startIndex)
            assertEquals(211, selected.songs.size)
            assertEquals("track-209", selected.songs.last().id)
        }

    @Test
    fun serviceOnlyRefreshPopulatesEmptyLikesAndNotifiesContentChanges() =
        runBlocking {
            withTimeout(10_000) {
                Settings.shared.dataSyncId = "account-a"
                SongLikeState.activateCurrentAccount()
                val started = CompletableDeferred<Unit>()
                val response = CompletableDeferred<List<SongItem>>()
                val remote = SongItem("remote-like", "Remote like", null, emptyList(), null)
                var scans = 0
                val library =
                    AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { true }, refreshLikes = {
                        SongLikeState.refreshLikesFromRemote(force = false) {
                            scans++
                            started.complete(Unit)
                            response.await()
                        }
                    })
                val notifications = Channel<Int>(Channel.UNLIMITED)
                val observer = launch { library.observeLikedSongs { notifications.trySend(it).getOrThrow() } }
                try {
                    assertEquals(0, notifications.receive())
                    val refresh = async { library.refreshLikedSongs() }
                    started.await()
                    assertTrue(library.children(AutomotiveMediaIds.LIKED, 0, 20).isEmpty())
                    response.complete(listOf(remote))
                    refresh.await()
                    library.refreshLikedSongs()
                    assertEquals(1, scans)
                    assertEquals(2, notifications.receive())
                    val selection = library.children(AutomotiveMediaIds.LIKED, 0, 20).drop(1).single()
                    assertEquals(remote.id, AutomotiveMediaIds.songId(selection.mediaId))
                    assertEquals(listOf(remote), library.resolveSongQueue(selection)!!.songs)

                    val replacement = remote.copy(id = "replacement")
                    SongLikeState.replaceLikesLocally(listOf(replacement))
                    assertEquals(2, notifications.receive())
                    assertNull(library.resolveSongQueue(selection))
                    assertEquals(
                        replacement.id,
                        AutomotiveMediaIds.songId(
                            library
                                .children(AutomotiveMediaIds.LIKED, 0, 20)
                                .drop(1)
                                .single()
                                .mediaId,
                        ),
                    )
                    SongLikeState.replaceLikesLocally(emptyList())
                    assertEquals(0, notifications.receive())

                    Settings.shared.dataSyncId = ""
                    SongLikeState.activateCurrentAccount()
                    Snapshot.sendApplyNotifications()
                    assertEquals(2, notifications.receive())
                    assertEquals(
                        "song",
                        AutomotiveMediaIds.songId(
                            library
                                .children(AutomotiveMediaIds.LIKED, 0, 20)
                                .drop(1)
                                .single()
                                .mediaId,
                        ),
                    )
                } finally {
                    observer.cancelAndJoin()
                }
            }
        }

    @Test
    fun automotiveRefreshKeepsCachedLikesOnFailureAndSkipsOfflineOrSignedOutRequests() =
        runBlocking {
            Settings.shared.dataSyncId = "account-a"
            val cached = SongItem("cached-like", "Cached like", null, emptyList(), null)
            SongLikeState.replaceLikesLocally(listOf(cached))
            var connected = false
            var requests = 0
            val library =
                AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { connected }, refreshLikes = {
                    SongLikeState.refreshLikesFromRemote {
                        requests++
                        throw IOException("Offline")
                    }
                })
            library.refreshLikedSongs()
            assertEquals(0, requests)
            connected = true
            library.refreshLikedSongs()
            assertEquals(1, requests)
            assertEquals(
                cached.id,
                AutomotiveMediaIds.songId(
                    library
                        .children(AutomotiveMediaIds.LIKED, 0, 20)
                        .drop(1)
                        .single()
                        .mediaId,
                ),
            )

            Settings.shared.dataSyncId = ""
            SongLikeState.activateCurrentAccount()
            library.refreshLikedSongs()
            assertEquals(1, requests)
            assertEquals(
                "song",
                AutomotiveMediaIds.songId(
                    library
                        .children(AutomotiveMediaIds.LIKED, 0, 20)
                        .drop(1)
                        .single()
                        .mediaId,
                ),
            )
        }

    @Test
    fun automotiveRefreshDiscardsLikesFromAnAccountSwitchedDuringLoading() =
        runBlocking {
            withTimeout(10_000) {
                Settings.shared.dataSyncId = "account-a"
                SongLikeState.activateCurrentAccount()
                val started = CompletableDeferred<Unit>()
                val response = CompletableDeferred<List<SongItem>>()
                val library =
                    AutomotiveMediaLibrary(context, database, repository, isNetworkConnected = { true }, refreshLikes = {
                        SongLikeState.refreshLikesFromRemote {
                            started.complete(Unit)
                            response.await()
                        }
                    })
                val refresh = async { library.refreshLikedSongs() }
                started.await()
                Settings.shared.dataSyncId = "account-b"
                SongLikeState.activateCurrentAccount()
                response.complete(listOf(SongItem("account-a-song", "Song", null, emptyList(), null)))
                refresh.await()
                assertTrue(library.children(AutomotiveMediaIds.LIKED, 0, 20).isEmpty())
            }
        }

    @Test
    fun removedFavouriteSelectionCannotStartAnotherSong() =
        runBlocking {
            val liked = listOf("first", "removed", "last").map { SongItem(it, it, null, emptyList(), null) }
            Settings.shared.likedSongsJson = Json.encodeToString(liked)
            SongLikeState.refreshFromSettings()
            try {
                val library = library()
                val selection = library.children(AutomotiveMediaIds.LIKED, 0, 3)[2]
                Settings.shared.likedSongsJson = Json.encodeToString(liked.filterNot { it.id == "removed" })
                SongLikeState.refreshFromSettings()
                assertEquals(null, library.resolveSongQueue(selection))
            } finally {
                Settings.shared.likedSongsJson = "[]"
                SongLikeState.refreshFromSettings()
            }
        }

    @Test
    fun offlineVoiceMatchesPunctuationCollectionsArtistsAndAlbums() =
        runBlocking {
            database.dao().insert(PlaylistEntity("road", "Road Trip!", isLocal = true))
            database.dao().insert(PlaylistSongMap(playlistId = "road", songId = "song"))
            val library = AutomotiveMediaLibrary(context, database, repository, { false })
            for (request in listOf(
                VoiceSearchRequest("Road Trip", VoiceSearchFocus.PLAYLIST),
                VoiceSearchRequest("Artist"),
                VoiceSearchRequest("Album"),
            )) {
                assertEquals(listOf("song"), library.resolveVoiceSearch(request).songs.map { it.id })
            }
            val downloads = VoiceSearchRequest(context.getString(R.string.downloads), VoiceSearchFocus.PLAYLIST)
            assertEquals(listOf(KIND_DOWNLOAD), library.resolveVoiceSearch(downloads).songs.map { it.id })
            database.dao().insert(SongEntity("exact-title", "Artist", inLibrary = LocalDateTime(2026, 1, 1, 0, 0)))
            assertEquals(listOf("exact-title"), library.resolveVoiceSearch(VoiceSearchRequest("Artist")).songs.map { it.id })
        }

    @Test
    fun bookmarkedEntitiesAreListedWithoutIndividuallySavedTracks() =
        runBlocking {
            val date = LocalDateTime(2026, 1, 1, 0, 0)
            database.dao().upsert(AlbumEntity("bookmark", title = "Bookmark", songCount = 0, duration = 0, bookmarkedAt = date))
            database.dao().upsert(ArtistEntity("bookmark", "Bookmark", bookmarkedAt = date))
            val library = AutomotiveMediaLibrary(context, database, repository, { false })
            assertTrue(library.children(AutomotiveMediaIds.ALBUMS, 0, 20).any { it.mediaId == AutomotiveMediaIds.album("bookmark") })
            assertTrue(library.children(AutomotiveMediaIds.ARTISTS, 0, 20).any { it.mediaId == AutomotiveMediaIds.artist("bookmark") })
            assertEquals(
                1,
                database
                    .dao()
                    .albumsByNameAsc()
                    .first()
                    .size,
            )
            assertEquals(
                1,
                database
                    .dao()
                    .artistsByNameAsc()
                    .first()
                    .size,
            )
        }

    @Test
    fun blockedRemotePageLoadsTheNextPlayablePage() =
        runBlocking {
            client.close()
            var requests = 0
            client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val continued = (request.body as TextContent).text.contains("continuation")
                        respond(
                            if (continued) {
                                playlistResponse(listOf("playable"), continuationPage = true)
                            } else {
                                playlistResponse(listOf("blocked"), continuation = "next")
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client))
            Settings.shared.blockedSongIds = "blocked"
            val library = library()
            val page = library.children(AutomotiveMediaIds.youtubePlaylist("PLremote"), 1, 1)
            assertEquals(listOf("playable"), page.mapNotNull { AutomotiveMediaIds.songId(it.mediaId) })
            assertEquals(2, requests)
            assertEquals(listOf("playable"), library.resolveSongQueue(page.single())!!.songs.map { it.id })
        }

    @Test
    fun cachedSearchRechecksBlockingBeforeReturningResults() =
        runBlocking {
            val library = AutomotiveMediaLibrary(context, database, repository, { false })
            assertEquals(listOf("song"), library.searchSongs("Song").map { it.id })
            Settings.shared.blockedSongIds = "song"
            assertTrue(library.search("Song", 0, 20).isEmpty())
        }

    @Test
    fun searchRevalidatesOfflineAndFailedSnapshotsButKeepsSuccessfulEmptyResults() =
        runBlocking {
            var connected = false
            var fail = true
            var requests = 0
            client.close()
            client =
                HttpClient(
                    MockEngine {
                        requests++
                        if (fail) throw IOException("offline")
                        respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
            val library = AutomotiveMediaLibrary(context, database, repository, { connected }, { 1_000L })
            assertTrue(library.searchSongs("missing").isEmpty())
            assertEquals(0, requests)
            connected = true
            assertTrue(library.searchSongs(" MISSING ").isEmpty())
            assertTrue(requests > 0)
            val failedRequests = requests
            fail = false
            assertTrue(library.searchSongs("missing").isEmpty())
            assertTrue(requests > failedRequests)
            val successfulRequests = requests
            assertTrue(library.search("missing", 0, 20).isEmpty())
            assertEquals(successfulRequests, requests)
        }

    private fun playlistResponse(
        ids: List<String>,
        continuation: String? = null,
        continuationPage: Boolean = false,
        unavailable: Set<String> = emptySet(),
        occurrencePrefix: String = continuationPage.toString(),
    ): String {
        val items =
            ids
                .mapIndexed { index, id ->
                    val policy =
                        if (id in
                            unavailable
                        ) {
                            "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
                        } else {
                            "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_DEFAULT"
                        }
                    """{"musicResponsiveListItemRenderer":{"musicItemRendererDisplayPolicy":"$policy","flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}],"playlistItemData":{"videoId":"$id","playlistSetVideoId":"$occurrencePrefix-$index"}}}"""
                }.joinToString(",")
        val next = continuation?.let { """, "continuations":[{"nextContinuationData":{"continuation":"$it"}}]""" }.orEmpty()
        return if (continuationPage) {
            """{"continuationContents":{"musicPlaylistShelfContinuation":{"contents":[$items]$next}}}"""
        } else {
            """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$items]$next}}]}}}"""
        }
    }

    private val browseResponse =
        """
        {
          "contents":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Tracks"}]},"contents":[
              {"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Remote song"}]}}}],"playlistItemData":{"videoId":"remote-song"}}},
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Remote"}]},"navigationEndpoint":{"browseEndpoint":{"browseId":"VLPLremote","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}}}
            ]}}
          ]}}
        }
        """.trimIndent()
}

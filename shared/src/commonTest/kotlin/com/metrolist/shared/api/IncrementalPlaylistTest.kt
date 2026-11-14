package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistPresentationOptions
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.preparePlaylistPresentation
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.DownloadPlaylistFilter
import com.metrolist.shared.viewmodels.PlaylistPageLoader
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncrementalPlaylistTest {
    @Test
    fun searchAndPlaybackShareContinuationResultsWithoutDroppingDuplicateOccurrences() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            HttpClient(
                MockEngine { request ->
                    requests++
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val index = body["continuation"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
                    if (index == 1) {
                        started.complete(Unit)
                        release.await()
                    }
                    respond(page(index, 3, songsPerPage = 2), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json() } }.use { client ->
                val repository = YouTubeRepository(InnerTube(client))
                val initial = repository.getPlaylistPageData("PLsource", applyContentFilters = false)
                val search = async(start = CoroutineStart.UNDISPATCHED) { repository.continuePlaylistPage(initial, false) }
                started.await()
                val playback = async(start = CoroutineStart.UNDISPATCHED) { repository.continuePlaylistPage(initial, false) }
                val queue = MusicQueue().apply { setQueue(initial.songs, startIndex = 1) }
                assertEquals("occurrence-1", queue.currentSong()?.playlistSetVideoId)
                assertEquals(2, requests)
                release.complete(Unit)
                val searched = search.await()
                val played = playback.await()
                assertEquals(2, requests)
                val searchEnd = repository.continuePlaylistPage(searched, false)
                val playbackEnd = repository.continuePlaylistPage(played, false)
                assertEquals(3, requests)
                assertEquals((0..5).map { "occurrence-$it" }, playbackEnd.songs.map { it.playlistSetVideoId })
                assertEquals(searchEnd.songs, playbackEnd.songs)
                assertFalse(playbackEnd.hasMore)
            }
        }

    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun completePlaylistResultDistinguishesEmptyAndFailedFetches() =
        runBlocking {
            var status = HttpStatusCode.BadRequest
            val client =
                HttpClient(
                    MockEngine { respond(page(0, 1, songsPerPage = 0), status, headersOf(HttpHeaders.ContentType, "application/json")) },
                ) {
                    install(ContentNegotiation) { json() }
                }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                assertTrue(repository.getPlaylistResult("PLsource", allowOfflineFallback = false).isFailure)
                status = HttpStatusCode.OK
                val empty = repository.getPlaylistResult("PLsource", allowOfflineFallback = false)
                assertTrue(empty.isSuccess)
                assertTrue(empty.getOrThrow().playlistSongs().isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun missingSavedEpisodeTokenStopsAtTheFirstMatchingPage() =
        runBlocking {
            var browseRequests = 0
            var edits = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/browse/edit_playlist")) {
                            edits++
                            respond("""{"status":"STATUS_SUCCEEDED"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        } else {
                            browseRequests++
                            respond(page(0, 100), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" })
                repository.setEpisodeSaved("song", saved = false).getOrThrow()
                assertEquals(1, browseRequests)
                assertEquals(1, edits)
            } finally {
                client.close()
            }
        }

    @Test
    fun streamIsColdAndFullCollectionKeepsOccurrencesAcrossOneHundredPages() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val index = body["continuation"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
                        respond(page(index, 100), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val pages = repository.getPlaylistPages("PLsource", applyContentFilters = false)
                assertEquals(0, requests)
                val initial = pages.first()
                assertEquals(1, requests)
                assertEquals(1, initial.songs.size)
                val deltas = repository.playlistPages(initial, applyContentFilters = false).toList()
                assertEquals(100, requests)
                assertTrue(deltas.all { it.songs.size == 1 })
                assertFalse(deltas.last().hasMore)
                assertEquals(1, initial.songs.size, "The initial cursor is immutable")
                val complete = repository.completePlaylistPage(initial, applyContentFilters = false)
                assertEquals(100, complete.songs.size)
                assertEquals((0 until 100).map { "occurrence-$it" }, complete.songs.map { it.playlistSetVideoId })
                assertFalse(complete.hasMore)
                requests = 0
                assertTrue(repository.playlistContainsSong("PLsource", "song"))
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun failedContinuationCanRetryTheSameCursor() =
        runTest {
            var fail = true
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        if (body["continuation"]?.jsonPrimitive?.contentOrNull != null && fail) {
                            respond("{}", HttpStatusCode.BadRequest)
                        } else {
                            respond(
                                page(
                                    if (body["continuation"]?.jsonPrimitive?.contentOrNull ==
                                        null
                                    ) {
                                        0
                                    } else {
                                        1
                                    },
                                    2,
                                    songsPerPage = 100,
                                ),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val first = repository.getPlaylistPageData("PLsource", false)
                assertEquals(100, first.songs.size)
                assertFailsWith<InnerTubeHttpException> { repository.completePlaylistPage(first, false) }
                assertTrue(repository.getPlaylistResult("PLsource", allowOfflineFallback = false).isFailure)
                assertEquals(100, first.songs.size)
                assertFailsWith<InnerTubeHttpException> { repository.playlistSongIds("PLsource", allowOfflineFallback = false) }
                val downloads = first.songs.distinctBy { it.id } + SongItem("other", "Other", null, emptyList(), null)
                val filter =
                    DownloadPlaylistFilter(
                        this,
                        localMembership = { error("Remote playlists must load every page") },
                        remoteMembership = { repository.playlistSongIds(it, allowOfflineFallback = false, refresh = true) },
                    )
                filter.setExcluded(true)
                filter.select(PlaylistEntity(id = "remote", name = "Remote", browseId = "PLsource"))
                val failed = filter.state.first { !it.loading }
                assertTrue(failed.failed)
                assertEquals(downloads, failed.apply(downloads))
                filter.setExcluded(false)
                assertEquals(downloads, filter.state.value.apply(downloads))
                fail = false
                filter.refresh()
                val loaded = filter.state.first { !it.loading }
                assertFalse(loaded.failed)
                assertEquals(listOf("song"), loaded.apply(downloads).map { it.id })
                filter.setExcluded(true)
                assertEquals(
                    listOf("other"),
                    filter.state.value
                        .apply(downloads)
                        .map { it.id },
                )
                assertEquals(200, repository.completePlaylistPage(first, false).songs.size)
                assertEquals(setOf("song"), repository.playlistSongIds("PLsource", allowOfflineFallback = false))
            } finally {
                client.close()
            }
        }

    @Test
    fun playbackCompletesMoreThanOneHundredSongsBeforeSortingAndShuffling() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val index = body["continuation"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
                        respond(page(index, 2, songsPerPage = 100), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val first = repository.getPlaylistPlaybackPage("PLsource")
                assertEquals(100, first.songs.size)
                assertEquals(1, requests)

                val complete = repository.completePlaylistPage(first, applyContentFilters = false)
                val displayed =
                    preparePlaylistPresentation(
                        complete.songs,
                        PlaylistPresentationOptions(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING),
                    ).songs
                assertEquals(2, requests)
                assertEquals(200, displayed.size)
                assertEquals("occurrence-199", displayed.first().playlistSetVideoId)
                assertEquals("occurrence-0", displayed.last().playlistSetVideoId)

                val queue = MusicQueue()
                queue.setQueue(displayed, 150)
                val selected =
                    queue.state.value.entries[150]
                        .key
                queue.toggleShuffle()
                assertEquals(200, queue.items.value.size)
                assertEquals(
                    selected,
                    queue.state.value.entries[queue.currentIndex.value]
                        .key,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun cumulativeCollectorsRecheckPreviouslyLoadedRowsAgainstCurrentBlocks() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine { request ->
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        val index = body["continuation"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
                        respond(page(index, 2), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                val initial = repository.getPlaylistPageData("PLsource")
                BlockedContent.rememberDislikedSong(initial.songs.single())
                assertTrue(repository.completePlaylistPage(initial).songs.isEmpty())
                assertTrue(repository.continuePlaylistPage(initial).songs.isEmpty())
                val loader = PlaylistPageLoader(repository, "PLsource")
                loader.loadFirst()
                assertEquals(
                    1,
                    loader.page.value!!
                        .songs.size,
                )
                assertEquals(2, loader.complete().songs.size)
                assertEquals(1, initial.songs.size)
            } finally {
                BlockedContent.unblockSong("song")
                client.close()
            }
        }

    @Test
    fun podcastFirstPageDoesNotFetchSavedMembershipOrContinuations() =
        runBlocking {
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        respond(page(0, 100), headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val first = YouTubeRepository(InnerTube(client)).getPodcastPages("MPSPsource").first()
                assertEquals(1, requests)
                assertEquals(listOf("song"), first.episodes.map { it.id })
                assertEquals("1", first.continuation)
            } finally {
                client.close()
            }
        }

    @Test
    fun thumbnailFetchPropagatesCancellation() =
        runBlocking<Unit> {
            val client = HttpClient(MockEngine { throw CancellationException("cancelled") }) { install(ContentNegotiation) { json() } }
            try {
                assertFailsWith<CancellationException> { YouTubeRepository(InnerTube(client)).fetchArtistThumbnail("artist") }
            } finally {
                client.close()
            }
        }

    private fun page(
        index: Int,
        count: Int,
        songsPerPage: Int = 1,
    ): String {
        val contents =
            (0 until songsPerPage).joinToString(",") { offset ->
                val occurrence = index * songsPerPage + offset
                """{"musicResponsiveListItemRenderer":{
                  "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song"}]}}}],
                  "playlistItemData":{"videoId":"song","playlistSetVideoId":"occurrence-$occurrence"}
                }}"""
            }
        val shelf = """{
          "contents":[$contents]
          ${if (index + 1 < count) ",\"continuations\":[{\"nextContinuationData\":{\"continuation\":\"${index + 1}\"}}]" else ""}
        }"""
        return if (index == 0) {
            """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":$shelf}]}}}"""
        } else {
            """{"continuationContents":{"musicPlaylistShelfContinuation":$shelf}}"""
        }
    }
}

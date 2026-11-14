package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.settings.Settings
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistMembershipTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun loadedLargePlaylistAddsUseOneEditEachAndKeepDuplicateConfirmation() =
        withFixture {
            val page = repository.completePlaylistPage(repository.getPlaylistPageData("VLPLlarge", false), false)
            assertEquals(1_400, page.songs.size)
            assertEquals(
                1_400,
                page.songs
                    .map { it.playlistSetVideoId }
                    .toSet()
                    .size,
            )
            assertEquals(14, browses)
            repository.addToPlaylist("PLlarge", "first", allowDuplicate = false).getOrThrow()
            repository.addToPlaylist("VLPLlarge", "second", allowDuplicate = false).getOrThrow()
            assertEquals(14, browses)
            assertEquals(2, edits)
            for (id in listOf("song-1399", "first", "second")) {
                assertTrue(
                    repository
                        .addToPlaylist(
                            "PLlarge",
                            id,
                            allowDuplicate = false,
                        ).exceptionOrNull() is PlaylistSongAlreadyPresentException,
                )
            }
            assertEquals(2, edits)
            repository.addToPlaylist("PLlarge", "first", allowDuplicate = true).getOrThrow()
            assertEquals(3, edits)
            assertEquals(14, browses)
            assertEquals(1_402, repository.playlistSongIds("PLlarge").size)
        }

    @Test
    fun chooserScanAndAdditionSharePaginationAndReuseItForTheNextSong() =
        withFixture {
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                beforeBrowse = {
                    started.complete(Unit)
                    release.await()
                }
                val chooser = async { repository.playlistContainsSong("PLlarge", "first") }
                started.await()
                val add = async { repository.addToPlaylist("PLlarge", "first", allowDuplicate = false) }
                release.complete(Unit)
                assertFalse(chooser.await())
                add.await().getOrThrow()
            }
            repository.addToPlaylist("PLlarge", "second", allowDuplicate = false).getOrThrow()
            assertEquals(14, browses)
            assertEquals(2, edits)
        }

    @Test
    fun partialMembershipDoesNotHideSongsOnLaterPagesAndExpiredOrSwitchedSessionsRefetch() =
        withFixture {
            assertTrue(repository.playlistContainsSong("PLlarge", "song-0"))
            assertEquals(1, browses)
            assertTrue(repository.playlistContainsSong("PLlarge", "song-1399"))
            assertEquals(15, browses)
            now += 5 * 60_000L
            assertTrue(repository.playlistContainsSong("PLlarge", "song-0"))
            assertEquals(16, browses)
            tube.cookie = "SAPISID=another-account"
            assertTrue(repository.playlistContainsSong("PLlarge", "song-0"))
            assertEquals(17, browses)
        }

    @Test
    fun acknowledgedAddsDoNotExtendTheMembershipExpiry() =
        withFixture {
            repository.playlistSongIds("PLlarge")
            now += 5 * 60_000L - 1
            repository.addToPlaylist("PLlarge", "first", allowDuplicate = false).getOrThrow()
            assertEquals(14, browses)
            now++
            assertTrue(repository.playlistContainsSong("PLlarge", "song-0"))
            assertEquals(15, browses)
        }

    @Test
    fun failedEditsAndRemovalsInvalidateMembershipBeforeRetrying() =
        withFixture {
            repository.playlistSongIds("PLlarge")
            editStatus = "STATUS_FAILED"
            assertTrue(repository.addToPlaylist("PLlarge", "first", allowDuplicate = false).isFailure)
            assertEquals(14, browses)
            editStatus = "STATUS_SUCCEEDED"
            repository.addToPlaylist("PLlarge", "first", allowDuplicate = false).getOrThrow()
            assertEquals(28, browses)
            repository.removePlaylistSong("PLlarge", "occurrence-0", "song-0").getOrThrow()
            assertFalse(repository.playlistContainsSong("PLlarge", "song-0"))
            assertEquals(42, browses)
        }

    @Test
    fun copyingAPlaylistInvalidatesMembershipBeforeDuplicateDetection() =
        withFixture {
            repository.playlistSongIds("PLlarge")
            repository.addPlaylistToPlaylist("PLlarge", "PLsource").getOrThrow()
            assertTrue(
                repository
                    .addToPlaylist(
                        "PLlarge",
                        "copied",
                        allowDuplicate = false,
                    ).exceptionOrNull() is PlaylistSongAlreadyPresentException,
            )
            assertEquals(29, browses)
            assertEquals(1, edits)
        }

    @Test
    fun aScanStartedBeforeAnEditCannotReplaceAcknowledgedMembership() =
        withFixture {
            coroutineScope {
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                beforeBrowse = {
                    if (browses == 14) {
                        started.complete(Unit)
                        release.await()
                    }
                }
                val scan = async { repository.playlistContainsSong("PLlarge", "first") }
                started.await()
                repository.addToPlaylist("PLlarge", "first", allowDuplicate = true).getOrThrow()
                release.complete(Unit)
                assertTrue(scan.await())
            }
            assertTrue(
                repository
                    .addToPlaylist(
                        "PLlarge",
                        "first",
                        allowDuplicate = false,
                    ).exceptionOrNull() is PlaylistSongAlreadyPresentException,
            )
            assertEquals(1, edits)
            assertEquals(14, browses)
        }

    @Test
    fun failedAndCancelledScansDoNotCacheAbsenceOrPermitAnEdit() =
        withFixture {
            browseStatus = HttpStatusCode.Forbidden
            assertTrue(repository.addToPlaylist("PLlarge", "first", allowDuplicate = false).isFailure)
            assertEquals(0, edits)
            browseStatus = HttpStatusCode.OK
            beforeBrowse = { throw CancellationException("cancel scan") }
            assertFailsWith<CancellationException> { repository.playlistContainsSong("PLlarge", "first") }
            beforeBrowse = {}
            repository.addToPlaylist("PLlarge", "first", allowDuplicate = false).getOrThrow()
            assertEquals(16, browses)
            assertEquals(1, edits)
        }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.block()
            } finally {
                fixture.tube.close()
                fixture.client.close()
            }
        }

    private class Fixture {
        var now = 1_000L
        var browses = 0
        var edits = 0
        var editStatus = "STATUS_SUCCEEDED"
        var browseStatus = HttpStatusCode.OK
        var beforeBrowse: suspend () -> Unit = {}
        private val songs = (0 until 1_400).map { "song-$it" }.toMutableList()
        val client =
            HttpClient(
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    if (request.url.encodedPath.endsWith("/browse/edit_playlist")) {
                        edits++
                        val action = body["actions"]!!.jsonArray.single().jsonObject
                        if (editStatus == "STATUS_SUCCEEDED") {
                            when (action["action"]!!.jsonPrimitive.content) {
                                "ACTION_ADD_VIDEO" -> songs += action["addedVideoId"]!!.jsonPrimitive.content
                                "ACTION_REMOVE_VIDEO" -> songs.remove(action["removedVideoId"]!!.jsonPrimitive.content)
                                "ACTION_ADD_PLAYLIST" -> songs += "copied"
                                else -> error("Unexpected playlist edit")
                            }
                        }
                        respond("""{"status":"$editStatus"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        assertEquals("/youtubei/v1/browse", request.url.encodedPath)
                        browses++
                        val index = body["continuation"]?.jsonPrimitive?.contentOrNull?.toInt() ?: 0
                        val response = page(index)
                        beforeBrowse()
                        respond(response, browseStatus, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ) { install(ContentNegotiation) { json() } }
        val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=account" }
        val repository = YouTubeRepository(tube, nowMs = { now })

        private fun page(index: Int): String {
            val rows =
                songs
                    .drop(index * 100)
                    .take(100)
                    .mapIndexed { offset, id ->
                        """{"musicResponsiveListItemRenderer":{
                      "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}],
                      "playlistItemData":{"videoId":"$id","playlistSetVideoId":"occurrence-${index * 100 + offset}"}
                    }}"""
                    }.joinToString(",")
            val continuation =
                if ((index + 1) * 100 < songs.size) {
                    ""","continuations":[{"nextContinuationData":{"continuation":"${index + 1}"}}]"""
                } else {
                    ""
                }
            val shelf = """{"contents":[$rows]$continuation}"""
            return if (index == 0) {
                """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":$shelf}]}}}"""
            } else {
                """{"continuationContents":{"musicPlaylistShelfContinuation":$shelf}}"""
            }
        }
    }
}

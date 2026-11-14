package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals

class PlaylistCopyImportTest {
    @Test
    fun completeSourceUsesOneServerCopy() = verifyCopy("STATUS_SUCCEEDED", copyApplied = true, expectedCopies = 1, expectedAdds = 0)

    @Test
    fun rejectedCopyFallsBackToSongs() = verifyCopy("STATUS_FAILED", copyApplied = false, expectedCopies = 1, expectedAdds = 2)

    @Test
    fun uncertainCopyReconcilesBeforeFallback() = verifyCopy("STATUS_FAILED", copyApplied = true, expectedCopies = 1, expectedAdds = 0)

    @Test
    fun partialSourceSelectionUsesSongs() =
        verifyCopy("STATUS_SUCCEEDED", copyApplied = false, selectedCount = 1, expectedCopies = 0, expectedAdds = 1)

    @Test
    fun collectionsWithoutRemoteSourceUseSongs() =
        verifyCopy("STATUS_SUCCEEDED", copyApplied = false, sourceId = null, expectedCopies = 0, expectedAdds = 2)

    @Test
    fun duplicateSourceOccurrencesUseSongs() =
        verifyCopy(
            "STATUS_SUCCEEDED",
            copyApplied = false,
            sourceSongs = listOf("song-1", "song-2", "song-1"),
            expectedCopies = 0,
            expectedAdds = 2,
        )

    @Test
    fun existingDestinationOverlapUsesSongs() =
        verifyCopy(
            "STATUS_SUCCEEDED",
            copyApplied = false,
            existingSongs = listOf("song-1"),
            expectedCopies = 0,
            expectedAdds = 1,
        )

    private fun verifyCopy(
        copyStatus: String,
        copyApplied: Boolean,
        selectedCount: Int = 2,
        sourceId: String? = "PLsource",
        sourceSongs: List<String> = listOf("song-1", "song-2"),
        existingSongs: List<String> = emptyList(),
        expectedCopies: Int,
        expectedAdds: Int,
    ) = runBlocking {
        val path = Files.createTempFile("playlist-copy", ".db")
        Files.deleteIfExists(path)
        val database = getDatabaseBuilder(path.toFile()).build()
        val destinationSongs = existingSongs.toMutableList()
        var copies = 0
        var adds = 0
        var destinationReads = 0
        val client =
            HttpClient(
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val actions = body["actions"]?.jsonArray
                    val response =
                        if (actions != null) {
                            val action = actions.single().jsonObject
                            when (action["action"]?.jsonPrimitive?.content) {
                                "ACTION_ADD_PLAYLIST" -> {
                                    copies++
                                    if (copyApplied) destinationSongs.addAll(listOf("song-1", "song-2"))
                                    """{"status":"$copyStatus"}"""
                                }

                                "ACTION_ADD_VIDEO" -> {
                                    adds++
                                    destinationSongs.add(requireNotNull(action["addedVideoId"]).jsonPrimitive.content)
                                    """{"status":"STATUS_SUCCEEDED"}"""
                                }

                                else -> {
                                    error("Unexpected action: $action")
                                }
                            }
                        } else if (body["browseId"]?.jsonPrimitive?.content == "VLPLsource") {
                            playlist(sourceSongs)
                        } else {
                            destinationReads++
                            playlist(destinationSongs)
                        }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                },
            ) { install(ContentNegotiation) { json() } }
        val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            val manager = PlaylistImportManager(database, YouTubeRepository(tube))
            val destination = PlaylistEntity(id = "local-cache", name = "Destination", browseId = "PLdestination", isEditable = true)
            val songs = (1..selectedCount).map { SongItem("song-$it", "Song $it", null, emptyList(), null) }
            val result =
                manager
                    .importVerbose(
                        "Copy",
                        songs,
                        PlaylistImportManager.Destination.EXISTING(destination),
                        sourceYouTubePlaylistId = sourceId,
                    ).getOrThrow()
            assertEquals(selectedCount, result.importedSongCount)
            assertEquals(expectedCopies, copies)
            assertEquals(expectedAdds, adds)
            assertEquals(songs.map { it.id }, destinationSongs)
            assertEquals(if (copyStatus == "STATUS_FAILED" && copies > 0) 2 else 1, destinationReads)
            assertEquals(selectedCount, database.dao().playlistSongMaps(destination.id, 0).size)
        } finally {
            Settings.replaceSharedForTest(null)
            tube.close()
            client.close()
            database.close()
            Files.deleteIfExists(path)
        }
    }

    private fun playlist(ids: List<String>): String {
        val rows =
            ids.joinToString(",") { id ->
                """{"musicResponsiveListItemRenderer":{
              "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$id"}]}}}],
              "playlistItemData":{"videoId":"$id"}
            }}"""
            }
        return """{"contents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[$rows]}}]}}}"""
    }
}

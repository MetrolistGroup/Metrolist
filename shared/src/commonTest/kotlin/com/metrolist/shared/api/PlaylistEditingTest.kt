package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.isEditablePlaylistId
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistEditingTest {
    @Test
    fun editabilityRequiresOwnershipAndAnEditableCollectionId() {
        for (id in listOf(
            "",
            "VL",
            "MPREb_album",
            "OLAK5uy_album",
            "VLOLAK5uy_album",
            "LM",
            "VLLM",
            "LM:account",
            "SE",
            "RDmix",
            "VLRDCLcurated",
            "MPSPpodcast",
            "FEmusic_library",
            "LP_LIKED",
        )) {
            assertFalse(id.isEditablePlaylistId(), id)
            assertFalse(PlaylistItem(id, "Collection", null, isEditable = true).canEdit, id)
        }
        for (id in listOf("PLowned", "VLPLowned")) {
            assertTrue(id.isEditablePlaylistId())
            assertFalse(PlaylistItem(id, "Read only", null).canEdit)
            assertTrue(PlaylistItem(id, "Owned", null, isEditable = true).canEdit)
        }
        assertTrue(PlaylistItem("LPlocal", "Local", null, isEditable = true, isLocal = true).canEdit)
    }

    @Test
    fun editsNormalizeBrowsePrefixesAndRejectAlbumOrSystemDestinations() =
        runBlocking {
            val engine =
                MockEngine {
                    respond("""{"status":"STATUS_SUCCEEDED"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            try {
                val edits = YouTubePlaylistEdits(tube, {}, { _, _ -> false })
                assertTrue(edits.removePlaylistSongs("VLPLowned", listOf("occurrence" to "song")).isSuccess)
                val body = Json.parseToJsonElement((engine.requestHistory.single().body as TextContent).text).jsonObject
                assertEquals("PLowned", body["playlistId"]?.jsonPrimitive?.content)
                for (id in listOf("MPREb_album", "VLOLAK5uy_album", "VLLM", "RDmix")) {
                    assertTrue(edits.removePlaylistSongs(id, listOf("occurrence" to "song")).isFailure)
                    assertTrue(edits.addToPlaylist(id, "song").isFailure)
                    assertTrue(edits.movePlaylistSong(id, "occurrence", null).isFailure)
                }
                assertEquals(1, engine.requestHistory.size)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun copyOrCover404CannotConfirmThatTheDestinationPlaylistIsGone() =
        runBlocking {
            var missing = false
            val client =
                HttpClient(
                    MockEngine {
                        respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            try {
                val edits = YouTubePlaylistEdits(tube, {}, { _, _ -> false }, { missing = true })
                assertTrue(edits.addPlaylistToPlaylist("PLdestination", "PLsource").isFailure)
                assertTrue(edits.removePlaylistThumbnail("PLdestination").isFailure)
                assertFalse(missing)
            } finally {
                tube.close()
                client.close()
            }
        }

    @Test
    fun accountSwitchDuringMissingEditCannotReconcileTheNewAccount() =
        runBlocking {
            var missing = false
            lateinit var tube: InnerTube
            val client =
                HttpClient(
                    MockEngine {
                        tube.cookie = "SAPISID=other"
                        respond("{}", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                ) { install(ContentNegotiation) { json() } }
            tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
            try {
                val edits = YouTubePlaylistEdits(tube, {}, { _, _ -> false }, { missing = true })
                assertFailsWith<CancellationException> { edits.removePlaylistSongs("PLowned", listOf("set" to "song")) }
                assertFalse(missing)
            } finally {
                tube.close()
                client.close()
            }
        }
}

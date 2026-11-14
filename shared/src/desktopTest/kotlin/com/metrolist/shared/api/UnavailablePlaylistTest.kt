package com.metrolist.shared.api

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.storage.IosDownloadQueue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnavailablePlaylistTest {
    @Test
    fun unavailableRowsAlsoSurviveExplicitNullMetadata() {
        val captured = fixtureRows("unavailable-playlist.json").first().getValue("musicResponsiveListItemRenderer").jsonObject
        val renderer =
            captured.toMutableMap().apply {
                listOf("thumbnail", "flexColumns", "fixedColumns", "overlay", "navigationEndpoint", "onTap").forEach { put(it, JsonNull) }
            }
        val song =
            assertNotNull(
                YouTubeItemParser.parseItem(JsonObject(mapOf("musicResponsiveListItemRenderer" to JsonObject(renderer)))) as? SongItem,
            )
        assertTrue(song.isUnavailable)
        assertEquals("cJaGsk3e5ME", song.id)
        assertEquals("6128676B35F5529F", song.playlistSetVideoId)
        assertTrue(song.artists.isEmpty())
        assertNull(song.thumbnail)
        assertNull(song.duration)
    }

    @Test
    fun greyedRowsWithoutATitleStayUnavailableDespiteOtherMetadata() {
        val captured = fixtureRows("unavailable-playlist.json").first().getValue("musicResponsiveListItemRenderer").jsonObject
        val renderer = JsonObject(captured.toMutableMap().apply { remove("flexColumns") })
        val song = assertNotNull(YouTubeItemParser.parseItem(JsonObject(mapOf("musicResponsiveListItemRenderer" to renderer))) as? SongItem)
        assertTrue(song.isUnavailable)
        assertNotNull(song.duration)
        assertNotNull(song.thumbnail)
    }

    @Test
    fun capturedGreyedRowsRemainPlayableAndKeepOccurrenceIds() {
        for (fixture in listOf("unavailable-playlist.json", "unavailable-playlist-continuation.json")) {
            val rows = fixtureRows(fixture)
            val songs = rows.map { assertNotNull(YouTubeItemParser.parseItem(it) as? SongItem) }
            assertEquals(2, songs.size)
            assertTrue(songs.none { it.isUnavailable })
            assertNotNull(songs.first().duration)
            val queue = MusicQueue().apply { setQueue(songs) }
            assertEquals(songs, queue.items.value)
            queue.toggleShuffle()
            assertEquals(songs.toSet(), queue.items.value.toSet())
            val downloads = IosDownloadQueue("")
            assertEquals(songs.size, downloads.enqueue(songs, emptySet(), emptyMap(), false, 1L))
            songs.zip(rows).forEach { (song, row) ->
                val identity =
                    row
                        .getValue("musicResponsiveListItemRenderer")
                        .jsonObject
                        .getValue("playlistItemData")
                        .jsonObject
                assertEquals(identity.getValue("videoId"), JsonPrimitive(song.id))
                assertEquals(identity.getValue("playlistSetVideoId"), JsonPrimitive(song.playlistSetVideoId))
            }
        }
    }

    @Test
    fun unavailableOwnerRowsSurviveMissingMetadataAndPlaceholderTitles() {
        val captured = fixtureRows("unavailable-playlist.json").first().getValue("musicResponsiveListItemRenderer").jsonObject
        for (title in listOf("Private video", "Deleted video", "Vidéo privée", null)) {
            val renderer =
                captured.toMutableMap().apply {
                    remove("thumbnail")
                    remove("fixedColumns")
                    remove("overlay")
                    remove("flexColumns")
                    if (title != null) {
                        put(
                            "flexColumns",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "musicResponsiveListItemFlexColumnRenderer",
                                            buildJsonObject {
                                                put("text", buildJsonObject { put("simpleText", title) })
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
            val song =
                assertNotNull(
                    YouTubeItemParser.parseItem(JsonObject(mapOf("musicResponsiveListItemRenderer" to JsonObject(renderer)))) as? SongItem,
                )
            assertTrue(song.isUnavailable)
            assertEquals(title.orEmpty(), song.title)
            assertTrue(song.artists.isEmpty())
            assertNull(song.thumbnail)
            assertNull(song.duration)
            assertNotNull(song.playlistSetVideoId)
        }
    }

    @Test
    fun greyedPlaceholdersStayUnavailableWithArtworkAndMenuRadioEndpoints() {
        val captured = fixtureRows("unavailable-playlist.json").first().getValue("musicResponsiveListItemRenderer").jsonObject
        val renderer =
            captured.toMutableMap().apply {
                remove("fixedColumns")
                put(
                    "menu",
                    buildJsonObject {
                        put("navigationEndpoint", buildJsonObject { put("watchEndpoint", buildJsonObject { put("videoId", "radio") }) })
                    },
                )
            }
        val song =
            assertNotNull(
                YouTubeItemParser.parseItem(JsonObject(mapOf("musicResponsiveListItemRenderer" to JsonObject(renderer)))) as? SongItem,
            )
        assertTrue(song.isUnavailable)
        assertNotNull(song.thumbnail)
        assertEquals("6128676B35F5529F", song.playlistSetVideoId)
        val queue = MusicQueue().apply { setQueue(listOf(song)) }
        assertTrue(queue.items.value.isEmpty())
    }

    private fun fixtureRows(name: String): List<JsonObject> {
        val text = checkNotNull(javaClass.getResourceAsStream("/youtube/$name")).bufferedReader().use { it.readText() }
        val fixture = decodeYouTubeJson(text)
        return fixture
            .getValue("contents")
            .jsonArray
            .single()
            .jsonObject
            .getValue("musicPlaylistShelfRenderer")
            .jsonObject
            .getValue("contents")
            .jsonArray
            .map { it.jsonObject }
    }
}

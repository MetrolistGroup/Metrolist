package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isVideoShelf
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtistDetailsTest {
    @BeforeTest
    fun setUp() = Settings.replaceSharedForTest(Settings(MapSettings()))

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(null)

    @Test
    fun artistPortraitNeverComesFromRelatedArtists() =
        runBlocking {
            for (hasPortrait in listOf(true, false)) {
                val portrait =
                    if (hasPortrait) {
                        """
                        , "thumbnail":{
                          "musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/50-cent.jpg"}]}}
                        }
                        """.trimIndent()
                    } else {
                        ""
                    }
                val body = """{
                "header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"50 Cent"}]}$portrait}},
                "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{
                  "musicCarouselShelfRenderer":{
                    "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Related artists"}]}}},
                    "contents":[{"musicTwoRowItemRenderer":{
                      "title":{"runs":[{"text":"Eminem"}]},
                      "navigationEndpoint":{"browseEndpoint":{"browseId":"UCeminem"}},
                      "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.com/eminem.jpg"}]}}}
                    }}]
                  }
                }]}}}}]}}
            }"""
                val parsed = YouTubeArtistParser.parseArtist(repositoryJson.decodeFromString<BrowseResponse>(body), "UC50cent")
                val parsedHeader = assertIs<ArtistItem>(parsed.sections["header"]?.single())
                assertEquals(if (hasPortrait) "https://example.com/50-cent.jpg" else null, parsedHeader.thumbnail)
                assertEquals("Eminem", assertIs<ArtistItem>(parsed.sections["Related artists"]?.single()).title)
                var requests = 0
                val client =
                    client { path ->
                        requests++
                        if (path.endsWith("/browse")) body else """{"results":[]}"""
                    }
                try {
                    val artist =
                        assertIs<ArtistItem>(YouTubeRepository(InnerTube(client)).getArtist("UC50cent").sections["header"]?.single())
                    assertEquals("50 Cent", artist.title)
                    assertEquals(if (hasPortrait) "https://example.com/50-cent.jpg" else null, artist.thumbnail)
                    assertEquals(1, requests, "Do not guess artist identity from unrelated search results")
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun untitledArtistPromoShelvesAreDropped() {
        val video = """{"musicTwoRowItemRenderer":{
            "title":{"runs":[{"text":"Watch the new music video"}]},
            "navigationEndpoint":{"watchEndpoint":{"videoId":"promo","watchEndpointMusicSupportedConfigs":
              {"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_OMV"}}}}
          }}"""
        val body = """{
            "header":{"musicImmersiveHeaderRenderer":{"title":{"runs":[{"text":"Band"}]}}},
            "contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicCarouselShelfRenderer":{"contents":[$video]}},
              {"musicCarouselShelfRenderer":{
                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Videos"}]}}},
                "contents":[$video]
              }}
            ]}}}}]}}
        }"""
        val parsed = YouTubeArtistParser.parseArtist(repositoryJson.decodeFromString<BrowseResponse>(body), "UCband")
        assertEquals(setOf("header", "Videos"), parsed.sections.keys)
    }

    @Test
    fun collectionCountsDoNotContainTypeOwnerOrYear() {
        fun parse(
            id: String,
            subtitle: String,
        ) = YouTubeItemParser.parseItem(
            Json
                .parseToJsonElement(
                    """{
            "musicTwoRowItemRenderer":{
              "title":{"runs":[{"text":"Collection"}]},
              "navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}},
              "subtitle":{"runs":$subtitle}
            }
        }""",
                ).jsonObject,
        )
        val playlist =
            assertIs<PlaylistItem>(
                parse(
                    "PLlist",
                    """[
          {"text":"Playlist"},{"text":" • "},
          {"text":"50 Cent","navigationEndpoint":{"browseEndpoint":{"browseId":"UC50cent"}}},
          {"text":" • "},{"text":"23 tracks"}
        ]""",
                ),
            )
        assertEquals("23 tracks", playlist.songCount)
        assertEquals("50 Cent", playlist.author)
        val noCount =
            assertIs<PlaylistItem>(
                parse(
                    "PLlist",
                    """[
          {"text":"Playlist"},{"text":" • "},
          {"text":"50 Cent","navigationEndpoint":{"browseEndpoint":{"browseId":"UC50cent"}}}
        ]""",
                ),
            )
        assertNull(noCount.songCount)
        val album =
            assertIs<AlbumItem>(
                parse(
                    "MPREb_album",
                    """[
          {"text":"EP"},{"text":" • "},{"text":"2025"},{"text":" • "},{"text":"6 titres"}
        ]""",
                ),
            )
        assertEquals("EP", album.type)
        assertEquals("2025", album.year)
        assertEquals(6, album.songCount)
        assertNull(assertIs<AlbumItem>(parse("MPREb_album", """[{"text":"2025"}]""")).songCount)
    }

    @Test
    fun onlyThePrimaryAudioShelfIsTopTracksEvenWhenVideosUsePlaylistIds() {
        val song = SongItem("audio", "Track", null, emptyList(), null)
        val video = song.copy(id = "video", musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        val page =
            ArtistPageResult(
                sections =
                    linkedMapOf(
                        "Vídeos" to listOf(video),
                        "Canciones" to listOf(song),
                        "Clips" to listOf(song.copy(id = "untyped-video")),
                    ),
                sectionBrowseIds =
                    mapOf(
                        "Vídeos" to ("VLOLvideos" to null),
                        "Canciones" to ("VLOLtracks" to null),
                        "Clips" to ("VLOLclips" to null),
                    ),
            )
        assertEquals("Canciones", page.topTracksSection)
        assertNull(page.copy(sections = mapOf("Vídeos" to listOf(video))).topTracksSection)
        assertNull(page.copy(sectionBrowseIds = emptyMap()).topTracksSection)
    }

    @Test
    fun artistShelfKindsUseMetadataInsteadOfTranslatedTitles() {
        val track = SongItem("audio", "Track", null, emptyList(), null)
        val video = track.copy(id = "video", musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        val live = video.copy(id = "live", musicVideoType = "MUSIC_VIDEO_TYPE_LIVE")
        val page =
            ArtistPageResult(
                sections =
                    linkedMapOf(
                        "Canciones" to listOf(track),
                        "De tu biblioteca" to listOf(video),
                        "Vídeos" to listOf(video),
                        "Actuaciones en directo" to listOf(live, track.copy(id = "untyped-live")),
                        "Clips" to listOf(track.copy(id = "untyped-video")),
                        "Vacío" to emptyList(),
                    ),
                sectionBrowseIds =
                    mapOf(
                        "Canciones" to ("VLOLtracks" to null),
                        "De tu biblioteca" to ("MPLAartist" to null),
                        "Vídeos" to ("VLOLvideos" to null),
                        "Clips" to ("VLOLclips" to null),
                    ),
            )
        assertEquals("Canciones", page.topTracksSection)
        assertEquals("De tu biblioteca", page.librarySection)
        assertEquals(setOf("Vídeos", "Actuaciones en directo", "Clips"), page.videoSections)
        assertNull(page.copy(sectionBrowseIds = emptyMap()).librarySection)
    }

    @Test
    fun videoShelvesRequireEveryItemToBeAVideo() {
        val track = SongItem("audio", "Track", null, emptyList(), null, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
        val videos =
            listOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_LIVE", "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_VIDEO")
                .mapIndexed { index, type -> track.copy(id = "video$index", musicVideoType = type) }
        assertTrue(videos.isVideoShelf())
        videos.forEach { assertTrue(listOf(it).isVideoShelf()) }
        assertFalse(emptyList<YTItem>().isVideoShelf())
        assertFalse((videos + track).isVideoShelf())
        assertFalse((videos + ArtistItem("artist", "Artist", null)).isVideoShelf())
        assertFalse((videos + track.copy(musicVideoType = null)).isVideoShelf())
        assertFalse(listOf(track.copy(musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")).isVideoShelf())
        assertFalse(listOf(videos.first().copy(isUploaded = true)).isVideoShelf())
    }

    @Test
    fun artistVideoHintsCannotOverrideExplicitAudioOrUploadedItems() {
        val untyped = SongItem("unknown", "Track", null, emptyList(), null)
        val video = untyped.copy(id = "video", musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        val audio = untyped.copy(id = "audio", musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
        assertTrue(listOf(untyped, video).isVideoShelf(allowUntypedSongs = true))
        assertFalse(listOf(audio, video).isVideoShelf(allowUntypedSongs = true))
        assertFalse(listOf(untyped.copy(isUploaded = true)).isVideoShelf(allowUntypedSongs = true))
        val page =
            ArtistPageResult(
                sections = mapOf("Mixed" to listOf(audio, video)),
                sectionBrowseIds = mapOf("Mixed" to ("VLOLmixed" to null)),
            )
        assertEquals(setOf("Mixed"), page.videoSections)
        assertFalse(page.sections.getValue("Mixed").isVideoShelf(allowUntypedSongs = "Mixed" in page.videoSections))
    }

    @Test
    fun missingDurationsAreFetchedInOneBatchAndMatchedById() =
        runBlocking {
            var requests = 0
            val client =
                client {
                    requests++
                    """{"queueDatas":[
              {"content":{"playlistPanelVideoRenderer":{"videoId":"second","title":{"runs":[{"text":"Second"}]},"lengthText":{"runs":[{"text":"4:12"}]}}}},
              {"content":{"playlistPanelVideoRenderer":{"videoId":"first","title":{"runs":[{"text":"First"}]},"lengthText":{"runs":[{"text":"3:20"}]}}}},
              {"content":{"playlistPanelVideoRenderer":{"videoId":"unrequested","title":{"runs":[{"text":"Other"}]},"lengthText":{"runs":[{"text":"5:00"}]}}}}
            ]}"""
                }
            try {
                val repository = YouTubeRepository(InnerTube(client))
                assertEquals(emptyList(), repository.getSongsMetadata(emptyList()))
                assertEquals(0, requests)
                val songs = repository.getSongsMetadata(listOf("first", "second", "first")).associateBy { it.id }
                assertEquals(setOf("first", "second"), songs.keys)
                assertEquals(200L, songs["first"]?.duration)
                assertEquals(252L, songs["second"]?.duration)
                assertEquals(1, requests)
            } finally {
                client.close()
            }
        }

    private fun client(body: (String) -> String) =
        HttpClient(
            MockEngine { request ->
                respond(body(request.url.encodedPath), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
}

package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HomeRendererParityTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun cardShelfKeepsTheCollectionAndItsPreviewRows() {
        val shelf =
            Json
                .parseToJsonElement(
                    """{"musicCardShelfRenderer":{
                    "header":{"musicCardShelfHeaderBasicRenderer":{"title":{"simpleText":"Dalla community"}}},
                    "title":{"runs":[{"text":"Workout music"}]},
                    "subtitle":{"runs":[{"text":"Creator"},{"text":" • "},{"text":"28 brani"}]},
                    "onTap":{"browseEndpoint":{"browseId":"VLPLworkout"}},
                    "contents":[{"musicResponsiveListItemRenderer":{
                        "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track"}]}}}],
                        "playlistItemData":{"videoId":"preview"}
                    }}]
                }}""",
                ).jsonObject
        val sections = mutableListOf<HomeSection>()
        YouTubeResponseParser.processShelfToSections(shelf, sections)
        val section = sections.single()
        assertEquals("Dalla community", section.title)
        assertEquals("VLPLworkout", section.browseId)
        assertEquals(listOf("VLPLworkout", "preview"), section.items.map { it.id })
        assertEquals("Creator •\u00a028 brani", assertIs<PlaylistItem>(section.items.first()).subtitle)
    }

    @Test
    fun similarArtistShelfUsesMoreEndpointAndKeepsHeaderArtist() {
        val section =
            parseShelf(
                """{"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"femtanyl","navigationEndpoint":{"browseEndpoint":{"browseId":"UCfemtanyl"}}}]},
                    "strapline":{"simpleText":"Similar to"},
                    "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{
                      "browseId":"FEmusic_similar","params":"femtanyl-recommendations"
                    }}}}
                  }},
                  "contents":[{"musicTwoRowItemRenderer":{
                    "title":{"simpleText":"Recommended mix"},
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDmix"}}
                  }}]
                }}""",
            )
        assertEquals("femtanyl", section.title)
        assertEquals("Similar to", section.subtitle)
        assertEquals("UCfemtanyl", section.headerBrowseId)
        assertEquals("FEmusic_similar", section.browseId)
        assertEquals("femtanyl-recommendations", section.params)
        assertEquals(listOf("VLRDmix"), section.items.map { it.id })
    }

    @Test
    fun filteredShelfMoreEndpointOverridesBothTitleEndpoints() {
        val section =
            parseShelf(
                """{"musicCarouselShelfRenderer":{
                  "title":{"runs":[{"text":"Mixed for you","navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_home"}}}]},
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"Mixed for you","navigationEndpoint":{"browseEndpoint":{"browseId":"FEmusic_mixes"}}}]},
                    "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{
                      "browseId":"FEmusic_mixes","params":"mixed-for-you"
                    }}}}
                  }},
                  "contents":[{"musicTwoRowItemRenderer":{
                    "title":{"simpleText":"Personal mix"},
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDpersonal"}}
                  }}]
                }}""",
            )
        assertEquals("Mixed for you", section.title)
        assertEquals("FEmusic_mixes", section.browseId)
        assertEquals("mixed-for-you", section.params)
        assertEquals(listOf("VLRDpersonal"), section.items.map { it.id })
    }

    @Test
    fun artistTitleWithoutMoreButtonKeepsItsSourceEndpoint() {
        val section =
            parseShelf(
                """{"musicCarouselShelfRenderer":{
                  "header":{"musicCarouselShelfBasicHeaderRenderer":{
                    "title":{"runs":[{"text":"femtanyl","navigationEndpoint":{"browseEndpoint":{"browseId":"UCfemtanyl"}}}]}
                  }},
                  "contents":[{"musicTwoRowItemRenderer":{
                    "title":{"simpleText":"Recommended mix"},
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDmix"}}
                  }}]
                }}""",
            )
        assertEquals("UCfemtanyl", section.browseId)
        assertEquals("UCfemtanyl", section.headerBrowseId)
    }

    @Test
    fun immersiveShelvesKeepHeadersItemsAndBothContinuationLevels() =
        runTest {
            val shelf =
                """{"musicImmersiveCarouselShelfRenderer":{
                    "header":{"musicImmersiveCarouselShelfBasicHeaderRenderer":{
                        "title":{"runs":[{"text":"Mixtape per te"}]},
                        "strapline":{"simpleText":"PER RILASSARTI"},
                        "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"header-artwork"}]}}},
                        "moreContentButton":{"buttonRenderer":{"navigationEndpoint":{"browseEndpoint":{
                            "browseId":"recommendations","params":"relax"
                        }}}}
                    }},
                    "contents":[{"musicTwoRowItemRenderer":{
                        "title":{"runs":[{"text":"Chill Mix 01"}]},
                        "subtitle":{"runs":[{"text":"Lady Gaga"},{"text":", "},{"text":"Rihanna"},{"text":", Emma"}]},
                        "navigationEndpoint":{"browseEndpoint":{"browseId":"VLRDmix"}}
                    }}],
                    "continuations":[{"nextContinuationData":{"continuation":"shelf-next"}}]
                }}"""
            val initial = """{"contents":{"sectionListRenderer":{"contents":[$shelf],
                "continuations":[{"nextContinuationData":{"continuation":"home-next"}}]}}}"""
            val next = """{"continuationContents":{"sectionListContinuation":{"contents":[$shelf]}}}"""
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        respond(
                            if (requests++ == 0) initial else next,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(client, retryDelay = {}))
                val page = repository.getHomePageData()
                val section = page.sections.single()
                assertEquals("home-next", page.continuation)
                assertEquals("Mixtape per te", section.title)
                assertEquals("PER RILASSARTI", section.subtitle)
                assertEquals("header-artwork", section.thumbnail)
                assertEquals("recommendations", section.browseId)
                assertEquals("relax", section.params)
                assertEquals("shelf-next", section.continuation)
                assertEquals(listOf("VLRDmix"), section.items.map { it.id })
                assertEquals("Lady Gaga, Rihanna, Emma", assertIs<PlaylistItem>(section.items.single()).subtitle)
                val continued = repository.getHomeSectionsContinuation("home-next", loadAllPages = false)
                assertEquals(listOf(section), continued.first)
                assertEquals(2, requests)
            } finally {
                client.close()
            }
        }

    private fun parseShelf(shelf: String): HomeSection {
        val sections = mutableListOf<HomeSection>()
        YouTubeResponseParser.processShelfToSections(Json.parseToJsonElement(shelf).jsonObject, sections)
        return sections.single()
    }
}

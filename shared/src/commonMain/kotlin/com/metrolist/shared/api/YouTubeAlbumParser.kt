package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.browseId
import com.metrolist.shared.api.YouTubeResponseParser.descriptionRunsToTextWithUrls
import com.metrolist.shared.api.YouTubeResponseParser.parseItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object YouTubeAlbumParser {
    data class AlbumHeader(
        val item: AlbumItem,
        val description: String?,
    )

    fun parseAlbumHeader(
        response: BrowseResponse,
        albumId: String,
    ): AlbumHeader? {
        val urlCanonical =
            response.microformat
                ?.get(
                    "microformatDataRenderer",
                )?.jsonObject
                ?.get("urlCanonical")
                ?.jsonPrimitive
                ?.contentOrNull
        val playlistId =
            urlCanonical?.playlistIdFromCanonicalUrl()
                ?: response.header
                    ?.musicDetailHeaderRenderer
                    ?.get(
                        "menu",
                    )?.jsonObject
                    ?.get(
                        "menuRenderer",
                    )?.jsonObject
                    ?.get(
                        "topLevelButtons",
                    )?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get(
                        "buttonRenderer",
                    )?.jsonObject
                    ?.get(
                        "navigationEndpoint",
                    )?.jsonObject
                    ?.get("watchPlaylistEndpoint")
                    ?.jsonObject
                    ?.get("playlistId")
                    ?.jsonPrimitive
                    ?.contentOrNull

        if (playlistId == null) return null

        val header =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.let { it["tabs"]?.jsonArray?.getOrNull(0)?.jsonObject }
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
                ?.get("contents")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("musicResponsiveHeaderRenderer")
                ?.jsonObject
                ?: response.header?.musicDetailHeaderRenderer

        val title =
            header
                ?.get(
                    "title",
                )?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: header
                    ?.get("title")
                    ?.jsonObject
                    ?.get("simpleText")
                    ?.jsonPrimitive
                    ?.contentOrNull
                ?: ""

        val artists = mutableListOf<ArtistTiny>()
        val artistRuns =
            header
                ?.get("straplineTextOne")
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
        val subtitleRuns =
            header
                ?.get("subtitle")
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
        val parsedArtists = mutableListOf<Pair<String?, String>>()
        if (artistRuns != null) {
            artistRuns.forEachIndexed { index, run ->
                if (index % 2 == 0) {
                    val runObj = run.jsonObject
                    val name = runObj["text"]?.jsonPrimitive?.contentOrNull
                    val id =
                        runObj["navigationEndpoint"]
                            ?.jsonObject
                            ?.get(
                                "browseEndpoint",
                            )?.jsonObject
                            ?.get("browseId")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    if (name != null &&
                        (id == null || id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist"))
                    ) {
                        parsedArtists.add(Pair(id, name))
                    }
                }
            }
        } else if (subtitleRuns != null) {
            val artistRun =
                subtitleRuns.firstOrNull { run ->
                    val runObj = run.jsonObject
                    val id =
                        runObj["navigationEndpoint"]
                            ?.jsonObject
                            ?.get(
                                "browseEndpoint",
                            )?.jsonObject
                            ?.get("browseId")
                            ?.jsonPrimitive
                            ?.contentOrNull
                    id?.startsWith("UC") == true || id?.startsWith("FEmusic_library_privately_owned_artist") == true
                } ?: subtitleRuns.getOrNull(2)

            if (artistRun != null) {
                val runObj = artistRun.jsonObject
                val name = runObj["text"]?.jsonPrimitive?.contentOrNull
                val id =
                    runObj["navigationEndpoint"]
                        ?.jsonObject
                        ?.get(
                            "browseEndpoint",
                        )?.jsonObject
                        ?.get("browseId")
                        ?.jsonPrimitive
                        ?.contentOrNull
                if (name != null) {
                    val validId = id?.takeIf { it.startsWith("UC") || it.startsWith("FEmusic_library_privately_owned_artist") }
                    parsedArtists.add(Pair(validId, name))
                }
            }
        }

        for ((id, name) in parsedArtists) {
            var thumb: String? = null
            if (header != null) {
                val runs =
                    header["straplineTextOne"]
                        ?.jsonObject
                        ?.get("runs")
                        ?.jsonArray
                if (runs != null) {
                    val runObj = runs.firstOrNull { r -> r.jsonObject["text"]?.jsonPrimitive?.contentOrNull == name }?.jsonObject
                    thumb =
                        runObj
                            ?.get(
                                "thumbnail",
                            )?.jsonObject
                            ?.get("thumbnails")
                            ?.jsonArray
                            ?.lastOrNull()
                            ?.jsonObject
                            ?.get("url")
                            ?.jsonPrimitive
                            ?.contentOrNull
                }
            }
            artists.add(ArtistTiny(id, name, thumb))
        }

        val year =
            header
                ?.get(
                    "subtitle",
                )?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.lastOrNull()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull

        val thumbnail =
            (
                header
                    ?.get("thumbnail")
                    ?.jsonObject
                    ?.get("musicThumbnailRenderer")
                    ?.jsonObject
                    ?: header
                        ?.get("thumbnail")
                        ?.jsonObject
                        ?.get("croppedSquareThumbnailRenderer")
                        ?.jsonObject
            )?.get(
                "thumbnail",
            )?.jsonObject
                ?.get("thumbnails")
                ?.jsonArray
                ?.lastOrNull()
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")

        val description =
            header
                ?.get("description")
                ?.jsonObject
                ?.get("musicDescriptionShelfRenderer")
                ?.jsonObject
                ?.get("description")
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.descriptionRunsToTextWithUrls()

        val albumItem =
            AlbumItem(
                id = albumId,
                title = title,
                thumbnail = thumbnail,
                artists = artists,
                year = year,
                playlistId = playlistId,
                songCount =
                    header
                        ?.get("secondSubtitle")
                        ?.jsonObject
                        ?.get("runs")
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.substringBefore(' ')
                        ?.toIntOrNull(),
            )

        return AlbumHeader(albumItem, description)
    }

    fun parseAlbumRecommendations(secondaryContents: JsonArray): Pair<List<AlbumItem>, List<AlbumItem>> {
        var otherVersions = emptyList<AlbumItem>()
        var artistAlbums = emptyList<AlbumItem>()

        secondaryContents.forEach { section ->
            val shelf = section.jsonObject["musicCarouselShelfRenderer"]?.jsonObject
            val items =
                shelf?.get("contents")?.jsonArray?.mapNotNull {
                    parseItem(it.jsonObject["musicTwoRowItemRenderer"]?.jsonObject) as? AlbumItem
                } ?: emptyList()

            when (shelf?.get("itemSize")?.jsonPrimitive?.contentOrNull) {
                "COLLECTION_STYLE_ITEM_SIZE_MEDIUM" -> otherVersions = otherVersions + items
                "COLLECTION_STYLE_ITEM_SIZE_SMALL" -> artistAlbums = artistAlbums + items
            }
        }

        return otherVersions to artistAlbums
    }
}

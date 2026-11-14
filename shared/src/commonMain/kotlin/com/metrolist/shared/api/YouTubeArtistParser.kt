package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.bestChannelThumbnailUrl
import com.metrolist.shared.api.YouTubeResponseParser.descriptionRunsToTextWithUrls
import com.metrolist.shared.api.YouTubeResponseParser.pageTitle
import com.metrolist.shared.api.YouTubeResponseParser.parseBrowseResponse
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.utils.monthlyListenersCount
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object YouTubeArtistParser {
    fun parseArtistRadioEndpoint(response: BrowseResponse): JsonObject? {
        val button =
            artistHeader(response)
                ?.get("startRadioButton")
                ?.jsonObject
                ?.get("buttonRenderer")
                ?.jsonObject ?: return null
        if (button["isDisabled"]?.jsonPrimitive?.booleanOrNull == true) return null
        val navigation = button["navigationEndpoint"]?.jsonObject ?: return null
        return (navigation["watchEndpoint"] ?: navigation["watchPlaylistEndpoint"])?.jsonObject
    }

    private fun artistHeader(response: BrowseResponse): JsonObject? {
        val header = response.header
        val responsiveHeader =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.get("tabs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
                ?.get("contents")
                ?.jsonArray
                ?.firstNotNullOfOrNull { section ->
                    section.jsonObject["musicResponsiveHeaderRenderer"]?.jsonObject
                }
        return header?.musicImmersiveHeaderRenderer
            ?: header?.musicVisualHeaderRenderer
            ?: header?.musicHeaderRenderer
            ?: header?.musicDetailHeaderRenderer
            ?: header?.musicResponsiveHeaderRenderer
            ?: responsiveHeader
    }

    fun parseArtist(
        response: BrowseResponse,
        channelId: String,
    ): ArtistPageResult {
        val sectionBrowseIds = mutableMapOf<String, Pair<String?, String?>>()
        val parsed = parseBrowseResponse(response, sectionBrowseIds, untitledShelfTitle = null)

        val h = artistHeader(response)

        val banner =
            h
                ?.get(
                    "thumbnail",
                )?.jsonObject
                ?.get(
                    "musicThumbnailRenderer",
                )?.jsonObject
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
                ?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")
                ?: h
                    ?.get(
                        "foregroundThumbnail",
                    )?.jsonObject
                    ?.get(
                        "musicThumbnailRenderer",
                    )?.jsonObject
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
                    ?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")
                ?: h
                    ?.get(
                        "backgroundImage",
                    )?.jsonObject
                    ?.get(
                        "musicThumbnailRenderer",
                    )?.jsonObject
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
                    ?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")

        val subscribers =
            h
                ?.get(
                    "subscriptionButton",
                )?.jsonObject
                ?.get(
                    "subscribeButtonRenderer",
                )?.jsonObject
                ?.get(
                    "subscriberCountText",
                )?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: h
                    ?.get(
                        "subscriptionButton",
                    )?.jsonObject
                    ?.get(
                        "subscribeButtonRenderer",
                    )?.jsonObject
                    ?.get(
                        "longSubscriberCountText",
                    )?.jsonObject
                    ?.get("runs")
                    ?.jsonArray
                    ?.getOrNull(0)
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull

        val isSubscribed =
            h
                ?.get("subscriptionButton")
                ?.jsonObject
                ?.get("subscribeButtonRenderer")
                ?.jsonObject
                ?.get("subscribed")
                ?.jsonPrimitive
                ?.booleanOrNull ?: false

        val description =
            h
                ?.get("description")
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.descriptionRunsToTextWithUrls()

        val monthlyListeners =
            h
                ?.get("monthlyListenerCount")
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                ?.takeIf { it.isNotBlank() }
                ?.let(::monthlyListenersCount)

        val exactArtistItem =
            parsed.sections.values
                .asSequence()
                .flatten()
                .filterIsInstance<ArtistItem>()
                .firstOrNull { it.id == channelId }

        val exactSongArtist =
            parsed.sections.values
                .asSequence()
                .flatten()
                .filterIsInstance<SongItem>()
                .flatMap { it.artists }
                .firstOrNull { it.id == channelId }

        val exactAlbumArtist =
            parsed.sections.values
                .asSequence()
                .flatten()
                .filterIsInstance<AlbumItem>()
                .flatMap { it.artists }
                .firstOrNull { it.id == channelId }

        val exactTitle = exactArtistItem?.title ?: exactSongArtist?.name ?: exactAlbumArtist?.name
        val exactThumbnail = exactArtistItem?.thumbnail ?: exactSongArtist?.thumbnail ?: exactAlbumArtist?.thumbnail

        val headerTitle =
            h
                ?.get("title")
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: h
                    ?.get("title")
                    ?.jsonObject
                    ?.get("simpleText")
                    ?.jsonPrimitive
                    ?.contentOrNull
                ?: h?.get("title")?.jsonPrimitive?.contentOrNull

        val title =
            headerTitle
                ?: exactTitle
                ?: response.pageTitle()
                ?: channelId.takeUnless { it.startsWith("UC") }
                ?: ""

        val artistThumbnail = h.bestChannelThumbnailUrl() ?: exactThumbnail ?: banner

        val viewCount =
            response.contents
                ?.singleColumnBrowseResultsRenderer
                ?.get("tabs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
                ?.get("contents")
                ?.jsonArray
                ?.firstNotNullOfOrNull { element ->
                    element.jsonObject["musicDescriptionShelfRenderer"]
                        ?.jsonObject
                        ?.get("subheader")
                        ?.jsonObject
                        ?.get("runs")
                        ?.jsonArray
                        ?.getOrNull(0)
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.contentOrNull
                }

        val artistInfo =
            ArtistItem(
                id = channelId,
                channelId = channelId,
                title = title,
                thumbnail = artistThumbnail,
                subscribers = subscribers,
                banner = banner,
                description = description,
                monthlyListeners = monthlyListeners,
                viewCount = viewCount,
                isSubscribed = isSubscribed,
            )

        val sections = parsed.sections.toMutableMap()
        sections["header"] = listOf(artistInfo)
        val albumArtists = if (title.isNotBlank()) listOf(ArtistTiny(channelId, title, artistThumbnail)) else emptyList()
        sections.forEach { (key, items) ->
            sections[key] =
                items.map { item ->
                    when (item) {
                        is SongItem -> {
                            item.copy(
                                artists =
                                    item.artists.map { artist ->
                                        if (artist.name == title && artist.thumbnail == null) {
                                            artist.copy(thumbnail = artistThumbnail)
                                        } else {
                                            artist
                                        }
                                    },
                            )
                        }

                        is AlbumItem -> {
                            item.copy(
                                artists =
                                    item.artists.ifEmpty { albumArtists }.map { artist ->
                                        if (artist.name == title && artist.thumbnail == null) {
                                            artist.copy(thumbnail = artistThumbnail)
                                        } else {
                                            artist
                                        }
                                    },
                            )
                        }

                        else -> {
                            item
                        }
                    }
                }
        }
        return BlockedContent.filterArtistPage(ArtistPageResult(sections, sectionBrowseIds)).let { page ->
            page.copy(sections = artistSectionsWithLatestRelease(page.sections))
        }
    }
}

package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.asArray
import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.bestChannelThumbnailUrl
import com.metrolist.shared.api.YouTubeResponseParser.descriptionText
import com.metrolist.shared.api.YouTubeResponseParser.findAllObjects
import com.metrolist.shared.api.YouTubeResponseParser.findFirstObject
import com.metrolist.shared.api.YouTubeResponseParser.primitiveText
import com.metrolist.shared.api.YouTubeResponseParser.textContent
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isEditablePlaylistId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object YouTubePlaylistParser {
    fun parsePlaylistSections(
        response: BrowseResponse,
        parsed: YouTubeResponseParser.ParsedBrowse,
        playlistId: String,
    ): Map<String, List<YTItem>> {
        val header = response.header
        val editable = header?.musicEditablePlaylistDetailHeaderRenderer
        val detailHeaders =
            listOfNotNull(
                header?.musicDetailHeaderRenderer,
                editable
                    ?.get("header")
                    .asObject()
                    ?.get("musicDetailHeaderRenderer")
                    .asObject(),
            )
        val headers =
            detailHeaders +
                listOfNotNull(
                    editable
                        ?.get("header")
                        .asObject()
                        ?.get("musicResponsiveHeaderRenderer")
                        .asObject(),
                    header?.musicResponsiveHeaderRenderer,
                ) +
                listOfNotNull(
                    response.contents?.singleColumnBrowseResultsRenderer,
                    response.contents?.twoColumnBrowseResultsRenderer,
                ).flatMap { it.findAllObjects("musicResponsiveHeaderRenderer") }
        val microformat = response.microformat?.get("microformatDataRenderer").asObject()
        val title = headers.firstNotNullOfOrNull { it["title"].textContent() } ?: microformat?.get("title").primitiveText()
        val thumbnail =
            headers.firstNotNullOfOrNull { it["thumbnail"].thumbnailUrl() }
                ?: editable?.get("thumbnail").thumbnailUrl()
                ?: microformat?.get("thumbnail").thumbnailUrl()
        val description =
            headers.firstNotNullOfOrNull { it["description"].descriptionText() }
                ?: editable?.get("description").descriptionText()
                ?: microformat?.get("description").descriptionText()
        val author = headers.firstNotNullOfOrNull { author(it, it in detailHeaders) }.takeUnless { playlistId.isMixPlaylistId() }
        val statistics = headers.map { statistics(it, author?.name) }.maxByOrNull { it.size }.orEmpty()

        if (title == null && parsed.sections.values.all(List<YTItem>::isEmpty) && parsed.continuationBranches.isEmpty()) {
            return emptyMap()
        }

        val playlistInfo =
            PlaylistItem(
                id = playlistId,
                title = title ?: "Playlist",
                thumbnail = thumbnail,
                author = author?.name,
                authorId = author?.browseId,
                authorThumbnail = author?.thumbnail,
                viewCount = statistics.getOrNull(statistics.size - 3),
                songCount = statistics.getOrNull(if (statistics.size > 1) statistics.size - 2 else 0),
                durationText = statistics.lastOrNull()?.takeIf { statistics.size > 1 },
                isEditable = playlistId.isEditablePlaylistId() && (editable != null || response.hasPlaylistEditControls()),
                description = description,
            )

        return parsed.sections + ("header" to listOf(playlistInfo))
    }

    private data class Author(
        val name: String,
        val browseId: String?,
        val thumbnail: String?,
    )

    private fun author(
        header: JsonObject,
        isDetailHeader: Boolean,
    ): Author? {
        val facepile = header["facepile"].asObject()?.get("avatarStackViewModel").asObject()
        val strapline = header["straplineTextOne"].asObject()
        val subtitle = header["subtitle"].asObject()
        val linkedAuthor = subtitle?.get("runs").asArray()?.firstOrNull { it.asObject()?.findFirstObject("browseEndpoint") != null }
        val name =
            facepile?.get("text").textContent()
                ?: strapline.textContent()
                ?: linkedAuthor?.asObject()?.get("text").primitiveText()
                ?: subtitle.metadataParts().let { parts ->
                    // Legacy detail headers put the creator between the type and year, or in the only run.
                    if (!isDetailHeader) {
                        null
                    } else {
                        (if (parts.size > 1) parts.getOrNull(1) else parts.firstOrNull())?.takeUnless { it.all(Char::isDigit) }
                    }
                }
        return name?.takeIf(String::isNotBlank)?.let {
            val source = facepile ?: strapline ?: linkedAuthor.asObject()
            Author(
                name = it,
                browseId = source?.findFirstObject("browseEndpoint")?.get("browseId").primitiveText(),
                thumbnail = facepile?.bestChannelThumbnailUrl() ?: header["straplineThumbnail"].asObject()?.bestChannelThumbnailUrl(),
            )
        }
    }

    private fun statistics(
        header: JsonObject,
        authorName: String?,
    ): List<String> {
        val subtitle =
            (
                header["subtitle"]
                    .asObject()
                    ?.get("runs")
                    .asArray()
                    ?.filter { it.asObject()?.findFirstObject("browseEndpoint") == null }
                    ?.joinToString("") {
                        it
                            .asObject()
                            ?.get("text")
                            .primitiveText()
                            .orEmpty()
                    }?.let { JsonPrimitive(it) } ?: header["subtitle"]
            ).metadataParts().filter { part ->
                part != authorName && part.any(Char::isDigit) && !part.all(Char::isDigit)
            }
        // Both header versions order statistics as optional views, track count, then duration.
        return (subtitle + header["secondSubtitle"].metadataParts()).filter { it != authorName }
    }

    private fun JsonElement?.thumbnailUrl(): String? {
        val obj = asObject() ?: return null
        val thumbnails = obj["thumbnails"].asArray() ?: obj.findFirstObject("thumbnail")?.get("thumbnails").asArray()
        return thumbnails
            ?.lastOrNull()
            .asObject()
            ?.get("url")
            .primitiveText()
    }

    private fun JsonElement?.metadataParts(): List<String> =
        textContent()
            ?.split('•', '·')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
}

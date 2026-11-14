package com.metrolist.shared.api

import com.metrolist.shared.api.YouTubeResponseParser.asArray
import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.findFirstObject
import com.metrolist.shared.api.YouTubeResponseParser.parseUploadedSongRenderer
import com.metrolist.shared.api.YouTubeResponseParser.textContent
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.isUploadedType
import com.metrolist.shared.model.parseDisplayCount
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object YouTubeItemParser {
    private val whitespaceRegex = Regex("\\s+")
    private val localizedCountRegex = Regex("""^\p{N}+[.,]\p{N}+\s+.+""")
    private val artworkSizeRegex = Regex("=w\\d+-h\\d+.*$")
    private val audienceRegex = Regex("(?i)\\b(?:views|plays|listeners|audience)\\b")
    private val yearRegex = Regex("\\d{4}")
    private val podcastDateRegex =
        Regex(
            "(?i)^(?:today|yesterday|just now|\\d+\\s*(?:minutes?|hours?|days?|weeks?|months?|years?|mo|[smhdwy])\\s+ago|(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?\\s+\\d{1,2}(?:,\\s*\\d{4})?|\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})$",
        )

    private val textDurationRegex = Regex("(?i)^(?:(\\d+)\\s*(?:hr|hrs|h|hours?))?\\s*(?:(\\d+)\\s*(?:min|mins|m|minutes?))?$")

    private fun parseTextDuration(text: String): Long? {
        val match = textDurationRegex.matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull()
        val minutes = match.groupValues[2].toLongOrNull()
        return if (hours == null && minutes == null) null else (hours ?: 0) * 3600 + (minutes ?: 0) * 60
    }

    /** Episode rows list "views • date" in `subtitle` and the duration in `playbackProgress`; classify each piece by shape. */
    private fun episodeStats(item: JsonObject): Triple<String?, String?, Long?> {
        var views: String? = null
        var date: String? = null
        var duration: Long? = null
        val sources =
            listOf(
                item["subtitle"],
                item["playbackProgress"]
                    ?.jsonObject
                    ?.get("musicPlaybackProgressRenderer")
                    ?.jsonObject
                    ?.get("durationText"),
            )
        sources.forEach { source ->
            source.textContent()?.split('•', '·')?.forEach { raw ->
                val text = raw.trim()
                when {
                    text.contains("views", true) || text.contains("plays", true) -> views = views ?: text
                    text.isPodcastDateText() && !text.matches(yearRegex) -> date = date ?: text
                    else -> parseTextDuration(text)?.let { duration = duration ?: it }
                }
            }
        }
        return Triple(views, date, duration)
    }

    private fun String.isPodcastDateText(): Boolean =
        trim().replace(whitespaceRegex, " ").matches(podcastDateRegex) ||
            trim().matches(yearRegex)

    fun parseItem(item: JsonObject?): YTItem? {
        if (item == null) return null
        return try {
            item["musicCategoryListItemRenderer"]?.jsonObject?.let(::parseCategoryItem)
                ?: item["musicNavigationButtonRenderer"]?.jsonObject?.let(::parseCategoryItem)
                ?: item["musicMultiRowListItemRenderer"]?.jsonObject?.let(::parseMultiRow)
                ?: parseRenderer(unwrapRenderer(item))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRenderer(item: JsonObject): YTItem? {
        parseNavigationButton(item)?.let { return it }
        if (item["onTap"].asObject()?.watchEndpoint()?.string("videoId") != null &&
            item["title"] != null
        ) {
            parseMultiRow(item)?.let { return it }
        }
        if (item.containsKey("playbackProgress")) parseMultiRow(item)?.let { return it }
        if (item["flexColumns"] != null || item["playlistItemData"] != null) return parseResponsiveItem(item)
        return parseTwoRowItem(item)
    }

    private fun unwrapRenderer(item: JsonObject): JsonObject =
        item["musicResponsiveListItemRenderer"]?.jsonObject?.let(::unwrapRenderer)
            ?: item["musicTwoRowItemRenderer"]?.jsonObject?.let(::unwrapRenderer)
            ?: item["musicNavigationButtonRenderer"]?.jsonObject?.let(::unwrapRenderer)
            ?: item["musicMultiRowListItemRenderer"]?.jsonObject?.let(::unwrapRenderer)
            ?: item

    private fun parseNavigationButton(item: JsonObject): BrowseItem? {
        val title = item["buttonText"]?.jsonObject.runText() ?: item["text"]?.jsonObject.runText() ?: return null
        val endpoint = item["clickCommand"]?.jsonObject ?: item["navigationEndpoint"]?.jsonObject
        val browseId = endpoint.browseId() ?: return null
        return BrowseItem(browseId, title, params = endpoint.browseParams())
    }

    private fun parseCategoryItem(item: JsonObject): BrowseItem? {
        val title =
            item["buttonText"]?.jsonObject.runText()
                ?: item["text"]?.jsonObject.runText()
                ?: item["title"]?.jsonObject.runText()
                ?: return null
        val endpoint = item["clickCommand"]?.jsonObject ?: item["navigationEndpoint"]?.jsonObject ?: item["onTap"]?.jsonObject
        val browseId = endpoint.browseId() ?: return null
        return BrowseItem(browseId, title, params = endpoint.browseParams())
    }

    private fun parseMultiRow(item: JsonObject): YTItem? {
        val title =
            item["title"].firstRunText()
                ?: item["secondTitle"].firstRunText()
                ?: return null
        val endpoint =
            item["onTap"]?.jsonObject
                ?: item["navigationEndpoint"]?.jsonObject
                ?: item["playNavigationEndpoint"]?.jsonObject
                ?: item["overlay"]
                    ?.jsonObject
                    ?.get("musicItemThumbnailOverlayRenderer")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("musicPlayButtonRenderer")
                    ?.jsonObject
                    ?.get("playNavigationEndpoint")
                    ?.jsonObject
        val watchEndpoint = endpoint?.watchEndpoint()
        val videoId = watchEndpoint?.string("videoId") ?: return null
        val playlistSetVideoId = watchEndpoint.string("playlistSetVideoId")
        val playbackProgress =
            item["playbackProgress"]
                ?.jsonObject
                ?.get("musicPlaybackProgressRenderer")
                ?.jsonObject
        val durationText = playbackProgress?.get("durationText")
        val durationDisplayText =
            durationText.textContent()?.trim { it.isWhitespace() || it == '•' || it == '·' }?.takeIf(
                String::isNotBlank,
            )
        val duration =
            durationText.runs()?.firstNotNullOfOrNull { parseDurationSeconds(it.jsonObject.string("text")) }
                ?: parseDurationSeconds(durationDisplayText)
                ?: item.fixedColumnDuration()
        val metadataRuns =
            item["secondSubtitle"].runs()?.takeIf { it.isNotEmpty() }
                ?: item["secondarySubtitle"].runs()?.takeIf { it.isNotEmpty() }
                ?: item["secondTitle"].runs()
        val metadata = extractMetadata(metadataRuns)
        val artists = metadata.artists
        val thumbnail = item.musicThumbnail()
        val flags = item.rendererFlags()
        return if (flags.podcastEpisode) {
            val (views, date, textDuration) = episodeStats(item)
            EpisodeItem(
                videoId,
                title,
                thumbnail,
                artists.firstOrNull(),
                podcastId = metadata.podcastId,
                publishDateText = date,
                duration = duration ?: textDuration ?: metadata.duration,
                viewCountText = views,
                description = item["description"]?.jsonObject.runText()?.trim(),
                playlistSetVideoId = playlistSetVideoId,
                subtitle = item["subtitle"].textContent()?.trim()?.takeIf(String::isNotBlank),
                durationText = durationDisplayText,
                playbackProgressPercent = playbackProgress?.get("playbackProgressPercentage")?.jsonPrimitive?.intOrNull,
                playbackPositionMs = watchEndpoint["startTimeSeconds"]?.jsonPrimitive?.longOrNull?.times(1000L),
                isUnavailable = isUnavailable(item, title, duration ?: textDuration ?: metadata.duration),
            )
        } else {
            val isExplicit = flags.explicit
            SongItem(
                videoId,
                title,
                thumbnail,
                artists,
                album = metadata.album,
                duration = duration ?: metadata.duration,
                isExplicit = isExplicit,
                playlistSetVideoId = playlistSetVideoId,
                explicitness = isExplicit,
                musicVideoType = item.musicVideoType() ?: title.inferredMusicVideoType(item.hasVideoThumbnail()),
                playCountText = metadata.playCountText,
                isNew = flags.newRelease,
                isUnavailable = isUnavailable(item, title, duration ?: metadata.duration),
            )
        }
    }

    private fun parseResponsiveItem(item: JsonObject): YTItem? {
        if (isUploadedType(item.musicVideoType()) || item.findFirstObject("musicDeletePrivatelyOwnedEntityCommand") != null) {
            return parseUploadedSongRenderer(item)
        }
        val flexColumns = item["flexColumns"].asArray().orEmpty()
        val title =
            flexColumns
                .getOrNull(0)
                .asObject()
                ?.get("musicResponsiveListItemFlexColumnRenderer")
                .asObject()
                ?.get("text")
                .firstRunText()
                ?: if (item["playlistItemData"] != null) "" else return null
        val endpoint = item["navigationEndpoint"].asObject() ?: item["onTap"].asObject()
        val browseId = endpoint.browseId()
        val pageType = endpoint.browsePageType()
        val videoId =
            item["playlistItemData"]?.jsonObject.string("videoId")
                ?: endpoint.watchEndpoint()?.string("videoId")
                ?: item["overlay"]
                    ?.jsonObject
                    ?.get("musicItemThumbnailOverlayRenderer")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("musicPlayButtonRenderer")
                    ?.jsonObject
                    ?.get("playNavigationEndpoint")
                    ?.jsonObject
                    .watchEndpoint()
                    ?.string("videoId")
                ?: flexColumns
                    .getOrNull(0)
                    .flexColumnRuns()
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("navigationEndpoint")
                    ?.jsonObject
                    .watchEndpoint()
                    ?.string("videoId")
        val playlistSetVideoId =
            item["playlistItemData"]?.jsonObject.string("playlistSetVideoId")
                ?: item.string("playlistSetVideoId")
                ?: endpoint.watchEndpoint()?.string("playlistSetVideoId")
        val thumbnail = item.musicThumbnail()

        val isAlbum = browseId != null && (pageType.isAlbumPageType() || browseId.isAlbumBrowseId())
        if (videoId != null && !isAlbum) {
            val metadataRuns = flexColumns.drop(1).flatMap { it.flexColumnRuns().orEmpty() }
            val metadata = extractMetadata(JsonArray(metadataRuns))
            val artists = metadata.artists
            val album = metadata.album
            val duration = metadata.duration ?: item.fixedColumnDuration()
            return playableItem(
                item,
                videoId,
                title,
                thumbnail,
                artists,
                album,
                duration,
                playlistSetVideoId,
                metadata.playCountText,
                metadata.podcastId,
            )
        }

        if (browseId != null) {
            val subtitleRuns =
                item["subtitle"].runs()?.takeIf { it.isNotEmpty() }
                    ?: flexColumns.getOrNull(1).flexColumnRuns()
            val metadata = extractMetadata(subtitleRuns)
            val subtitle = subtitleRuns.subtitleText()
            val flags = item.rendererFlags()
            return browseItem(
                browseId,
                title,
                thumbnail,
                pageType,
                endpoint.browseParams(),
                metadata.artists,
                subtitle,
                flags.editable,
                subtitleRuns.extractYear(),
                metadata.type,
                isExplicit = flags.explicit,
                isNew = flags.newRelease,
                countText = subtitleRuns.collectionCountText(),
                duration = metadata.duration,
                isUnavailable = isUnavailable(item, title, metadata.duration),
                podcastId = metadata.podcastId,
            )
        }
        return null
    }

    private fun parseTwoRowItem(item: JsonObject): YTItem? {
        val title =
            item["title"].firstRunText()
                ?: (item["title"] as? JsonPrimitive)?.contentOrNull
                ?: return null
        val endpoint =
            item["navigationEndpoint"]?.jsonObject
                ?: item["onTap"]?.jsonObject
                ?: item["playNavigationEndpoint"]?.jsonObject
        val browseId = endpoint.browseId()
        val videoId = endpoint.watchEndpoint()?.string("videoId")
        val playlistSetVideoId = endpoint.watchEndpoint()?.string("playlistSetVideoId")
        val subtitleRuns = item["subtitle"].runs()
        val metadata = extractMetadata(subtitleRuns)
        val thumbnail = item.musicThumbnail()
        if (videoId != null) {
            return playableItem(
                item,
                videoId,
                title,
                thumbnail,
                metadata.artists,
                metadata.album,
                metadata.duration,
                playlistSetVideoId,
                metadata.playCountText,
                metadata.podcastId,
            )
        }
        return browseId?.let {
            val flags = item.rendererFlags()
            browseItem(
                it,
                title,
                thumbnail,
                endpoint.browsePageType(),
                endpoint.browseParams(),
                metadata.artists,
                subtitleRuns.subtitleText(),
                flags.editable,
                subtitleRuns.extractYear(),
                metadata.type,
                isExplicit = flags.explicit,
                isNew = flags.newRelease,
                countText = subtitleRuns.collectionCountText(),
                duration = metadata.duration,
                isUnavailable = isUnavailable(item, title, metadata.duration),
                podcastId = metadata.podcastId,
            )
        }
    }

    private fun playableItem(
        renderer: JsonObject,
        videoId: String,
        title: String,
        thumbnail: String?,
        artists: List<ArtistTiny>,
        album: AlbumTiny?,
        duration: Long?,
        playlistSetVideoId: String?,
        playCountText: String?,
        podcastId: String?,
    ): YTItem {
        val flags = renderer.rendererFlags()
        if (flags.podcastEpisode) {
            return EpisodeItem(
                videoId,
                title,
                thumbnail,
                artists.firstOrNull(),
                duration = duration,
                podcastId = podcastId,
                isUnavailable = isUnavailable(renderer, title, duration),
                playlistSetVideoId = playlistSetVideoId,
                playbackPositionMs =
                    (renderer["onTap"]?.jsonObject ?: renderer["navigationEndpoint"]?.jsonObject)
                        .watchEndpoint()
                        ?.get("startTimeSeconds")
                        ?.jsonPrimitive
                        ?.longOrNull
                        ?.times(1000L),
                playbackProgressPercent =
                    renderer["playbackProgress"]
                        ?.jsonObject
                        ?.get("musicPlaybackProgressRenderer")
                        ?.jsonObject
                        ?.get("playbackProgressPercentage")
                        ?.jsonPrimitive
                        ?.intOrNull,
            )
        }
        val isExplicit = flags.explicit
        return SongItem(
            videoId,
            title,
            thumbnail,
            artists,
            album,
            duration,
            isExplicit = isExplicit,
            playlistSetVideoId = playlistSetVideoId,
            explicitness = isExplicit,
            musicVideoType = renderer.musicVideoType() ?: title.inferredMusicVideoType(renderer.hasVideoThumbnail()),
            playCountText = playCountText,
            isNew = flags.newRelease,
            isUnavailable = isUnavailable(renderer, title, duration),
        )
    }

    internal fun isUnavailable(
        renderer: JsonObject,
        title: String,
        duration: Long?,
    ): Boolean {
        if (renderer.string("musicItemRendererDisplayPolicy") != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") return false
        if (title.isBlank()) return true
        // YouTube Music's display policy can hide playable videos, even omitting their play controls.
        if ((duration ?: 0L) > 0L) return false
        val endpoints =
            listOf(
                renderer["onTap"],
                renderer["navigationEndpoint"],
                renderer["playNavigationEndpoint"],
                renderer["overlay"].asObject()?.findFirstObject("musicPlayButtonRenderer"),
                renderer["flexColumns"]
                    .asArray()
                    ?.getOrNull(0)
                    .flexColumnRuns()
                    ?.firstOrNull()
                    .asObject()
                    ?.get("navigationEndpoint"),
            )
        return endpoints.none {
            !it
                .asObject()
                .watchEndpoint()
                ?.string("videoId")
                .isNullOrBlank()
        }
    }

    private fun browseItem(
        browseId: String,
        title: String,
        thumbnail: String?,
        pageType: String?,
        params: String?,
        artists: List<ArtistTiny>,
        subtitle: String?,
        isEditable: Boolean,
        year: String?,
        type: String? = null,
        isExplicit: Boolean = false,
        isNew: Boolean = false,
        countText: String? = null,
        duration: Long? = null,
        isUnavailable: Boolean = false,
        podcastId: String? = null,
    ): YTItem =
        when {
            browseId.startsWith("MPED") ||
                pageType == "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE" ||
                pageType?.contains("PODCAST_EPISODE") == true -> {
                EpisodeItem(
                    id = browseId.removePrefix("MPED"),
                    title = title,
                    thumbnail = thumbnail,
                    author = artists.firstOrNull(),
                    podcastId = podcastId,
                    duration = duration,
                    isUnavailable = isUnavailable,
                )
            }

            pageType.isPodcastPageType() || browseId.startsWith("MPSP") -> {
                PodcastItem(
                    browseId,
                    title,
                    thumbnail,
                    author = subtitle,
                    channelId = artists.firstOrNull { it.id?.startsWith("UC") == true }?.id,
                )
            }

            pageType.isArtistPageType() || browseId.startsWith("UC") -> {
                ArtistItem(browseId, title, thumbnail)
            }

            pageType.isAlbumPageType() || browseId.startsWith("MPREb") || browseId.contains("release_detail") -> {
                AlbumItem(
                    browseId,
                    title,
                    thumbnail,
                    artists,
                    year = year,
                    type = type,
                    songCount = parseDisplayCount(countText),
                    isExplicit = isExplicit,
                    isNew = isNew,
                )
            }

            pageType.isPlaylistPageType() || browseId.isPlaylistBrowseId() -> {
                PlaylistItem(
                    browseId,
                    title,
                    thumbnail,
                    author = artists.firstOrNull()?.name?.takeUnless { browseId.isMixPlaylistId() },
                    songCount = countText,
                    isEditable = isEditable,
                    subtitle = subtitle,
                )
            }

            else -> {
                BrowseItem(browseId, title, thumbnail, params, subtitle)
            }
        }

    private class MetadataCollector {
        val artists = mutableListOf<ArtistTiny>()
        val ambiguousLinks = mutableListOf<Pair<String, String>>()
        val unlinked = mutableListOf<String>()
        var album: AlbumTiny? = null
        var duration: Long? = null
        var playCountText: String? = null
        var type: String? = null

        fun classify(
            text: String,
            endpoint: JsonObject?,
        ) {
            if (text.isBlank()) return
            val browseId = endpoint.browseId()
            if (browseId != null && (endpoint.browsePageType().isAlbumPageType() || browseId.isAlbumBrowseId())) {
                album = AlbumTiny(browseId, text)
                return
            }
            if (browseId != null && (endpoint.browsePageType().isArtistPageType() || browseId.startsWith("UC"))) {
                artists += ArtistTiny(browseId, text)
                return
            }
            if (browseId != null) {
                ambiguousLinks += browseId to text
                return
            }
            val lower = text.lowercase()
            when {
                lower in setOf("song", "video", "album", "single", "ep", "playlist") -> type = text

                lower.contains("views") ||
                    lower.contains("plays") ||
                    lower.contains("listeners") ||
                    lower.contains("audience") -> playCountText = text

                parseDurationSeconds(text) != null -> duration = parseDurationSeconds(text)

                text.firstOrNull()?.isDigit() == true &&
                    (
                        artists.isNotEmpty() || ambiguousLinks.isNotEmpty() || unlinked.isNotEmpty() ||
                            localizedCountRegex.matches(text)
                    ) -> playCountText = text

                else -> unlinked += text
            }
        }
    }

    internal fun extractMetadata(runs: JsonArray?): Metadata {
        val collector = MetadataCollector()
        runs?.forEach { element ->
            val run = element.jsonObject
            val text = run.string("text")?.trim().orEmpty()
            if (text.isBlank() || text in setOf("•", "&", ",")) return@forEach
            val endpoint = run["navigationEndpoint"]?.jsonObject
            if (endpoint.browseId() == null && text.isPodcastDateText()) return@forEach
            if (endpoint?.browseId() == null && " • " in text) {
                text.split(" • ").forEach { collector.classify(it.trim(), null) }
            } else {
                collector.classify(text, endpoint)
            }
        }
        val artists = collector.artists
        val ambiguousLinks = collector.ambiguousLinks
        val unlinked = collector.unlinked
        var album = collector.album
        val duration = collector.duration
        val playCountText = collector.playCountText
        val type = collector.type
        val names =
            unlinked.filterNot {
                it.matches(yearRegex)
            }

        if (artists.isEmpty()) {
            if (ambiguousLinks.isNotEmpty() && names.isEmpty()) {
                val candidate =
                    ambiguousLinks.firstOrNull { (id, _) -> id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist") }
                        ?: ambiguousLinks[0]
                val artistId = candidate.first.takeUnless { it.isAlbumBrowseId() }
                artists += ArtistTiny(artistId, candidate.second)
            } else if (names.isNotEmpty()) {
                artists += ArtistTiny(null, names[0])
            }
        }

        if (album == null) {
            val usedAmbiguousForArtist = artists.any { it.name == ambiguousLinks.firstOrNull()?.second }
            val albumCandidate =
                if (usedAmbiguousForArtist) {
                    ambiguousLinks.getOrNull(1)
                } else {
                    ambiguousLinks.firstOrNull()
                }
            if (albumCandidate != null) {
                album = AlbumTiny(albumCandidate.first, albumCandidate.second)
            } else {
                // Digit-prefixed unlinked album candidates are often localized play counts, unlike linked artists such as 2Pac.
                names
                    .filterNot { name -> name.firstOrNull()?.isDigit() == true || artists.any { it.name == name } }
                    .firstOrNull()
                    ?.let { album = AlbumTiny(null, it) }
            }
        }

        val podcastId =
            runs?.firstNotNullOfOrNull { element ->
                val endpoint = element.jsonObject["navigationEndpoint"]?.jsonObject
                endpoint.browseId()?.takeIf { id ->
                    id.startsWith("MPSP") || endpoint.browsePageType() == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
                }
            }
        return Metadata(artists, album, duration, playCountText, type, podcastId)
    }

    private fun JsonObject.musicThumbnail(): String? {
        val thumbnailContainer = this["thumbnailRenderer"].asObject() ?: this["thumbnail"].asObject()
        return thumbnailContainer
            ?.get("musicThumbnailRenderer")
            .asObject()
            ?.get("thumbnail")
            .asObject()
            ?.get("thumbnails")
            .asArray()
            .largestThumbnailUrl()
            ?.replace(artworkSizeRegex, "=s0")
    }

    private fun JsonArray?.largestThumbnailUrl(): String? {
        var largestUrl: String? = null
        var largestSize = -1L
        this?.forEach { thumbnail ->
            val value = thumbnail.jsonObject
            val url = value.string("url") ?: return@forEach
            val width = value.string("width")?.toLongOrNull() ?: 0L
            val height = value.string("height")?.toLongOrNull() ?: 0L
            val size = if (width > 0L && height > 0L) width * height else maxOf(width, height)
            if (size >= largestSize) {
                largestUrl = url
                largestSize = size
            }
        }
        return largestUrl
    }

    private fun JsonObject?.watchEndpoint(): JsonObject? {
        val value = this ?: return null
        value["watchEndpoint"]?.jsonObject?.let { return it }
        listOf("innertubeCommand", "playNavigationEndpoint", "navigationEndpoint", "command")
            .firstNotNullOfOrNull { key -> value[key]?.jsonObject?.watchEndpoint() }
            ?.let { return it }
        return null
    }

    private fun JsonObject?.browseEndpoint(): JsonObject? = this?.get("browseEndpoint")?.jsonObject

    private fun JsonObject?.browseId(): String? = browseEndpoint()?.string("browseId")

    private fun JsonObject?.browseParams(): String? = browseEndpoint()?.string("params")

    private fun JsonObject?.browsePageType(): String? =
        browseEndpoint()
            ?.get("browseEndpointContextSupportedConfigs")
            ?.jsonObject
            ?.get("browseEndpointContextMusicConfig")
            ?.jsonObject
            ?.string("pageType")

    private fun JsonObject?.string(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonElement?.runs(): JsonArray? = asObject()?.get("runs").asArray()

    private fun JsonObject.fixedColumnDuration(): Long? =
        parseDurationSeconds(
            this["fixedColumns"]
                .asArray()
                ?.getOrNull(0)
                .asObject()
                ?.get("musicResponsiveListItemFixedColumnRenderer")
                .asObject()
                ?.get("text")
                .textContent(),
        )

    private fun JsonElement?.firstRunText(): String? = runs().firstText() ?: (this as? JsonObject)?.string("simpleText")

    private fun JsonElement?.flexColumnRuns(): JsonArray? =
        asObject()
            ?.get("musicResponsiveListItemFlexColumnRenderer")
            .asObject()
            ?.get("text")
            .asObject()
            ?.get("runs")
            .asArray()

    private fun JsonArray?.firstText(): String? = this?.getOrNull(0)?.jsonObject?.string("text")

    private fun JsonArray?.subtitleText(): String? =
        this
            ?.joinToString("") { it.jsonObject.string("text").orEmpty() }
            ?.trim()
            ?.replace(" • ", " •\u00a0")
            ?.replace(" · ", " ·\u00a0")
            ?.takeIf(String::isNotEmpty)

    private fun JsonArray?.collectionCountText(): String? {
        val last = this?.lastOrNull()?.jsonObject ?: return null
        if (last["navigationEndpoint"] != null) return null
        return last
            .string("text")
            ?.substringAfterLast(" • ")
            ?.trim()
            ?.takeIf { text ->
                text.firstOrNull()?.isDigit() == true && text.any(Char::isWhitespace) && text.any(Char::isLetter) &&
                    !text.contains(audienceRegex)
            }
    }

    private fun JsonArray?.extractYear(): String? =
        this?.firstNotNullOfOrNull {
            it.jsonObject
                .string("text")
                ?.trim()
                ?.takeIf(yearRegex::matches)
        }

    private fun JsonObject?.runText(): String? =
        this
            ?.get("runs")
            ?.jsonArray
            ?.joinToString("") { it.jsonObject.string("text").orEmpty() }
            ?.takeIf(String::isNotBlank)

    private fun String?.isPlaylistPageType(): Boolean = this?.contains("PLAYLIST") == true

    private fun String?.isAlbumPageType(): Boolean = this?.contains("ALBUM") == true

    private fun String?.isArtistPageType(): Boolean = this?.contains("ARTIST") == true || this?.contains("USER_CHANNEL") == true

    private fun String?.isPodcastPageType(): Boolean = this?.contains("PODCAST") == true

    private fun String.isAlbumBrowseId(): Boolean =
        startsWith("MPREb") || contains("release_detail") || startsWith("FEmusic_release_detail")

    private fun String.isPlaylistBrowseId(): Boolean =
        startsWith("FEmusic_library_") || startsWith("VL") || startsWith("PL") || startsWith("RD") || startsWith("LM:")

    internal data class Metadata(
        val artists: List<ArtistTiny>,
        val album: AlbumTiny?,
        val duration: Long?,
        val playCountText: String? = null,
        val type: String? = null,
        val podcastId: String? = null,
    )
}

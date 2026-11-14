package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal object YouTubeResponseParser {
    internal fun JsonObject?.bestChannelThumbnailUrl(): String? {
        fun fromThumbnails(element: JsonElement?): String? {
            val objectValue = element as? JsonObject ?: return null
            return objectValue["thumbnails"]
                ?.jsonArray
                ?.lastOrNull()
                ?.jsonObject
                ?.get("url")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: objectValue["sources"]
                    ?.jsonArray
                    ?.lastOrNull()
                    ?.jsonObject
                    ?.get("url")
                    ?.jsonPrimitive
                    ?.contentOrNull
                ?: objectValue["url"]?.jsonPrimitive?.contentOrNull
        }

        fun fromAvatarObject(element: JsonElement?): String? {
            val objectValue = element as? JsonObject ?: return null
            return fromThumbnails(objectValue)
                ?: fromThumbnails(objectValue["thumbnail"])
                ?: fromAvatarObject(objectValue["musicThumbnailRenderer"])
                ?: fromThumbnails(objectValue["croppedSquareThumbnailRenderer"])
                ?: fromThumbnails(objectValue["avatar"])
                ?: fromThumbnails(objectValue["image"])
                ?: fromThumbnails(objectValue["avatarViewModel"])
                ?: fromThumbnails(objectValue["channelAvatarViewModel"])
        }

        fun findAvatar(element: JsonElement?): String? =
            when (element) {
                is JsonObject -> {
                    element.entries.firstNotNullOfOrNull { (key, value) ->
                        val keyIsAvatar =
                            key.contains("avatar", ignoreCase = true) ||
                                key.contains("profilePhoto", ignoreCase = true) ||
                                key.contains("channelPhoto", ignoreCase = true)
                        if (keyIsAvatar) fromAvatarObject(value) ?: findAvatar(value) else findAvatar(value)
                    }
                }

                is JsonArray -> {
                    element.firstNotNullOfOrNull(::findAvatar)
                }

                else -> {
                    null
                }
            }

        val root = this ?: return null
        val direct =
            listOf(
                "channelAvatarViewModel",
                "avatarViewModel",
                "thumbnail",
                "foregroundThumbnail",
                "musicThumbnailRenderer",
                "backgroundImage",
            ).firstNotNullOfOrNull { key -> fromAvatarObject(root[key]) }
        return (direct ?: findAvatar(root))?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")
    }

    internal fun JsonObject?.runText(): String? {
        val obj = this ?: return null
        return obj["simpleText"]?.jsonPrimitive?.contentOrNull
            ?: obj["runs"]
                ?.jsonArray
                ?.joinToString("") {
                    it.jsonObject["text"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty()
                }?.takeIf { it.isNotBlank() }
            ?: obj["text"]?.jsonPrimitive?.contentOrNull
    }

    internal fun JsonArray?.descriptionRunsToTextWithUrls(): String? {
        val runs = this ?: return null
        val raw =
            runs.joinToString("") { runEl ->
                val run = runEl.jsonObject
                val text = run["text"]?.jsonPrimitive?.contentOrNull ?: ""
                val url =
                    run["navigationEndpoint"]
                        ?.jsonObject
                        ?.get("urlEndpoint")
                        ?.jsonObject
                        ?.get("url")
                        ?.jsonPrimitive
                        ?.contentOrNull
                if (url != null) {
                    if (text.isNotBlank() && !text.contains("://")) "$text $url" else url
                } else {
                    text
                }
            }
        return raw
            .replace(Regex("\\s*\\(\\s*\\)"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    internal fun JsonElement?.descriptionText(): String? {
        val primitive = (this as? JsonPrimitive)?.contentOrNull?.trim()
        if (!primitive.isNullOrEmpty()) return primitive
        val obj = this as? JsonObject ?: return null
        return obj.runText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: obj["description"].descriptionText()
            ?: obj["musicDescriptionShelfRenderer"].descriptionText()
    }

    internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    internal fun JsonElement?.primitiveText(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.primitiveLong(): Long? = (this as? JsonPrimitive)?.longOrNull

    internal fun JsonElement?.textContent(): String? =
        primitiveText()
            ?: asObject().runText()
            ?: asObject()?.get("content").primitiveText()

    internal fun JsonObject.findFirstObject(key: String): JsonObject? {
        this[key].asObject()?.let { return it }
        values.forEach { value ->
            when (value) {
                is JsonObject -> {
                    value.findFirstObject(key)?.let { return it }
                }

                is JsonArray -> {
                    value.forEach { item ->
                        item.asObject()?.findFirstObject(key)?.let { return it }
                    }
                }

                else -> {
                }
            }
        }
        return null
    }

    internal fun JsonElement?.findAllObjects(key: String): List<JsonObject> {
        val matches = mutableListOf<JsonObject>()

        fun visit(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    element[key].asObject()?.let(matches::add)
                    element.values.forEach(::visit)
                }

                is JsonArray -> {
                    element.forEach(::visit)
                }

                else -> {}
            }
        }

        if (this != null) visit(this)
        return matches
    }

    internal fun JsonObject.longValue(key: String): Long? = this[key].primitiveLong()

    internal fun String?.parseIntegerCount(): Long? =
        this
            ?.filter { it.isDigit() }
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()

    internal fun String?.cleanSubscriberCount(): String? =
        this
            ?.replace(Regex("\\s+subscribers?$", RegexOption.IGNORE_CASE), "")
            ?.takeIf { it.isNotBlank() }

    internal fun JsonObject?.feedbackToken(): String? =
        this
            ?.get("feedbackEndpoint")
            ?.jsonObject
            ?.get("feedbackToken")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun JsonObject?.browseEndpoint(): JsonObject? = this?.get("browseEndpoint")?.jsonObject

    internal fun JsonObject?.browseId(): String? = browseEndpoint()?.get("browseId")?.jsonPrimitive?.contentOrNull

    internal fun JsonObject?.browseParams(): String? = browseEndpoint()?.get("params")?.jsonPrimitive?.contentOrNull

    internal fun JsonObject?.browsePageType(): String? =
        browseEndpoint()
            ?.get("browseEndpointContextSupportedConfigs")
            ?.jsonObject
            ?.get("browseEndpointContextMusicConfig")
            ?.jsonObject
            ?.get("pageType")
            ?.jsonPrimitive
            ?.contentOrNull

    internal fun String?.isPlaylistPageType(): Boolean = this == "MUSIC_PAGE_TYPE_PLAYLIST"

    internal fun String?.isAlbumPageType(): Boolean = this == "MUSIC_PAGE_TYPE_ALBUM" || this == "MUSIC_PAGE_TYPE_AUDIOBOOK"

    internal fun String?.isArtistPageType(): Boolean =
        this == "MUSIC_PAGE_TYPE_ARTIST" || this == "MUSIC_PAGE_TYPE_LIBRARY_ARTIST" || this == "MUSIC_PAGE_TYPE_USER_CHANNEL"

    internal fun String?.isPodcastPageType(): Boolean = this == "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"

    private fun JsonObject.carouselHeader(): JsonObject? =
        this["header"].asObject()?.let { header ->
            header["musicCarouselShelfBasicHeaderRenderer"].asObject()
                ?: header["musicImmersiveCarouselShelfBasicHeaderRenderer"].asObject()
                ?: header["musicImmersiveCarouselShelfHeaderRenderer"].asObject()
        }

    internal fun JsonObject.shelfNavigationEndpoint(): JsonObject? =
        carouselHeader()
            ?.get("moreContentButton")
            ?.jsonObject
            ?.get("buttonRenderer")
            ?.jsonObject
            ?.get("navigationEndpoint")
            ?.jsonObject
            ?: this["title"]
                ?.jsonObject
                ?.get("runs")
                ?.jsonArray
                ?.getOrNull(0)
                ?.jsonObject
                ?.get("navigationEndpoint")
                ?.jsonObject
            ?: carouselHeader()
                ?.let { header ->
                    header["title"]
                        ?.jsonObject
                        ?.get("runs")
                        ?.jsonArray
                        ?.getOrNull(0)
                        ?.jsonObject
                        ?.get("navigationEndpoint")
                        ?.jsonObject
                }
            ?: this["header"]
                ?.jsonObject
                ?.get("gridHeaderRenderer")
                ?.jsonObject
                ?.let { header ->
                    header["title"]
                        ?.jsonObject
                        ?.get("runs")
                        ?.jsonArray
                        ?.getOrNull(0)
                        ?.jsonObject
                        ?.get("navigationEndpoint")
                        ?.jsonObject
                        ?: header["callToActionButton"]
                            ?.jsonObject
                            ?.get("buttonRenderer")
                            ?.jsonObject
                            ?.get("navigationEndpoint")
                            ?.jsonObject
                }

    internal fun BrowseResponse.sectionListRenderer(tabIndex: Int = 0): JsonObject? {
        fun JsonObject.tabSectionList(): JsonObject? {
            val tabs = this["tabs"]?.jsonArray
            return (
                tabs
                    ?.firstOrNull {
                        it.jsonObject["tabRenderer"]
                            ?.jsonObject
                            ?.get("selected")
                            ?.jsonPrimitive
                            ?.booleanOrNull == true
                    }
                    ?: tabs?.getOrNull(tabIndex)
            )?.jsonObject
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
        }

        val contents = contents
        return contents?.singleColumnBrowseResultsRenderer?.tabSectionList()
            ?: contents?.twoColumnBrowseResultsRenderer?.tabSectionList()
            ?: contents?.sectionListRenderer
            ?: contents
                ?.twoColumnBrowseResultsRenderer
                ?.get("secondaryContents")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
            ?: continuationContents?.sectionListContinuation
    }

    internal fun BrowseResponse.pageTitle(): String? {
        val headerObj =
            header?.musicImmersiveHeaderRenderer
                ?: header?.musicDetailHeaderRenderer
                ?: header?.musicEditablePlaylistDetailHeaderRenderer
                ?: header?.musicResponsiveHeaderRenderer
                ?: header?.musicVisualHeaderRenderer
                ?: header?.musicHeaderRenderer
        return headerObj?.get("title")?.jsonObject.runText()
            ?: microformat
                ?.get("microformatDataRenderer")
                ?.jsonObject
                ?.get("title")
                ?.jsonPrimitive
                ?.contentOrNull
    }

    internal fun JsonObject.continuationToken(): String? =
        get("continuations")
            ?.jsonArray
            ?.getOrNull(0)
            ?.jsonObject
            ?.get("nextContinuationData")
            ?.jsonObject
            ?.get("continuation")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun JsonObject.flexColumnTextObject(index: Int): JsonObject? =
        get("flexColumns")
            .asArray()
            ?.getOrNull(index)
            .asObject()
            ?.get("musicResponsiveListItemFlexColumnRenderer")
            .asObject()
            ?.get("text")
            .asObject()

    private fun JsonObject.fixedColumnText(index: Int): String? =
        get("fixedColumns")
            .asArray()
            ?.getOrNull(index)
            .asObject()
            ?.get("musicResponsiveListItemFixedColumnRenderer")
            .asObject()
            ?.get("text")
            .asObject()
            .runText()

    private fun JsonObject.uploadVideoId(): String? =
        findFirstObject("queueAddEndpoint")
            ?.get("queueTarget")
            .asObject()
            ?.get("videoId")
            .primitiveText()
            ?: get("playlistItemData")
                .asObject()
                ?.get("videoId")
                .primitiveText()
            ?: get("navigationEndpoint")
                .asObject()
                ?.get("watchEndpoint")
                .asObject()
                ?.get("videoId")
                .primitiveText()
            ?: get("flexColumns")
                .asArray()
                ?.firstOrNull()
                .asObject()
                ?.get("musicResponsiveListItemFlexColumnRenderer")
                .asObject()
                ?.get("text")
                .asObject()
                ?.get("runs")
                .asArray()
                ?.firstOrNull()
                .asObject()
                ?.get("navigationEndpoint")
                .asObject()
                ?.get("watchEndpoint")
                .asObject()
                ?.get("videoId")
                .primitiveText()
            ?: get("overlay")
                .asObject()
                ?.get("musicItemThumbnailOverlayRenderer")
                .asObject()
                ?.get("content")
                .asObject()
                ?.get("musicPlayButtonRenderer")
                .asObject()
                ?.get("playNavigationEndpoint")
                .asObject()
                ?.get("watchEndpoint")
                .asObject()
                ?.get("videoId")
                .primitiveText()
            ?: findFirstObject("watchEndpoint")
                ?.get("videoId")
                .primitiveText()

    private fun JsonObject.uploadEntityId(): String? =
        findFirstObject("musicDeletePrivatelyOwnedEntityCommand")
            ?.get("entityId")
            .primitiveText()

    private fun JsonObject.thumbnailUrl(): String? =
        get("thumbnail")
            .asObject()
            ?.get("musicThumbnailRenderer")
            .asObject()
            ?.get("thumbnail")
            .asObject()
            ?.get("thumbnails")
            .asArray()
            ?.lastOrNull()
            .asObject()
            ?.get("url")
            .primitiveText()
            ?.replace(Regex("=w\\d+-h\\d+.*$"), "=s0")

    private fun String.isMetadataSeparator(): Boolean = isBlank() || this == "•" || this == "&" || this == ","

    private fun JsonArray?.uploadArtists(): List<ArtistTiny> =
        this
            ?.mapNotNull { run ->
                val runObj = run.asObject() ?: return@mapNotNull null
                val name = runObj["text"].primitiveText()?.trim()?.takeUnless { it.isMetadataSeparator() } ?: return@mapNotNull null
                ArtistTiny(runObj["navigationEndpoint"].asObject().browseId(), name)
            }.orEmpty()

    private fun JsonArray?.uploadAlbum(): AlbumTiny? =
        this
            ?.firstNotNullOfOrNull { run ->
                val runObj = run.asObject() ?: return@firstNotNullOfOrNull null
                val name =
                    runObj["text"].primitiveText()?.trim()?.takeUnless { it.isMetadataSeparator() } ?: return@firstNotNullOfOrNull null
                AlbumTiny(runObj["navigationEndpoint"].asObject().browseId(), name)
            }

    internal fun parseUploadedSongRenderer(renderer: JsonObject): SongItem? {
        val videoId = renderer.uploadVideoId() ?: return null
        val title = renderer.flexColumnTextObject(0).runText() ?: return null
        val artistRuns = renderer.flexColumnTextObject(1)?.get("runs").asArray()
        val albumRuns = renderer.flexColumnTextObject(2)?.get("runs").asArray()
        return SongItem(
            id = videoId,
            title = title,
            thumbnail = renderer.thumbnailUrl(),
            artists = artistRuns.uploadArtists(),
            album = albumRuns.uploadAlbum(),
            duration = parseDurationSeconds(renderer.fixedColumnText(0)),
            isExplicit = renderer.hasExplicitBadge(),
            isUploaded = true,
            explicitness = renderer.hasExplicitBadge(),
            uploadEntityId = renderer.uploadEntityId(),
            playlistSetVideoId = renderer["playlistItemData"].asObject()?.get("playlistSetVideoId").primitiveText(),
            musicVideoType = renderer.musicVideoType(),
        )
    }

    internal fun parseUploadedSongItems(contents: JsonArray?): List<SongItem> =
        contents
            ?.mapNotNull { item ->
                val itemObject = item.asObject() ?: return@mapNotNull null
                val renderer =
                    itemObject["musicResponsiveListItemRenderer"].asObject()
                        ?: itemObject.takeIf { it.containsKey("flexColumns") }
                        ?: return@mapNotNull null
                parseUploadedSongRenderer(renderer)
            }.orEmpty()

    internal fun BrowseResponse.uploadShelfObjects(): List<JsonObject> {
        val roots =
            listOfNotNull(
                contents?.singleColumnBrowseResultsRenderer,
                contents?.twoColumnBrowseResultsRenderer,
                contents?.sectionListRenderer,
                contents?.twoColumnBrowseResultsRenderer?.get("secondaryContents").asObject(),
                continuationContents?.sectionListContinuation,
            )
        return roots
            .flatMap { root ->
                root.findAllObjects("musicShelfRenderer") +
                    root.findAllObjects("musicPlaylistShelfRenderer")
            }.plus(listOfNotNull(continuationContents?.musicShelfContinuation, continuationContents?.musicPlaylistShelfContinuation))
            .distinct()
    }

    internal fun processShelfToSections(
        shelfObj: JsonObject,
        sections: MutableList<HomeSection>,
        isPersonal: Boolean = false,
    ) {
        val itemSection = shelfObj["itemSectionRenderer"]?.jsonObject
        if (itemSection != null) {
            itemSection["contents"]?.jsonArray?.forEach {
                processShelfToSections(it.jsonObject, sections, isPersonal)
            }
            return
        }

        val card = shelfObj["musicCardShelfRenderer"].asObject()
        if (card != null) {
            val endpoint = card["onTap"].asObject() ?: card.shelfNavigationEndpoint()
            val parent =
                parseItem(
                    buildJsonObject {
                        card.forEach { (key, value) -> put(key, value) }
                        endpoint?.let { put("navigationEndpoint", it) }
                    },
                )
            val contents = card["contents"].asArray()
            val items = listOfNotNull(parent) + contents.orEmpty().mapNotNull { parseItem(it.asObject()) }
            val header = card["header"].asObject()?.get("musicCardShelfHeaderBasicRenderer").asObject()
            val title = header?.get("title").asObject().runText() ?: parent?.title
            if (items.isNotEmpty()) {
                sections +=
                    HomeSection(
                        title = title.orEmpty(),
                        items = items,
                        subtitle = header?.get("strapline").asObject().runText(),
                        browseId = endpoint.browseId(),
                        params = endpoint.browseParams(),
                        continuation = card.continuationToken() ?: contents.findContinuationToken(),
                        hasTitle = title != null,
                        thumbnail = header?.thumbnailUrl(),
                    )
            }
            return
        }

        val isListStyle = shelfObj.containsKey("musicShelfRenderer") || shelfObj.containsKey("musicPlaylistShelfRenderer")
        val shelf =
            shelfObj["musicShelfRenderer"]?.jsonObject
                ?: shelfObj["musicCarouselShelfRenderer"]?.jsonObject
                ?: shelfObj["musicImmersiveCarouselShelfRenderer"]?.jsonObject
                ?: shelfObj["musicPlaylistShelfRenderer"]?.jsonObject
                ?: shelfObj["gridRenderer"]?.jsonObject
                ?: shelfObj["musicCategoryListRenderer"]?.jsonObject

        if (shelf != null) {
            val header = shelf.carouselHeader()
            val title =
                shelf["title"].asObject().runText()
                    ?: header?.get("title").asObject().runText()
                    ?: shelf["header"]
                        .asObject()
                        ?.get("gridHeaderRenderer")
                        .asObject()
                        ?.get("title")
                        .asObject()
                        .runText()
            val subtitle = header?.get("strapline").asObject().runText()

            val endpoint = shelf.shelfNavigationEndpoint()

            val browseId = endpoint.browseId()
            val sectionParams = endpoint.browseParams()

            val itemsArray = shelf["contents"]?.jsonArray ?: shelf["items"]?.jsonArray
            val shelfContinuation = shelf.continuationToken() ?: itemsArray?.findContinuationToken()
            val items =
                itemsArray?.mapNotNull { item ->
                    val itemObj = item.jsonObject
                    parseItem(
                        itemObj["musicResponsiveListItemRenderer"]?.jsonObject
                            ?: itemObj["musicTwoRowItemRenderer"]?.jsonObject
                            ?: itemObj["musicNavigationButtonRenderer"]?.jsonObject
                            ?: itemObj["musicCategoryListItemRenderer"]?.jsonObject
                            ?: itemObj,
                    )
                } ?: emptyList()

            if (items.isNotEmpty()) {
                sections.add(
                    HomeSection(
                        title.orEmpty(),
                        items,
                        subtitle,
                        browseId,
                        sectionParams,
                        isListStyle,
                        isCategoryComponent = shelfObj.containsKey("musicCategoryListRenderer"),
                        isMultiRow =
                            shelf["numItemsPerColumn"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toIntOrNull()
                                ?.let { it > 1 } == true,
                        numItemsPerColumn =
                            shelf["numItemsPerColumn"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toIntOrNull()
                                ?.takeIf { it > 0 },
                        isResponsiveList =
                            !isListStyle && itemsArray?.any {
                                it.asObject()?.containsKey(
                                    "musicResponsiveListItemRenderer",
                                ) == true
                            } == true,
                        continuation = shelfContinuation,
                        hasTitle = title != null,
                        isPersonal = isPersonal && isListStyle,
                        thumbnail = header?.thumbnailUrl(),
                        headerBrowseId =
                            header
                                ?.get("title")
                                ?.jsonObject
                                ?.get("runs")
                                ?.jsonArray
                                ?.firstOrNull()
                                ?.jsonObject
                                ?.get("navigationEndpoint")
                                ?.jsonObject
                                .browseId(),
                    ),
                )
            }
        }
    }

    internal data class ParsedBrowse(
        val sections: Map<String, List<YTItem>>,
        val continuation: String?,
        val continuationAmbiguous: Boolean = false,
        val continuationBranches: Set<ContinuationBranch> = emptySet(),
        val sectionBrowseIds: Map<String, Pair<String?, String?>> = emptyMap(),
    )

    internal fun parseBrowseResponse(
        response: BrowseResponse,
        browseIds: MutableMap<String, Pair<String?, String?>>? = null,
        untitledShelfTitle: String? = "Tracks",
    ): ParsedBrowse {
        val sections = mutableMapOf<String, List<YTItem>>()
        val continuationTokens = mutableSetOf<String>()
        val continuationBranches = linkedMapOf<String, ContinuationBranch>()

        fun recordContinuation(
            element: JsonElement?,
            sectionTitle: String? = null,
        ) {
            // Header menus and track actions can carry tokens for other endpoints.
            val container = element.asObject()
            val shelf =
                container?.get("musicShelfRenderer").asObject()
                    ?: container?.get("musicCarouselShelfRenderer").asObject()
                    ?: container?.get("musicPlaylistShelfRenderer").asObject()
                    ?: container?.get("gridRenderer").asObject()
                    ?: container
            val sources =
                if (element is JsonArray) {
                    element.mapNotNull { item ->
                        val value = item.asObject()
                        if (value?.containsKey("nextContinuationData") == true) value else value?.get("continuationItemRenderer")
                    }
                } else {
                    listOfNotNull(shelf?.get("continuations"), shelf?.get("continuationItemRenderer")) +
                        (shelf?.get("contents").asArray() ?: shelf?.get("items").asArray()).orEmpty().mapNotNull {
                            it.asObject()?.get("continuationItemRenderer")
                        }
                }
            sources.flatMap { it.findContinuationTokens() }.forEach { token ->
                continuationTokens += token
                if (token !in continuationBranches) {
                    continuationBranches[token] = ContinuationBranch(token, sectionTitle)
                }
            }
        }

        fun recordContinuation(
            token: String?,
            sectionTitle: String? = null,
        ) {
            token?.takeIf(String::isNotBlank)?.let { normalizedToken ->
                continuationTokens += normalizedToken
                if (normalizedToken !in continuationBranches) {
                    continuationBranches[normalizedToken] = ContinuationBranch(normalizedToken, sectionTitle)
                }
            }
        }

        fun processContinuationItems(continuationItems: JsonArray) {
            val items =
                continuationItems.mapNotNull { element ->
                    val item = element.jsonObject
                    val renderer =
                        item["musicResponsiveListItemRenderer"]?.jsonObject
                            ?: item["musicTwoRowItemRenderer"]?.jsonObject
                            ?: item["musicNavigationButtonRenderer"]?.jsonObject
                            ?: item
                    parseItem(renderer)
                }
            if (items.isNotEmpty()) {
                sections["Continuation"] = sections["Continuation"].orEmpty() + items
            }
            recordContinuation(continuationItems)
        }

        fun processContinuationShelf(shelf: JsonObject) {
            val itemsArray = shelf["contents"]?.jsonArray ?: shelf["items"]?.jsonArray
            val sectionTitle = shelf.shelfTitle(default = "Continuation")
            if (itemsArray != null) {
                val items = itemsArray.mapNotNull { parseItem(it.jsonObject) }
                if (items.isNotEmpty()) {
                    sections[sectionTitle] = sections[sectionTitle].orEmpty() + items
                }
            }
            recordContinuation(shelf, sectionTitle.takeUnless { it == "Continuation" })
        }

        val contents = response.contents
        val primarySectionList =
            contents
                ?.singleColumnBrowseResultsRenderer
                ?.let { it["tabs"]?.jsonArray?.getOrNull(0)?.jsonObject }
                ?.get("tabRenderer")
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject
                ?: contents
                    ?.twoColumnBrowseResultsRenderer
                    ?.let { it["tabs"]?.jsonArray?.getOrNull(0)?.jsonObject }
                    ?.get("tabRenderer")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonObject
                    ?.get("sectionListRenderer")
                    ?.jsonObject
                ?: contents?.sectionListRenderer
        val secondarySectionList =
            contents
                ?.twoColumnBrowseResultsRenderer
                ?.get("secondaryContents")
                ?.jsonObject
                ?.get("sectionListRenderer")
                ?.jsonObject

        listOfNotNull(primarySectionList, secondarySectionList, contents?.sectionListRenderer)
            .distinct()
            .forEach { sectionList ->
                sectionList["contents"]?.jsonArray?.forEach { sectionElement ->
                    val sectionObj = sectionElement.jsonObject
                    if (sectionObj.containsKey("itemSectionRenderer")) {
                        val itemSection = sectionObj["itemSectionRenderer"]?.jsonObject
                        val sectionTitle =
                            itemSection
                                ?.get(
                                    "header",
                                )?.jsonObject
                                ?.get(
                                    "itemSectionTabbedHeaderRenderer",
                                )?.jsonObject
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
                                ?: ""
                        val itemContents = mutableListOf<YTItem>()
                        itemSection?.get("contents")?.jsonArray?.forEach { subElement ->
                            val subObj = subElement.jsonObject
                            val tempSections = mutableMapOf<String, List<YTItem>>()
                            processShelf(subObj, tempSections, browseIds, untitledShelfTitle)
                            recordContinuation(subObj, subObj.continuationSectionTitle())
                            if (tempSections.isNotEmpty()) {
                                tempSections.forEach { (title, items) ->
                                    sections[title] = sections[title].orEmpty() + items
                                }
                            } else {
                                parseItem(subObj)?.let(itemContents::add)
                            }
                        }
                        if (itemContents.isNotEmpty() && sectionTitle.isNotEmpty()) {
                            sections[sectionTitle] = sections[sectionTitle].orEmpty() + itemContents
                        }
                    } else {
                        processShelf(sectionObj, sections, browseIds, untitledShelfTitle)
                        recordContinuation(sectionObj, sectionObj.continuationSectionTitle())
                    }
                }

                recordContinuation(sectionList["continuations"])
            }

        response.continuationContents?.let { cont ->
            listOfNotNull(
                cont.musicShelfContinuation,
                cont.musicPlaylistShelfContinuation,
                cont.gridContinuation,
            ).forEach(::processContinuationShelf)

            cont.sectionListContinuation?.let { sectionList ->
                sectionList["contents"]?.jsonArray?.forEach { element ->
                    val section = element.jsonObject
                    val itemSection = section["itemSectionRenderer"]?.jsonObject
                    if (itemSection != null) {
                        itemSection["contents"]?.jsonArray?.forEach { child ->
                            val childObject = child.jsonObject
                            val before = sections.values.sumOf { it.size }
                            processShelf(childObject, sections, browseIds, defaultTitle = "Continuation")
                            recordContinuation(childObject, childObject.continuationSectionTitle())
                            if (sections.values.sumOf { it.size } == before) {
                                parseItem(childObject)?.let { item ->
                                    sections["Continuation"] = sections["Continuation"].orEmpty() + item
                                }
                            }
                        }
                    } else {
                        val before = sections.values.sumOf { it.size }
                        processShelf(section, sections, browseIds, defaultTitle = "Continuation")
                        recordContinuation(section, section.continuationSectionTitle())
                        if (sections.values.sumOf { it.size } == before) {
                            parseItem(section)?.let { item ->
                                sections["Continuation"] = sections["Continuation"].orEmpty() + item
                            }
                        }
                    }
                }
                recordContinuation(sectionList["continuations"])
            }
        }

        listOfNotNull(
            response.onResponseReceivedActions,
            response.onResponseReceivedCommands,
            response.onResponseReceivedEndpoints,
        ).forEach { actions ->
            actions.forEach { element ->
                val action = element.jsonObject
                val continuationItems =
                    action["appendContinuationItemsAction"]
                        ?.jsonObject
                        ?.get("continuationItems")
                        ?.jsonArray
                        ?: action["reloadContinuationItemsCommand"]
                            ?.jsonObject
                            ?.get("continuationItems")
                            ?.jsonArray
                        ?: action["appendContinuationItemsCommand"]
                            ?.jsonObject
                            ?.get("continuationItems")
                            ?.jsonArray
                continuationItems?.let(::processContinuationItems)
            }
        }

        return ParsedBrowse(
            sections = sections,
            continuation = continuationTokens.singleOrNull(),
            continuationAmbiguous = continuationTokens.size > 1,
            continuationBranches = continuationBranches.values.toSet(),
            sectionBrowseIds = browseIds ?: emptyMap(),
        )
    }

    private fun processShelf(
        shelfObj: JsonObject,
        sections: MutableMap<String, List<YTItem>>,
        browseIds: MutableMap<String, Pair<String?, String?>>? = null,
        defaultTitle: String? = "Tracks",
    ) {
        val releaseCard = shelfObj["musicCardShelfRenderer"]?.jsonObject
        if (releaseCard != null) {
            val release =
                parseItem(
                    buildJsonObject {
                        releaseCard.forEach { (key, value) -> put(key, value) }
                        releaseCard["onTap"]?.let { put("navigationEndpoint", it) }
                    },
                )
            if (release is AlbumItem) sections["latest_release"] = listOf(release)
            return
        }
        val shelf =
            shelfObj["musicShelfRenderer"]?.jsonObject
                ?: shelfObj["musicCarouselShelfRenderer"]?.jsonObject
                ?: shelfObj["musicPlaylistShelfRenderer"]?.jsonObject
                ?: shelfObj["gridRenderer"]?.jsonObject
                ?: return

        // A null default drops untitled shelves, such as artist-page promo cards.
        val title = defaultTitle?.let { shelf.shelfTitle(it) } ?: shelf.shelfTitle(default = "").ifEmpty { return }

        if (browseIds != null) {
            val endpoint = shelf.shelfNavigationEndpoint()
            val browseId = endpoint.browseId()
            val params = endpoint.browseParams()
            if (browseId != null) browseIds[title] = Pair(browseId, params)
        }

        val itemsArray = shelf["contents"]?.jsonArray ?: shelf["items"]?.jsonArray
        val items =
            itemsArray?.mapNotNull { item ->
                val itemObj = item.jsonObject
                parseItem(
                    itemObj["musicResponsiveListItemRenderer"]?.jsonObject
                        ?: itemObj["musicTwoRowItemRenderer"]?.jsonObject
                        ?: itemObj["musicNavigationButtonRenderer"]?.jsonObject
                        ?: itemObj,
                )
            } ?: emptyList()

        if (items.isNotEmpty()) {
            val existing = sections[title] ?: emptyList()
            sections[title] = existing + items
        }
    }

    private fun JsonObject.continuationSectionTitle(): String? {
        val shelf =
            this["musicShelfRenderer"]?.jsonObject
                ?: this["musicCarouselShelfRenderer"]?.jsonObject
                ?: this["musicPlaylistShelfRenderer"]?.jsonObject
                ?: this["gridRenderer"]?.jsonObject
                ?: return null
        return shelf.shelfTitle(default = "Continuation").takeUnless { it == "Continuation" }
    }

    private fun JsonObject.shelfTitle(default: String): String =
        this["title"].asObject().runText()
            ?: this["header"]
                .asObject()
                ?.get("musicCarouselShelfBasicHeaderRenderer")
                .asObject()
                ?.get("title")
                .asObject()
                .runText()
            ?: this["header"]
                .asObject()
                ?.get("gridHeaderRenderer")
                .asObject()
                ?.get("title")
                .asObject()
                .runText()
            ?: default

    internal fun parseItem(item: JsonObject?): YTItem? = YouTubeItemParser.parseItem(item)
}

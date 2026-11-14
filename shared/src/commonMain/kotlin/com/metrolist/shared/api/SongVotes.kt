package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.shared.api.YouTubeResponseParser.asObject
import com.metrolist.shared.api.YouTubeResponseParser.findAllObjects
import com.metrolist.shared.api.YouTubeResponseParser.primitiveText
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

private class RowVoteContext(
    val id: String?,
    val ordinal: Int,
    val parent: RowVoteContext?,
)

internal fun parseSongVotes(response: JsonObject): Map<String, SongVote> {
    val explicitVotes = linkedMapOf<String, SongVote>()
    val rowVotes = linkedMapOf<String, Pair<Int, SongVote>>()
    var rowOrdinal = 0

    fun firstWatchEndpoint(element: JsonElement?): JsonObject? =
        when (element) {
            is JsonObject -> element["watchEndpoint"].asObject() ?: element.values.firstNotNullOfOrNull(::firstWatchEndpoint)
            is JsonArray -> element.firstNotNullOfOrNull(::firstWatchEndpoint)
            else -> null
        }

    fun record(
        button: JsonObject,
        context: RowVoteContext?,
    ) {
        val target = button["target"].asObject()
        val explicitId = target?.get("videoId").primitiveText()
        val status = button["likeStatus"].primitiveText() ?: return
        val vote = SongVote.entries.firstOrNull { it.name == status } ?: return
        if (!explicitId.isNullOrBlank()) explicitVotes[explicitId] = vote
        var menu = context
        while (menu != null) {
            val id = if (target == null) menu.id else explicitId
            if (!id.isNullOrBlank() && menu.ordinal >= (rowVotes[id]?.first ?: 0)) {
                rowVotes[id] = menu.ordinal to vote
            }
            menu = menu.parent
        }
    }

    fun visit(
        element: JsonElement,
        context: RowVoteContext? = null,
        ownRowOrdinal: Int = 0,
    ) {
        when (element) {
            is JsonObject -> {
                element["likeButtonRenderer"].asObject()?.let { record(it, context) }
                val nestedRow = element["musicResponsiveListItemRenderer"].asObject()
                val nestedOrdinal = if (nestedRow != null) ++rowOrdinal else 0
                val ownContext =
                    if (ownRowOrdinal == 0) {
                        context
                    } else {
                        val id =
                            element["playlistItemData"].asObject()?.get("videoId").primitiveText()
                                ?: firstWatchEndpoint(element["overlay"])?.get("videoId").primitiveText()
                        RowVoteContext(id, ownRowOrdinal, context)
                    }
                for ((key, child) in element) {
                    visit(
                        child,
                        if (key == "menu") ownContext else context,
                        if (key == "musicResponsiveListItemRenderer") nestedOrdinal else 0,
                    )
                }
            }

            is JsonArray -> {
                element.forEach { visit(it, context) }
            }

            else -> {}
        }
    }

    visit(response)
    rowVotes.forEach { (id, observation) -> explicitVotes[id] = observation.second }
    return explicitVotes
}

internal fun reconcileSongVotes(
    response: JsonObject,
    revision: Long,
) {
    val votes = parseSongVotes(response)
    if (votes.isEmpty()) return
    val dislikedSongs =
        if (SongVote.DISLIKE in votes.values) {
            listOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicMultiRowListItemRenderer")
                .asSequence()
                .flatMap { response.findAllObjects(it).asSequence() }
                .mapNotNull { YouTubeItemParser.parseItem(it) as? SongItem }
                .filter { votes[it.id] == SongVote.DISLIKE }
                .associateBy(SongItem::id)
        } else {
            emptyMap()
        }
    SongLikeState.reconcileRemoteVotes(votes, revision, dislikedSongs)
}

internal suspend fun HttpResponse.browseResponseWithVotes(
    innerTube: InnerTube,
    session: InnerTube.SessionSnapshot,
    revision: Long,
    authenticated: Boolean = true,
    allowVisitorBootstrap: Boolean = false,
): BrowseResponse =
    withContext(responseDecodeDispatcher) {
        val raw = bodyLimited<JsonObject>(FEED_RESPONSE_LIMIT)
        if (innerTube.sessionSnapshot() != session && !(allowVisitorBootstrap && innerTube.acceptsHomeVisitorBootstrap(session))) {
            throw CancellationException("YouTube session changed")
        }
        if (authenticated && innerTube.hasSapCookieAuth(session)) {
            reconcileSongVotes(raw, revision)
        }
        repositoryJson.decodeFromJsonElement<BrowseResponse>(raw)
    }

internal fun InnerTube.acceptsHomeVisitorBootstrap(session: InnerTube.SessionSnapshot): Boolean {
    if (!session.regionOverrideActive || session.visitorData != null) return false
    val visitor = sessionSnapshot().visitorData ?: return false
    return sessionSnapshotWithVisitorData(session, visitor) != null
}

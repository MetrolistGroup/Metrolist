package com.metrolist.shared.api

import com.metrolist.innertubex.models.response.BrowseResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class RendererFlags(
    var explicit: Boolean = false,
    var newRelease: Boolean = false,
    var podcastEpisode: Boolean = false,
    var editable: Boolean = false,
)

internal fun JsonElement.rendererFlags(): RendererFlags {
    val flags = RendererFlags()
    anyJsonText { text ->
        flags.explicit = flags.explicit || text.contains("MUSIC_EXPLICIT_BADGE")
        flags.newRelease = flags.newRelease || text.contains("MUSIC_NEW_RELEASE_BADGE")
        flags.podcastEpisode =
            flags.podcastEpisode || text.contains("PODCAST_EPISODE") || text == "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
        flags.editable = flags.editable || text.contains("editPlaylistEndpoint")
        flags.explicit && flags.newRelease && flags.podcastEpisode && flags.editable
    }
    return flags
}

// Inspect both keys and values to retain support for unfamiliar renderer envelopes.
internal fun JsonElement.anyJsonText(predicate: (String) -> Boolean): Boolean =
    when (this) {
        is JsonObject -> entries.any { (key, value) -> predicate(key) || value.anyJsonText(predicate) }
        is JsonArray -> any { it.anyJsonText(predicate) }
        is JsonPrimitive -> predicate(content)
    }

internal fun JsonElement.hasPlaylistEditControls(): Boolean = anyJsonText { it.hasPlaylistEditControls() }

internal fun BrowseResponse.hasPlaylistEditControls(): Boolean =
    header?.musicEditablePlaylistDetailHeaderRenderer != null ||
        listOf(
            header?.musicImmersiveHeaderRenderer,
            header?.musicDetailHeaderRenderer,
            header?.musicResponsiveHeaderRenderer,
            header?.musicVisualHeaderRenderer,
            header?.musicHeaderRenderer,
            contents?.singleColumnBrowseResultsRenderer,
            contents?.twoColumnBrowseResultsRenderer,
            contents?.sectionListRenderer,
            continuationContents?.sectionListContinuation,
            continuationContents?.musicPlaylistShelfContinuation,
            continuationContents?.gridContinuation,
            continuationContents?.musicShelfContinuation,
            microformat,
            onResponseReceivedActions,
            onResponseReceivedCommands,
            onResponseReceivedEndpoints,
            background,
        ).any { it?.hasPlaylistEditControls() == true }

private fun String.hasPlaylistEditControls(): Boolean =
    contains("musicEditablePlaylistDetailHeaderRenderer") ||
        contains("editPlaylistEndpoint") ||
        contains("ACTION_MOVE_VIDEO_BEFORE") ||
        contains("ACTION_REMOVE_VIDEO")

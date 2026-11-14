package meow.nyx.spotlib

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object Queries {
    const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
    const val LIBRARY_V3_HASH = "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
    const val PLAYLIST_CONTENTS_HASH = "e4b2953f160e58e38ac025d79b5a9b3aceee5c4c716598e9830bfceb69faff5f"
    const val LIBRARY_TRACKS_HASH = "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240"
    const val PROFILE_ATTRIBUTES_HASH = "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0"
    const val SEARCH_SUGGESTIONS_HASH = "b50ebd72524415b132ddaca04158fd7aca529da28be322c9924643c0633df5bd"

    val LIBRARY_FEATURES =
        listOf(
            "LIKED_SONGS",
            "YOUR_EPISODES_V2",
            "PRERELEASES",
            "PRERELEASES_V2",
            "CLIPS",
            "EVENTS",
        )
}

private fun JsonObject?.string(key: String): String? = this?.get(key)?.jsonPrimitive?.content

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.array(key: String): JsonArray = this?.get(key) as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject?.objects(key: String): List<JsonObject> =
    (this?.get(key) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

internal data class LibraryPage(
    val playlists: List<PlaylistMeta> = emptyList(),
    val folders: List<Pair<String, String>> = emptyList(),
    val likedSongs: PlaylistMeta? = null,
    val totalCount: Int = 0,
)

internal fun parseLibraryPage(
    page: JsonObject,
    folderPath: List<String>,
): LibraryPage {
    val library = page.obj("data")?.obj("me")?.obj("libraryV3") ?: return LibraryPage()
    val playlists = ArrayList<PlaylistMeta>()
    val folders = ArrayList<Pair<String, String>>()
    var likedSongs: PlaylistMeta? = null

    for (entry in library.array("items")) {
        val wrapper = (entry as? JsonObject)?.obj("item") ?: continue
        val data = wrapper.obj("data")
        val type = wrapper.string("__typename").orEmpty()
        val uri = data?.string("uri") ?: wrapper.string("_uri") ?: continue
        val name = data?.string("name") ?: uri
        when (type) {
            "LibraryFolderResponseWrapper" -> {
                folders.add(uri to name)
            }

            "PlaylistResponseWrapper" -> {
                val image =
                    data
                        ?.obj("images")
                        ?.objects("items")
                        ?.firstOrNull()
                        ?.objects("sources")
                        ?.firstOrNull()
                        ?.string("url")
                playlists.add(
                    PlaylistMeta(
                        uri = uri,
                        name = name,
                        owner = data?.obj("ownerV2")?.obj("data")?.string("name"),
                        description = data?.string("description").orEmpty(),
                        image = image,
                        folderPath = folderPath,
                    ),
                )
            }

            "LibraryPseudoPlaylistResponseWrapper" -> {
                if (uri == "spotify:collection:tracks") {
                    likedSongs =
                        PlaylistMeta(
                            uri = uri,
                            name = name,
                            folderPath = folderPath,
                            source = PlaylistSource.LIKED_SONGS,
                            trackCount =
                                data
                                    ?.get("count")
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.toIntOrNull() ?: 0,
                        )
                }
            }
        }
    }
    return LibraryPage(
        playlists,
        folders,
        likedSongs,
        library["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: playlists.size,
    )
}

internal fun parseTrackItem(
    entry: JsonObject,
    position: Int,
): TrackItem {
    val wrapper = entry.obj("itemV2") ?: entry.obj("track") ?: entry
    val node = wrapper.obj("data") ?: wrapper
    val albumNode = node.obj("albumOfTrack")
    val durationRaw = node["duration"]
    val durationMs =
        when (durationRaw) {
            is JsonObject -> durationRaw["totalMilliseconds"]?.jsonPrimitive?.content?.toLongOrNull()
            null -> node.obj("trackDuration")?.string("totalMilliseconds")?.toLongOrNull()
            else -> durationRaw.jsonPrimitive.content.toLongOrNull()
        }
    val playable =
        node
            .obj("playability")
            ?.get("playable")
            ?.jsonPrimitive
            ?.content
            ?.toBooleanStrictOrNull()
            ?: node["isPlayable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
    val uri = node.string("uri") ?: wrapper.string("_uri") ?: wrapper.string("uri").orEmpty()
    return TrackItem(
        uri = uri,
        name = node.string("name").orEmpty(),
        type = node.string("__typename") ?: "Track",
        artists =
            node
                .obj("artists")
                ?.objects("items")
                ?.mapNotNull { it.obj("profile")?.string("name") }
                .orEmpty(),
        album = albumNode?.string("name"),
        albumUri = albumNode?.string("uri"),
        albumArtists =
            albumNode
                ?.obj("artists")
                ?.objects("items")
                ?.mapNotNull { it.obj("profile")?.string("name") }
                .orEmpty(),
        releaseDate = albumNode?.obj("date")?.string("isoString"),
        durationMs = durationMs,
        trackNumber = node["trackNumber"]?.jsonPrimitive?.content?.toIntOrNull(),
        discNumber = node["discNumber"]?.jsonPrimitive?.content?.toIntOrNull(),
        explicit = node.obj("contentRating")?.string("label") == "EXPLICIT",
        isLocal = uri.startsWith("spotify:local:"),
        isPlayable = playable,
        addedAt = entry.obj("addedAt")?.string("isoString"),
        addedBy = entry.obj("addedBy")?.obj("data")?.string("name") ?: entry.obj("addedBy")?.obj("data")?.string("username"),
        position = position,
    )
}

internal fun parseLikedSongsPage(page: JsonObject): Pair<List<TrackItem>, Int> {
    val tracks =
        page
            .obj("data")
            ?.obj("me")
            ?.obj("library")
            ?.obj("tracks") ?: return emptyList<TrackItem>() to 0
    val items =
        tracks.array("items").mapIndexedNotNull { index, item ->
            (item as? JsonObject)?.let { parseTrackItem(it, index + 1) }
        }
    return items to (tracks["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: items.size)
}

internal fun parsePlaylistContentsPage(page: JsonObject): Pair<List<TrackItem>, Int> {
    val content = page.obj("data")?.obj("playlistV2")?.obj("content") ?: return emptyList<TrackItem>() to 0
    val items =
        content.array("items").mapIndexedNotNull { index, item ->
            (item as? JsonObject)?.let { parseTrackItem(it, index + 1) }
        }
    return items to (content["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: items.size)
}

internal fun parseProfile(page: JsonObject): AccountProfile {
    val profile = page.obj("data")?.obj("me")?.obj("profile") ?: return AccountProfile()
    return AccountProfile(
        name = profile.string("name"),
        username = profile.string("username"),
        uri = profile.string("uri"),
        avatar = profile.string("avatar"),
    )
}

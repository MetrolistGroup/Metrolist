package com.metrolist.shared.settings

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object HiddenLibraryItems {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private val store =
        HiddenLibraryItemStore(
            read = { Settings.shared.hiddenLibraryPlaylistsJson },
            write = { Settings.shared.hiddenLibraryPlaylistsJson = it },
        )

    fun all(): List<HiddenLibraryItem> = store.all()

    fun keys(): Set<HiddenLibraryItemKey> = store.keys()

    fun isHidden(item: YTItem): Boolean = store.isHidden(HiddenLibraryItem.from(item).key)

    fun hide(item: YTItem) {
        store.hide(HiddenLibraryItem.from(item))
        _version.value++
    }

    fun hide(item: HiddenLibraryItem) {
        store.hide(item)
        _version.value++
    }

    fun restore(key: HiddenLibraryItemKey) {
        store.restore(key)
        _version.value++
    }
}

class HiddenLibraryItemStore(
    private val read: () -> String,
    private val write: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<HiddenLibraryItem> =
        try {
            json
                .decodeFromString<List<HiddenLibraryItem>>(read())
                .map(HiddenLibraryItem::normalized)
                .distinctBy(HiddenLibraryItem::key)
        } catch (_: Exception) {
            emptyList()
        }

    fun keys(): Set<HiddenLibraryItemKey> = all().mapTo(mutableSetOf(), HiddenLibraryItem::key)

    fun isHidden(key: HiddenLibraryItemKey): Boolean = key.normalized() in keys()

    fun hide(item: HiddenLibraryItem) {
        val normalized = item.normalized()
        write(json.encodeToString(all().filterNot { it.key == normalized.key } + normalized))
    }

    fun restore(key: HiddenLibraryItemKey) {
        val normalized = key.normalized()
        write(json.encodeToString(all().filterNot { it.key == normalized }))
    }
}

@Serializable
data class HiddenLibraryItem(
    val type: HiddenLibraryItemType = HiddenLibraryItemType.PLAYLIST,
    val id: String,
    val title: String,
    val thumbnail: String? = null,
    val author: String? = null,
    val subtitle: String? = null,
) {
    val key: HiddenLibraryItemKey get() = HiddenLibraryItemKey(type, normalizedId(type, id))

    fun normalized(): HiddenLibraryItem = copy(id = normalizedId(type, id))

    companion object {
        fun from(item: YTItem): HiddenLibraryItem =
            HiddenLibraryItem(
                type =
                    when (item) {
                        is PlaylistItem -> HiddenLibraryItemType.PLAYLIST
                        is AlbumItem -> HiddenLibraryItemType.ALBUM
                        is ArtistItem -> HiddenLibraryItemType.ARTIST
                        is PodcastItem -> HiddenLibraryItemType.PODCAST
                        else -> HiddenLibraryItemType.OTHER
                    },
                id = item.id,
                title = item.title,
                thumbnail = item.thumbnail,
                author =
                    when (item) {
                        is PlaylistItem -> item.author
                        is PodcastItem -> item.author
                        is AlbumItem -> item.artists.joinToString { it.name }.ifBlank { null }
                        else -> null
                    },
            ).normalized()
    }
}

data class HiddenLibraryItemKey(
    val type: HiddenLibraryItemType,
    val id: String,
) {
    fun normalized(): HiddenLibraryItemKey = copy(id = normalizedId(type, id))
}

@Serializable
enum class HiddenLibraryItemType {
    @SerialName("auto")
    AUTO,

    @SerialName("playlist")
    PLAYLIST,

    @SerialName("album")
    ALBUM,

    @SerialName("artist")
    ARTIST,

    @SerialName("podcast")
    PODCAST,

    @SerialName("other")
    OTHER,
}

private fun normalizedId(
    type: HiddenLibraryItemType,
    id: String,
): String = if (type == HiddenLibraryItemType.PLAYLIST) id.removePrefix("VL") else id

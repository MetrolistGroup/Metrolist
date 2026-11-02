package com.metrolist.music.utils

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.PersistQueue
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.time.LocalDateTime

internal class QueueObjectInputStream(input: InputStream) : ObjectInputStream(input) {
    init {
        enableResolveObject(true)
    }

    override fun readClassDescriptor(): ObjectStreamClass {
        val stored = super.readClassDescriptor()
        val model = when {
            stored.name == PersistQueue::class.java.name && stored.serialVersionUID == 5403708039420159258L ->
                PersistQueue::class.java
            stored.name == MediaMetadata::class.java.name && stored.serialVersionUID == -6838280725963291378L ->
                MediaMetadata::class.java
            stored.name == MediaMetadata::class.java.name && stored.serialVersionUID == -1021980328297695771L ->
                LegacyPlaylistMediaMetadata::class.java
            else -> return stored
        }
        val current = ObjectStreamClass.lookup(model)
        // A descriptor swap is safe only for the same wire layout, including reference types.
        val sameFields = stored.fields.size == current.fields.size && stored.fields.zip(current.fields).all { (old, new) ->
            old.name == new.name && old.typeCode == new.typeCode && old.typeString == new.typeString
        }
        return if (sameFields) current else stored
    }

    override fun resolveObject(obj: Any?): Any? =
        if (obj is LegacyPlaylistMediaMetadata) obj.toCurrent() else obj
}

// Keep the exact serialized fields of the intermediate build; adding fields here would corrupt its wire layout.
internal class LegacyPlaylistMediaMetadata(
    val id: String,
    val title: String,
    val artists: List<MediaMetadata.Artist>,
    val duration: Int,
    val thumbnailUrl: String?,
    val album: MediaMetadata.Album?,
    val setVideoId: String?,
    val musicVideoType: String?,
    val explicit: Boolean,
    val liked: Boolean,
    val likedDate: LocalDateTime?,
    val inLibrary: LocalDateTime?,
    val libraryAddToken: String?,
    val libraryRemoveToken: String?,
    val suggestedBy: String?,
    val isEpisode: Boolean,
    val uploadEntityId: String?,
    val playlistBrowseId: String?,
) : Serializable {
    fun toCurrent() = MediaMetadata(
        id = id, title = title, artists = artists, duration = duration,
        thumbnailUrl = thumbnailUrl, album = album, setVideoId = setVideoId,
        musicVideoType = musicVideoType, explicit = explicit, liked = liked,
        likedDate = likedDate, inLibrary = inLibrary, libraryAddToken = libraryAddToken,
        libraryRemoveToken = libraryRemoveToken, suggestedBy = suggestedBy,
        isEpisode = isEpisode, uploadEntityId = uploadEntityId, playlistBrowseId = playlistBrowseId,
    )
}

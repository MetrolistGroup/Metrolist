package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class EditPlaylistBody(
    val context: Context,
    val playlistId: String,
    val actions: List<Action>
)

@Serializable
sealed class Action {
    @Serializable
    data class AddVideoAction(
        val action: String = "ACTION_ADD_VIDEO",
        val addedVideoId: String
    ) : Action()

    @Serializable
    data class AddPlaylistAction(
        val action: String = "ACTION_ADD_PLAYLIST",
        val addedFullListId: String
    ) : Action()

    @Serializable
    data class MoveVideoAction(
        val action: String = "ACTION_MOVE_VIDEO_BEFORE",
        val setVideoId: String,
        val movedSetVideoIdSuccessor: String?
    ) : Action()

    @Serializable
    data class RemoveVideoAction(
        val action: String = "ACTION_REMOVE_VIDEO",
        val setVideoId: String,
        val removedVideoId: String
    ) : Action()

    @Serializable
    data class RenamePlaylistAction(
        val action: String = "ACTION_SET_PLAYLIST_NAME",
        val playlistName: String
    ) : Action()

    @Serializable
    data class SetCustomThumbnailAction(
        val action: String = "ACTION_SET_CUSTOM_THUMBNAIL",
        val addedCustomThumbnail: AddedCustomThumbnail
    ) : Action() {
        @Serializable
        data class AddedCustomThumbnail(
            val imageKey: ImageKey = ImageKey(
                name = "studio_square_thumbnail",
                type = "PLAYLIST_IMAGE_TYPE_CUSTOM_THUMBNAIL"
            ),
            val playlistScottyEncryptedBlobId: String
        ) {
            @Serializable
            data class ImageKey(
                val name: String,
                val type: String
            )
        }
    }

    @Serializable
    data class RemoveCustomThumbnailAction(
        val action: String = "ACTION_REMOVE_CUSTOM_THUMBNAIL",
        val deletedCustomThumbnail: DeletedCustomThumbnail = DeletedCustomThumbnail()
    ) : Action() {
        @Serializable
        data class DeletedCustomThumbnail(
            val name: String = "studio_square_thumbnail",
            val type: String = "PLAYLIST_IMAGE_TYPE_CUSTOM_THUMBNAIL"
        )
    }
}

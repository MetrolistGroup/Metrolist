package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class NavigationEndpoint(
    val watchEndpoint: WatchEndpoint? = null,
    val watchPlaylistEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val searchEndpoint: SearchEndpoint? = null,
    val queueAddEndpoint: QueueAddEndpoint? = null,
    val shareEntityEndpoint: ShareEntityEndpoint? = null,
    val feedbackEndpoint: FeedbackEndpoint? = null,
    val urlEndpoint: UrlEndpoint? = null,
    val deletePrivatelyOwnedEntityCommand: DeletePrivatelyOwnedEntityCommand? = null,
    val confirmDialogEndpoint: ConfirmDialogEndpoint? = null,
) {
    @Serializable
    data class DeletePrivatelyOwnedEntityCommand(
        val entityId: String,
    )

    @Serializable
    data class ConfirmDialogEndpoint(
        val content: ConfirmDialogContent? = null,
    ) {
        @Serializable
        data class ConfirmDialogContent(
            val confirmDialogRenderer: ConfirmDialogRenderer? = null,
        )

        @Serializable
        data class ConfirmDialogRenderer(
            val confirmButton: ConfirmButton? = null,
        )

        @Serializable
        data class ConfirmButton(
            val buttonRenderer: ConfirmButtonRenderer? = null,
        )

        @Serializable
        data class ConfirmButtonRenderer(
            val command: ConfirmCommand? = null,
        )

        @Serializable
        data class ConfirmCommand(
            val musicDeletePrivatelyOwnedEntityCommand: DeletePrivatelyOwnedEntityCommand? = null,
        )
    }
    val endpoint: Endpoint?
        get() =
            watchEndpoint
                ?: watchPlaylistEndpoint
                ?: browseEndpoint
                ?: searchEndpoint
                ?: queueAddEndpoint
                ?: shareEntityEndpoint

    val anyWatchEndpoint: WatchEndpoint?
        get() = watchEndpoint
            ?: watchPlaylistEndpoint

    val musicVideoType: String?
        get() = anyWatchEndpoint
            ?.watchEndpointMusicSupportedConfigs
            ?.watchEndpointMusicConfig
            ?.musicVideoType
}

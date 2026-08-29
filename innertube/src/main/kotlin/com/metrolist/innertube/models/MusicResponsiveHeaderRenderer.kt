package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicResponsiveHeaderRenderer(
    val thumbnail: ThumbnailRenderer? = null,
    val buttons: List<Button> = emptyList(),
    val title: Runs = Runs(null),
    val subtitle: Runs = Runs(null),
    val secondSubtitle: Runs? = null,
    val straplineTextOne: Runs? = null,
    val description: DescriptionWrapper? = null,
    val facepile: FacepileWrapper? = null,
) {
    @Serializable
    data class Button(
        val musicPlayButtonRenderer: MusicPlayButtonRenderer? = null,
        val menuRenderer: Menu.MenuRenderer? = null,
        val toggleButtonRenderer: ToggleButtonRenderer? = null,
    ) {
        @Serializable
        data class MusicPlayButtonRenderer(
            val playNavigationEndpoint: NavigationEndpoint? = null,
        )

        @Serializable
        data class ToggleButtonRenderer(
            val defaultIcon: Icon? = null,
            val defaultServiceEndpoint: DefaultServiceEndpoint? = null,
            val toggledServiceEndpoint: ToggledServiceEndpoint? = null,
        )
    }
}

@Serializable
data class AvatarStackViewModel(
    val avatars: List<Avatar>?,
    val text: AvatarText?,
    val rendererContext: RendererContext?,
) {
    @Serializable
    data class Avatar(
        val avatarViewModel: AvatarViewModel?,
    )

    @Serializable
    data class AvatarViewModel(
        val image: AvatarImage?,
    )

    @Serializable
    data class AvatarImage(
        val sources: List<ImageSource>?,
    )

    @Serializable
    data class ImageSource(
        val url: String?,
    )

    @Serializable
    data class AvatarText(
        val content: String?,
    )

    @Serializable
    data class RendererContext(
        val commandContext: CommandContext?,
    )

    @Serializable
    data class CommandContext(
        val onTap: OnTap?,
    )

    @Serializable
    data class OnTap(
        val innertubeCommand: InnerTubeBrowseCommand?,
    )

    @Serializable
    data class InnerTubeBrowseCommand(
        val browseEndpoint: BrowseEndpoint?,
    )

    @Serializable
    data class BrowseEndpoint(
        val browseId: String?,
    )
}

@Serializable
data class DescriptionWrapper(
    val musicDescriptionShelfRenderer: MusicDescriptionShelfRenderer?,
)

@Serializable
data class FacepileWrapper(
    val avatarStackViewModel: AvatarStackViewModel?,
)

package com.metrolist.shared.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin

class NavigationContainerState {
    val input = NavigationInputState()
    var change by mutableStateOf<NavigationChange?>(null)
        internal set
    internal var pendingPageOrigin: TransformOrigin? = null
    internal var pendingAlbumArtworkSource: String? = null
    private val pageOrigins = mutableMapOf<Screen, TransformOrigin>()
    internal val albumArtworkVisibility = mutableStateMapOf<String, Boolean>()
    internal var pendingPopup: NavigationPopupOrigin? = null

    internal fun takePopupSource(): NavigationPopupOrigin? = pendingPopup.also { pendingPopup = null }

    internal fun navigate(
        change: NavigationChange,
        reuseOrigin: Boolean = false,
    ) {
        input.reset()
        if (change.isPop) {
            val album = change.source as? Screen.Album
            album?.artworkSource?.let { albumArtworkVisibility.remove(albumArtworkKey(album.id, it)) }
        }
        if (!change.isPop && (!reuseOrigin || change.destination !in pageOrigins)) {
            pageOrigins[change.destination] = pendingPageOrigin ?: TransformOrigin.Center
        }
        pendingPageOrigin = null
        pendingAlbumArtworkSource = null
        pendingPopup = null
        this.change = change
    }

    fun pageOrigin(route: Screen?): TransformOrigin = pageOrigins[route] ?: TransformOrigin.Center

    fun pagePattern(
        source: Screen?,
        destination: Screen?,
    ): NavigationPattern = if (destination is Screen.Album) NavigationPattern.Default else navigationPattern(source, destination)

    internal val elementPattern: NavigationPattern
        get() {
            val edge = change ?: return NavigationPattern.Default
            val album = (if (edge.isPop) edge.source else edge.destination) as? Screen.Album ?: return edge.pattern
            val key = album.artworkSource?.let { albumArtworkKey(album.id, it) }
            return edge.pattern.takeIf { key != null && albumArtworkVisibility[key] == true } ?: NavigationPattern.Default
        }
}

val LocalNavigationContainerState = staticCompositionLocalOf<NavigationContainerState?> { null }
val LocalNavigationRoute = staticCompositionLocalOf<Screen?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.navigationContainerTransform(
    source: String?,
    shape: Shape = RectangleShape,
    visibilityScope: AnimatedVisibilityScope? = LocalNavVisibilityScope.current,
    fadeContent: Boolean = true,
): Modifier {
    val scope = LocalNavSharedTransitionScope.current
    val visibility = visibilityScope
    val motion = rememberNavigationMotion()
    if (source == null || scope == null || visibility == null || !motion.enabled) return this
    val boundsSpec = MaterialTheme.motionScheme.fastSpatialSpec<Rect>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    return with(scope) {
        val contentState = rememberSharedContentState("container:$source")
        sharedBounds(
            contentState,
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ -> boundsSpec },
            enter = if (fadeContent) fadeIn(effects) else EnterTransition.None,
            exit = if (fadeContent) fadeOut(effects) else ExitTransition.None,
            clipInOverlayDuringTransition = OverlayClip(shape),
        )
    }.blockInput(!visibility.transition.acceptsInput(EnterExitState.Visible))
}

@Composable
fun NavigationDestination(
    route: Screen,
    visibility: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val input = LocalNavigationContainerState.current?.input
    val settled = visibility.transition.acceptsInput(EnterExitState.Visible)
    CompositionLocalProvider(
        LocalNavVisibilityScope provides visibility,
        LocalNavigationRoute provides route,
        LocalAlbumArtworkSource provides (route as? Screen.Album)?.artworkSource,
    ) {
        Box(Modifier.fillMaxSize().blockInput(!(input?.acceptsInput(settled) ?: settled))) { content() }
    }
}

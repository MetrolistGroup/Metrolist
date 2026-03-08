package com.metrolist.shared.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalNavSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

class NavOverlayCoordinates {
    var coordinates: LayoutCoordinates? = null
    var contentCoordinates: LayoutCoordinates? = null

    val radii = mutableStateMapOf<String, Dp>()
}

val LocalNavOverlayCoordinates = staticCompositionLocalOf<NavOverlayCoordinates?> { null }

val LocalNavVisibilityScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Immutable
class NavigationMotion internal constructor(
    private val scheme: MotionScheme,
    val enabled: Boolean = true,
) {
    internal val effectsSpec = scheme.fastEffectsSpec<Float>()
    internal val spatialSpec = scheme.fastSpatialSpec<Float>()

    val popupEnter: EnterTransition
        get() = if (enabled) fadeIn(effectsSpec) + scaleIn(spatialSpec, initialScale = 0.8f) else EnterTransition.None
    val popupExit: ExitTransition
        get() = if (enabled) fadeOut(effectsSpec) else ExitTransition.None

    fun transition(
        pattern: NavigationPattern = NavigationPattern.Default,
        isPop: Boolean = false,
        origin: TransformOrigin = TransformOrigin.Center,
    ): ContentTransform =
        if (enabled) {
            val spatial =
                if (pattern ==
                    NavigationPattern.Expressive
                ) {
                    MotionScheme.expressive().defaultSpatialSpec<Float>()
                } else {
                    spatialSpec
                }
            val enterScale = if (pattern == NavigationPattern.Expressive && !isPop) 0.8f else 0.96f
            val exitScale = if (pattern == NavigationPattern.Expressive && isPop) 0.8f else 0.96f
            val pivot = if (pattern == NavigationPattern.Expressive) origin else TransformOrigin.Center
            (scaleIn(spatial, initialScale = enterScale, transformOrigin = pivot) + fadeIn(effectsSpec))
                .togetherWith(scaleOut(spatial, targetScale = exitScale, transformOrigin = pivot) + fadeOut(effectsSpec))
        } else {
            EnterTransition.None togetherWith ExitTransition.None
        }

    fun predictiveBackTransition(
        pattern: NavigationPattern = NavigationPattern.Default,
        origin: TransformOrigin = TransformOrigin.Center,
    ): ContentTransform = transition(pattern, isPop = true, origin = origin)
}

@Composable
fun rememberNavigationMotion(): NavigationMotion {
    val scheme = MaterialTheme.motionScheme
    val scale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return remember(scheme, scale) { NavigationMotion(scheme, scale > 0f) }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NavSharedTransitionLayout(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val parent = LocalNavSharedTransitionScope.current
    val parentOverlay = LocalNavOverlayCoordinates.current
    if (parent != null) {
        Box(modifier.onPlaced { parentOverlay?.contentCoordinates = it }, contentAlignment = contentAlignment) { content() }
        return
    }
    val overlay = remember { NavOverlayCoordinates() }
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(
            LocalNavSharedTransitionScope provides this,
            LocalNavOverlayCoordinates provides overlay,
        ) {
            Box(
                Modifier.fillMaxSize().resetPopupSource(LocalNavigationContainerState.current).onPlaced { overlay.coordinates = it },
                contentAlignment = contentAlignment,
            ) { content() }
        }
    }
}

@Composable
fun NavigationTransition(
    route: Screen,
    content: @Composable (Screen) -> Unit,
) {
    val savedState = rememberSaveableStateHolder()
    val containers = LocalNavigationContainerState.current
    val navigator = LocalNavigator.current
    NavSharedTransitionLayout {
        PredictiveNavigationContent(
            targetState = route,
            previousState = navigator.previousRoute ?: route,
            backEnabled = navigator.canGoBack,
            onBack = { navigator.goBack() },
            modifier = Modifier.fillMaxSize(),
            isPop = containers?.change?.isPop == true,
            pattern = { source, destination -> containers?.pagePattern(source, destination) ?: navigationPattern(source, destination) },
            origin = { containers?.pageOrigin(it) ?: TransformOrigin.Center },
        ) { destination ->
            NavigationDestination(destination, this) {
                savedState.SaveableStateProvider(destination.toString()) { content(destination) }
            }
        }
    }
}

@Composable
fun NavigationContentProvider(
    vararg values: ProvidedValue<*>,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(*values) { NavSharedTransitionLayout(content = content) }
}

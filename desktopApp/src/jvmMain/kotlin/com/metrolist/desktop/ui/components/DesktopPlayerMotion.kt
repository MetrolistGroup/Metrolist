package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.metrolist.shared.ui.navigation.LocalNavSharedTransitionScope
import com.metrolist.shared.ui.navigation.LocalNavVisibilityScope
import com.metrolist.shared.ui.navigation.acceptsInput
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.rememberNavigationMotion

internal object DesktopPlayerMotion {
    val artworkBounds = tween<Rect>(300, easing = FastOutSlowInEasing)
    val fade = tween<Float>(200)
}

@Composable
internal fun DesktopPlayerVisibility(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    val enabled = rememberNavigationMotion().enabled
    AnimatedVisibility(
        visible = visible,
        enter = if (enabled) fadeIn(DesktopPlayerMotion.fade) else EnterTransition.None,
        exit = if (enabled) fadeOut(DesktopPlayerMotion.fade) else ExitTransition.None,
    ) {
        CompositionLocalProvider(LocalNavVisibilityScope provides this) {
            Box(Modifier.blockInput(!transition.acceptsInput(EnterExitState.Visible))) { content() }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.desktopPlayerArtwork(songId: String?): Modifier {
    val scope = LocalNavSharedTransitionScope.current
    val visibility = LocalNavVisibilityScope.current
    if (songId == null || scope == null || visibility == null || !rememberNavigationMotion().enabled) return this
    return with(scope) {
        sharedElement(
            rememberSharedContentState("desktop-player-artwork:$songId"),
            animatedVisibilityScope = visibility,
            boundsTransform = BoundsTransform { _, _ -> DesktopPlayerMotion.artworkBounds },
        )
    }
}

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.isDesktop
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesktopHorizontalScrollControls(
    state: ScrollableState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!isDesktop) {
        content()
        return
    }
    val scope = rememberCoroutineScope()
    val animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val distance = with(LocalDensity.current) { maxWidth.toPx() * 0.8f }

        fun scroll(forward: Boolean) {
            if (if (forward) !state.canScrollForward else !state.canScrollBackward) return
            scope.launch {
                if (state is CarouselState) {
                    state.animateScrollToItem(
                        (state.currentItem + if (forward) 1 else -1).coerceAtLeast(0),
                        animationSpec,
                    )
                } else {
                    state.animateScrollBy(if (forward) distance else -distance, animationSpec)
                }
            }
        }

        content()
        AnimatedVisibility(
            visible = state.canScrollBackward,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            FilledTonalIconButton(onClick = { scroll(false) }, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.previous))
            }
        }
        AnimatedVisibility(
            visible = state.canScrollForward,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            FilledTonalIconButton(onClick = { scroll(true) }, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.next))
            }
        }
    }
}

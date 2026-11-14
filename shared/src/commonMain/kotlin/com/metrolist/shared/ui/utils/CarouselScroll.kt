package com.metrolist.shared.ui.utils

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.metrolist.shared.utils.isDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class CarouselScrollSettler(
    private val scope: CoroutineScope,
    private val settle: suspend () -> Unit,
) {
    private var job: Job? = null

    fun schedule() {
        cancel()
        job =
            scope.launch {
                // Wheel gestures have no release event.
                delay(150)
                settle()
            }
    }

    fun cancel() {
        job?.cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Modifier.carouselScroll(
    state: CarouselState,
    flingBehavior: FlingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(state),
    enabled: Boolean = true,
): Modifier = carouselScroll(state as ScrollableState, flingBehavior, enabled)

@Composable
fun Modifier.carouselScroll(
    state: ScrollableState,
    flingBehavior: FlingBehavior? = null,
    enabled: Boolean = true,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val dragFlingBehavior = flingBehavior ?: ScrollableDefaults.flingBehavior()
    val wheelScope = rememberCoroutineScope()
    val settling =
        remember(state, flingBehavior) {
            CarouselScrollSettler(wheelScope) {
                if (flingBehavior != null) state.scroll { with(flingBehavior) { performFling(0f) } }
            }
        }
    DisposableEffect(settling, enabled) {
        onDispose { settling.cancel() }
    }
    if (!enabled) return this

    // Desktop containers animate wheel input and settle snapping through Compose Foundation.
    val wheelModifier =
        if (isDesktop) {
            this
        } else {
            mouseWheelScroll(horizontal = true) { delta ->
                if ((delta > 0f && state.canScrollForward) || (delta < 0f && state.canScrollBackward)) {
                    settling.cancel()
                    wheelScope.launch { state.scroll(MutatePriority.UserInput) { scrollBy(delta) } }
                    if (flingBehavior != null) {
                        settling.schedule()
                    }
                    delta
                } else {
                    0f
                }
            }
        }
    return wheelModifier
        .focusGroup()
        .mouseDragScroll(
            state = state,
            horizontal = true,
            reverseDirection = layoutDirection == LayoutDirection.Rtl,
            flingBehavior = dragFlingBehavior,
        ).desktopArrowScroll(state, horizontal = true)
}

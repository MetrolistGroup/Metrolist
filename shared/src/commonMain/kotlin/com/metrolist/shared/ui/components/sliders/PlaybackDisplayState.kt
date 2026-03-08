package com.metrolist.shared.ui.components.sliders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

@Composable
fun <T, R> StateFlow<T>.collectPlaybackDisplayState(
    active: Boolean,
    transform: (T) -> R,
): State<R> =
    key(this, active, transform) {
        produceState(transform(value)) {
            if (active) map(transform).distinctUntilChanged().collect { value = it }
        }
    }

fun progressAtPixel(
    fraction: Float,
    lengthPx: Int,
): Float {
    if (!fraction.isFinite()) return 0f
    val bounded = fraction.coerceIn(0f, 1f)
    if (lengthPx <= 0) return bounded
    return (bounded * lengthPx).roundToInt().toFloat() / lengthPx
}

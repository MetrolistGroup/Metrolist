package com.metrolist.shared.ui.components

import androidx.compose.animation.core.snap
import androidx.compose.material3.MotionScheme
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import kotlin.math.PI
import kotlin.math.sin

internal class SpeedDialRollSelection(
    item: YTItem,
) {
    val itemKey = canonicalHomeItemId(item)

    fun resolve(
        items: List<YTItem>,
        isItemEnabled: (YTItem) -> Boolean,
    ): YTItem? = items.firstOrNull { canonicalHomeItemId(it) == itemKey }?.takeIf(isItemEnabled)
}

internal class SpeedDialRollMotion(
    scheme: MotionScheme,
    private val enabled: Boolean,
) {
    val spatialSpec = if (enabled) scheme.fastSpatialSpec<Float>() else snap<Float>()
    val effectsSpec = scheme.fastEffectsSpec<Float>()

    fun frame(progress: Float): DiceRollFrame {
        if (!enabled) return DiceRollFrame()
        val stretch = sin(progress * 2 * PI).toFloat()
        return DiceRollFrame(
            rotation = progress * 360f,
            scaleX = 1f + stretch * 0.18f,
            scaleY = 1f - stretch * 0.14f,
            morph = sin(progress * PI).toFloat().coerceIn(0f, 1f),
        )
    }
}

internal data class DiceRollFrame(
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val morph: Float = 0f,
)

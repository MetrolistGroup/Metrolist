package com.metrolist.android.ui.glass

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Non-pointer state feedback remains visible when the internal Material ripple is replaced. */
internal class GlassInteractionStateLayer {
    private val focuses = mutableSetOf<FocusInteraction.Focus>()
    private val hovers = mutableSetOf<HoverInteraction.Enter>()
    private var alpha by mutableStateOf(0f)

    fun update(interaction: Interaction) {
        when (interaction) {
            is FocusInteraction.Focus -> focuses.add(interaction)
            is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
            is HoverInteraction.Enter -> hovers.add(interaction)
            is HoverInteraction.Exit -> hovers.remove(interaction.enter)
        }
        alpha = when {
            focuses.isNotEmpty() -> .12f
            hovers.isNotEmpty() -> .08f
            else -> 0f
        }
    }

    fun draw(scope: DrawScope, intensity: Float) {
        if (alpha > 0f && intensity > 0f) with(scope) {
            drawRect(Color.White.copy(alpha = alpha * intensity))
        }
    }
}

package com.metrolist.shared.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.theme.contrastRatio
import kotlinx.coroutines.launch

@Composable
fun FocusOutlineProvider(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val fallback = LocalIndication.current
    val colors = MaterialTheme.colorScheme
    val inputModeManager = LocalInputModeManager.current
    // Retain focus interactions while artwork theme colors animate.
    val color = rememberUpdatedState(colors.primary)
    val contrastColor = rememberUpdatedState(colors.onPrimary)
    val indication =
        remember(enabled, fallback, inputModeManager) {
            if (enabled) {
                FocusOutlineIndication(color, contrastColor, inputModeManager)
            } else {
                fallback
            }
        }
    CompositionLocalProvider(LocalIndication provides indication, content = content)
}

@Composable
fun Modifier.focusOutline(
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 3.dp,
    focused: Boolean? = null,
): Modifier {
    val colors = MaterialTheme.colorScheme
    val contrastColor =
        if (contrastRatio(color, colors.primary) > contrastRatio(color, colors.onPrimary)) {
            colors.primary
        } else {
            colors.onPrimary
        }
    val inputModeManager = LocalInputModeManager.current
    var targetFocused by remember { mutableStateOf(false) }
    return onFocusChanged { targetFocused = it.isFocused }
        .drawWithContent {
            drawContent()
            if ((focused ?: targetFocused) && inputModeManager.inputMode == InputMode.Keyboard) {
                drawFocusOutline(shape, color, contrastColor, borderWidth)
            }
        }
}

private fun DrawScope.drawFocusOutline(
    shape: Shape,
    color: Color,
    contrastColor: Color,
    borderWidth: Dp = 3.dp,
) {
    // Keep both strokes inside the target so scroll containers cannot clip the ring.
    val width = borderWidth.coerceAtLeast(3.dp).toPx()
    val outerWidth = width + 2.dp.toPx()
    if (size.minDimension <= outerWidth) return
    inset(outerWidth / 2) {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, contrastColor, style = Stroke(outerWidth))
        drawOutline(outline, color, style = Stroke(width))
    }
}

private data class FocusOutlineIndication(
    val color: State<Color>,
    val contrastColor: State<Color>,
    val inputModeManager: InputModeManager,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusOutlineIndicationNode(interactionSource, color, contrastColor, inputModeManager)
}

private class FocusOutlineIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: State<Color>,
    private val contrastColor: State<Color>,
    private val inputModeManager: InputModeManager,
) : Modifier.Node(),
    DrawModifierNode {
    private val focusInteractions = mutableSetOf<FocusInteraction.Focus>()
    private val pressInteractions = mutableSetOf<PressInteraction.Press>()
    private val shape = RoundedCornerShape(12.dp)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> focusInteractions += interaction
                    is FocusInteraction.Unfocus -> focusInteractions -= interaction.focus
                    is PressInteraction.Press -> pressInteractions += interaction
                    is PressInteraction.Release -> pressInteractions -= interaction.press
                    is PressInteraction.Cancel -> pressInteractions -= interaction.press
                }
                invalidateDraw()
            }
        }
    }

    override fun onDetach() {
        focusInteractions.clear()
        pressInteractions.clear()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressInteractions.isNotEmpty()) {
            drawRoundRect(color.value.copy(alpha = 0.18f), cornerRadius = CornerRadius(12.dp.toPx()))
        }
        if (focusInteractions.isNotEmpty() && inputModeManager.inputMode == InputMode.Keyboard) {
            drawFocusOutline(shape, color.value, contrastColor.value)
        }
    }
}

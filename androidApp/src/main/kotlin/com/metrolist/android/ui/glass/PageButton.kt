package com.metrolist.android.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop

/** Page action retaining Material's dimensions, role colours, content, and input behaviour. */
@Composable
fun PageButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = uiRoundnessShape(),
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable RowScope.() -> Unit,
) {
    val material = settings.usesGlassMaterial(GlassArea.ActionButtons)
    val resolvedColors = pageButtonColors(colors, settings, material)
    val nativeBounds = remember { GlassNativeSurfaceBounds() }
    NativePageButtonFeedback(material, enabled, shape, interactionSource, nativeBounds, settings) { feedback, source ->
        Button(
            onClick = onClick,
            modifier = modifier.then(feedback).pageButtonMaterial(enabled, shape, colors, settings),
            enabled = enabled,
            shape = shape,
            colors = resolvedColors,
            elevation = elevation,
            border = if (material || settings.usesGlassContour(GlassArea.ActionButtons)) null else border,
            contentPadding = contentPadding,
            interactionSource = source,
        ) {
            content()
            NativeButtonSurfaceProbe(nativeBounds, contentRows = 1)
        }
    }
}

/** Text page action with the exact native text-button defaults when Glass is disabled. */
@Composable
fun PageTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = uiRoundnessShape(),
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable RowScope.() -> Unit,
) {
    val material = settings.usesGlassMaterial(GlassArea.ActionButtons)
    val resolvedColors = pageButtonColors(colors, settings, material)
    val nativeBounds = remember { GlassNativeSurfaceBounds() }
    NativePageButtonFeedback(material, enabled, shape, interactionSource, nativeBounds, settings) { feedback, source ->
        TextButton(
            onClick = onClick,
            modifier = modifier.then(feedback).pageButtonMaterial(enabled, shape, colors, settings),
            enabled = enabled,
            shape = shape,
            colors = resolvedColors,
            elevation = elevation,
            border = if (material || settings.usesGlassContour(GlassArea.ActionButtons)) null else border,
            contentPadding = contentPadding,
            interactionSource = source,
        ) {
            content()
            NativeButtonSurfaceProbe(nativeBounds, contentRows = 1)
        }
    }
}

/** Use one explicit Glass indication; native buttons otherwise retain their own ripple. */
@Composable
internal fun PageButtonFeedback(
    material: Boolean,
    enabled: Boolean,
    shape: Shape,
    interactionSource: MutableInteractionSource?,
    settings: GlassSettings = rememberGlassSettings(),
    animateContentLayer: Boolean = false,
    content: @Composable (Modifier, MutableInteractionSource?) -> Unit,
) {
    PageButtonFeedbackImpl(material, enabled, shape, interactionSource, settings, null, animateContentLayer, content)
}

/** Known native adapters export actual surface coordinates without changing caller layout. */
@Composable
internal fun NativePageButtonFeedback(
    material: Boolean,
    enabled: Boolean,
    shape: Shape,
    interactionSource: MutableInteractionSource?,
    nativeBounds: GlassNativeSurfaceBounds,
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable (Modifier, MutableInteractionSource?) -> Unit,
) {
    PageButtonFeedbackImpl(material, enabled, shape, interactionSource, settings, nativeBounds, false, content)
}

/** A zero-size sibling preserves the native content's Box/RowScope and all of its measurements. */
@Composable
internal fun NativeButtonSurfaceProbe(bounds: GlassNativeSurfaceBounds, contentRows: Int = 0) {
    Spacer(Modifier.size(0.dp).then(bounds.indication).onPlaced { probe ->
        var surface = probe.parentLayoutCoordinates
        repeat(contentRows) { surface = surface?.parentLayoutCoordinates }
        bounds.coordinates = surface
    })
}

@Composable
private fun PageButtonFeedbackImpl(
    material: Boolean,
    enabled: Boolean,
    shape: Shape,
    interactionSource: MutableInteractionSource?,
    settings: GlassSettings,
    nativeBounds: GlassNativeSurfaceBounds?,
    animateContentLayer: Boolean,
    content: @Composable (Modifier, MutableInteractionSource?) -> Unit,
) {
    // Native adapters draw their indication on the content probe under M3's own clip.
    // Reset it on every branch so master/material/disabled changes remove old nodes.
    nativeBounds?.indication = Modifier
    val global = LocalGlassRippleActive.current
    if (!material && !global) {
        content(Modifier, interactionSource)
        return
    }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val active = enabled && (global || isGlassMotionAllowed())
    if (!active) {
        GlassArtworkContent(settings, material) { content(Modifier, source) }
        return
    }
    if (material) {
        val feedback = Modifier.retainGlassButtonGesture().glassButtonFeedback(source, shape,
            drawMotionOnly = !animateContentLayer, retainPressUntilUp = true, settings = settings)
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            GlassArtworkContent(settings, material) { content(feedback, source) }
        }
        return
    }
    val tracker = remember { GlassHighlightPointerTracker() }
    val policy = settings.interactionPolicy()
    val indication = remember(tracker, policy, shape, nativeBounds) {
        GlassHighlightIndication(tracker, policy = policy, nativeSurfaceShape = shape, nativeSurfaceBounds = nativeBounds)
    }
    val pointerFeedback = Modifier.trackGlassHighlightPointers(tracker)
    val feedback = if (nativeBounds == null) pointerFeedback.indication(source, indication) else {
        nativeBounds.indication = Modifier.indication(source, indication)
        pointerFeedback
    }
    CompositionLocalProvider(LocalRippleConfiguration provides null) { content(feedback, source) }
}

@Composable
private fun Modifier.pageButtonMaterial(
    enabled: Boolean,
    shape: Shape,
    colors: ButtonColors,
    settings: GlassSettings,
): Modifier {
    if (!settings.usesGlassMaterial(GlassArea.ActionButtons)) {
        return glassAreaContour(settings, GlassArea.ActionButtons, shape)
    }
    // Use the rendered parent material when it exports a child-free source.
    val background = colorPalette().background0
    val drawBackground: DrawScope.() -> Unit = remember(background) { { drawRect(background) } }
    val backdrop = LocalGlassContainerBackdrop.current ?: rememberCanvasBackdrop(drawBackground)
    val roleFill = if (enabled) colors.containerColor else colors.disabledContainerColor
    return glassMaterial(
        settings = settings.forArea(GlassArea.ActionButtons),
        shape = shape,
        ownBackdrop = backdrop,
        filmColor = roleFill,
        drawShadow = false,
    )
}

/** Resolve Material's explicit content role on the same film used by pageButtonMaterial. */
@Composable
private fun pageButtonColors(colors: ButtonColors, settings: GlassSettings, material: Boolean): ButtonColors {
    if (!material) return colors
    val plate = glassFilmColor(settings.forArea(GlassArea.ActionButtons), colors.containerColor, menu = false)
    val surface = effectiveSurfaceColor(surfaceBackdropColor(), plate)
    return colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
        contentColor = glassPaneForeground(colors.contentColor, surface, TextContrast, glassSurfaceDarkness(settings)))
}

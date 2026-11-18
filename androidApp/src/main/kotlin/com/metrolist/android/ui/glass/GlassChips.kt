package com.metrolist.android.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop

/** Custom text chips retain their original layout and one native clickable, including row scrolling. */
@Composable
internal fun GlassCustomChip(
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = uiRoundnessShape(),
    settings: GlassSettings = rememberGlassSettings(),
    role: Role? = null,
    content: @Composable (Modifier) -> Unit,
) {
    val material = settings.usesGlassMaterial(GlassArea.ActionButtons)
    GlassChipFeedback(material, enabled, shape, settings) { feedback, source ->
        val chip = modifier.then(if (material) Modifier else Modifier.clip(shape)).then(feedback)
            .glassChipBackground(color, shape, settings)
            .clickable(interactionSource = source, indication = if (source == null) LocalIndication.current else null,
                enabled = enabled, role = role, onClick = onClick)
        if (material) SurfaceForegroundContent(
            chipForegroundSurface(settings, color), glassInkDarkness = glassSurfaceDarkness(settings),
        ) { content(chip) }
        else content(chip)
    }
}

/** Replaces a custom chip's fill without changing its padding, gestures or content. */
@Composable
internal fun Modifier.glassChipBackground(
    color: Color,
    shape: Shape,
    settings: GlassSettings = rememberGlassSettings(),
    filmColor: Color? = color,
    allowInNativeQueue: Boolean = false,
): Modifier {
    if (LocalNativeQueueRows.current && !allowInNativeQueue) return background(color, shape)
    if (!settings.usesGlassMaterial(GlassArea.ActionButtons)) {
        return background(color, shape).glassAreaContour(settings, GlassArea.ActionButtons, shape)
    }
    val backgroundColor = colorPalette().background0
    val drawBackground: DrawScope.() -> Unit = remember(backgroundColor) { { drawRect(backgroundColor) } }
    // A container export includes its material but excludes these child controls.
    // Page captures can contain the chip itself, so retain an independent fallback.
    val backdrop = LocalGlassContainerBackdrop.current ?: rememberCanvasBackdrop(drawBackground)
    return glassMaterial(settings.forArea(GlassArea.ActionButtons), shape,
        ownBackdrop = backdrop, filmColor = filmColor, drawShadow = false)
}

/** Keeps Material selection, colours and touch bounds with shared Glass material and feedback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = uiRoundnessShape(),
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
    settings: GlassSettings = rememberGlassSettings(),
) {
    val material = settings.usesGlassMaterial(GlassArea.ActionButtons)
    GlassChipFeedback(material, enabled, shape, settings) { feedback, source ->
        val film = when {
            !enabled && selected -> colors.disabledSelectedContainerColor
            !enabled -> colors.disabledContainerColor
            selected -> colors.selectedContainerColor
            else -> colors.containerColor
        }
        val surface = if (material) chipForegroundSurface(settings, film) else null
        val inkDarkness = if (material) glassSurfaceDarkness(settings) else 0f
        // The 48dp touch spacer stays outside the visible surface in both renderers.
        // Otherwise a global indication would paint the spacer above/below the 32dp chip.
        Box(Modifier.minimumInteractiveComponentSize().then(modifier), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                FilterChip(selected, onClick, label,
                    modifier = if (material) feedback.glassChipBackground(film, shape, settings)
                        else feedback.glassAreaContour(settings, GlassArea.ActionButtons, shape),
                    enabled = enabled, shape = shape,
                    colors = if (surface != null) colors.copy(containerColor = Color.Transparent,
                        selectedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        disabledSelectedContainerColor = Color.Transparent,
                        labelColor = glassPaneForeground(colors.labelColor, surface, TextContrast, inkDarkness),
                        selectedLabelColor = glassPaneForeground(colors.selectedLabelColor, surface, TextContrast, inkDarkness),
                        leadingIconColor = glassPaneForeground(colors.leadingIconColor, surface, IconContrast, inkDarkness, glyph = true),
                        trailingIconColor = glassPaneForeground(colors.trailingIconColor, surface, IconContrast, inkDarkness, glyph = true),
                        selectedLeadingIconColor = glassPaneForeground(colors.selectedLeadingIconColor, surface, IconContrast, inkDarkness, glyph = true),
                        selectedTrailingIconColor = glassPaneForeground(colors.selectedTrailingIconColor, surface, IconContrast, inkDarkness, glyph = true)) else colors,
                    border = if (material) null else FilterChipDefaults.filterChipBorder(enabled, selected),
                    interactionSource = source)
            }
        }
    }
}

/** Preserves the native keyword chip; Glass uses the same shared material as filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSuggestionChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape? = null,
    colors: ChipColors = SuggestionChipDefaults.suggestionChipColors(),
    border: BorderStroke? = SuggestionChipDefaults.suggestionChipBorder(enabled),
    settings: GlassSettings = rememberGlassSettings(),
) {
    val nativeShape = shape ?: uiRoundnessShape()
    val material = settings.usesGlassMaterial(GlassArea.ActionButtons)
    val glassShape = if (material) shape ?: uiRoundnessShape() else nativeShape
    val surface = if (material) chipForegroundSurface(settings,
        if (enabled) colors.containerColor else colors.disabledContainerColor) else null
    val inkDarkness = if (material) glassSurfaceDarkness(settings) else 0f
    GlassChipFeedback(material, enabled, glassShape, settings) { feedback, source ->
        Box(Modifier.minimumInteractiveComponentSize().then(modifier), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                SuggestionChip(onClick, label,
                    modifier = if (material) feedback.glassChipBackground(
                        if (enabled) colors.containerColor else colors.disabledContainerColor, glassShape, settings)
                        else feedback.glassAreaContour(settings, GlassArea.ActionButtons, nativeShape),
                    enabled = enabled, shape = glassShape,
                    colors = if (surface != null) colors.copy(containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        labelColor = glassPaneForeground(colors.labelColor, surface, TextContrast, inkDarkness),
                        leadingIconContentColor = glassPaneForeground(colors.leadingIconContentColor, surface, IconContrast, inkDarkness, glyph = true),
                        trailingIconContentColor = glassPaneForeground(colors.trailingIconContentColor, surface, IconContrast, inkDarkness, glyph = true)) else colors,
                    border = if (material) null else border, interactionSource = source)
            }
        }
    }
}

@Composable
private fun chipForegroundSurface(settings: GlassSettings, role: Color): Color =
    effectiveSurfaceColor(surfaceBackdropColor(),
        glassFilmColor(settings.forArea(GlassArea.ActionButtons), role, menu = false))

/** Material owns input; its single source also drives the bounded Glass response. */
@Composable
private fun GlassChipFeedback(
    material: Boolean,
    enabled: Boolean,
    shape: Shape,
    settings: GlassSettings,
    content: @Composable (Modifier, MutableInteractionSource?) -> Unit,
) {
    if (!material) {
        PageButtonFeedback(false, enabled, shape, null, settings, content = content)
        return
    }
    val source = remember { MutableInteractionSource() }
    if (!enabled) {
        content(Modifier, source)
        return
    }
    // Native chip interactions cancel the visual press when the row starts scrolling.
    val feedback = Modifier.glassButtonFeedback(source, shape, drawMotionOnly = true, retainAfterLongPress = true, settings = settings)
    CompositionLocalProvider(LocalRippleConfiguration provides null) { content(feedback, source) }
}

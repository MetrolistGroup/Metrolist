package com.metrolist.android.ui.glass

import com.metrolist.android.ui.component.reservedIconActionCanvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.theme.paletteDarkness
import com.metrolist.android.ui.theme.surfaceColor
import com.metrolist.android.ui.theme.uiRoundnessShape

/** Matches the film explicitly supplied by callers that draw their own dialog-action plate. */
@Composable
internal fun dialogButtonForegroundSurface(settings: GlassSettings, role: Color?): Color? {
    if (role == null || !settings.usesGlassDialogButtonMaterial()) return null
    val backdrop = LocalSurfaceBackdropColor.current ?: surfaceColor(colorPalette().background1)
    return effectiveSurfaceColor(backdrop,
        glassFilmColor(settings.forArea(GlassArea.DialogButtons), role, menu = true))
}

@Composable
private fun DialogButtonForeground(surface: Color?, glyph: Boolean = false, content: @Composable () -> Unit) {
    if (surface == null) {
        content()
        return
    }
    val contentColor = LocalContentColor.current
    val darkness = LocalGlassInkDarkness.current ?: paletteDarkness()
    val minimum = if (glyph) IconContrast else TextContrast
    SurfaceForegroundContent(surface, glassInkDarkness = darkness) {
        CompositionLocalProvider(LocalContentColor provides glassPaneForeground(
            contentColor, surface, minimum, darkness, glyph = glyph), content = content)
    }
}

/** One native action source moves the entire material and foreground without another ripple. */
@Composable
internal fun GlassDialogButtonFeedback(
    enabled: Boolean,
    shape: Shape,
    settings: GlassSettings = rememberGlassSettings(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable (Modifier, MutableInteractionSource?) -> Unit,
) {
    PageButtonFeedback(settings.usesGlassDialogButtonMaterial(), enabled, shape,
        interactionSource, settings, animateContentLayer = true, content = content)
}

/** Material icon geometry and input remain native; the caller's material is inside the motion. */
@PublishedApi
@Composable
internal fun GlassDialogIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    foregroundFilm: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = uiRoundnessShape()
    val settings = rememberGlassSettings()
    val nativeBounds = remember { GlassNativeSurfaceBounds() }
    val plate = dialogButtonForegroundSurface(settings, foregroundFilm)?.takeIf { enabled }
        ?.let { effectiveSurfaceColor(it, colors.containerColor) }
    NativePageButtonFeedback(settings.usesGlassDialogButtonMaterial(), enabled, shape,
        interactionSource, nativeBounds, settings) { feedback, source ->
        androidx.compose.material3.IconButton(
            onClick = onClick, modifier = feedback.then(modifier), enabled = enabled,
            colors = colors, interactionSource = source, shape = shape,
        ) {
            DialogButtonForeground(plate, glyph = true) { content() }
            NativeButtonSurfaceProbe(nativeBounds)
        }
    }
}

/** Presentation equivalent of the existing image icon button, sharing its sole clickable source. */
@OptIn(ExperimentalFoundationApi::class)
@PublishedApi
@Composable
internal fun GlassDialogIconButton(
    onClick: () -> Unit,
    icon: Int,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    foregroundFilm: Color? = null,
) {
    val settings = rememberGlassSettings()
    if (!settings.usesGlassDialogButtonMaterial()) {
        androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                tint = color
            )
        }
        return
    }
    val shape = uiRoundnessShape()
    val plate = dialogButtonForegroundSurface(settings, foregroundFilm)
    val ink = if (enabled && plate != null) glassPaneForeground(color, plate, IconContrast,
        LocalGlassInkDarkness.current ?: paletteDarkness(), glyph = true) else color
    GlassDialogButtonFeedback(enabled, shape, settings) { feedback, source ->
        Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(ink),
            modifier = feedback.then(modifier.reservedIconActionCanvas(shape,
                interactionSource = source, indication = null, enabled = enabled, onClick = onClick, role = Role.Button)))
    }
}

/** Keeps OutlinedButton's layout and action ownership while moving its supplied Glass material. */
@Composable
internal fun GlassDialogOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = uiRoundnessShape(),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    foregroundFilm: Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val settings = rememberGlassSettings()
    val nativeBounds = remember { GlassNativeSurfaceBounds() }
    val plate = dialogButtonForegroundSurface(settings, foregroundFilm)?.takeIf { enabled }
        ?.let { effectiveSurfaceColor(it, colors.containerColor) }
    NativePageButtonFeedback(settings.usesGlassDialogButtonMaterial(), enabled, shape,
        interactionSource, nativeBounds, settings) { feedback, source ->
        OutlinedButton(onClick = onClick, modifier = feedback.then(modifier), enabled = enabled,
            shape = shape, colors = colors, elevation = elevation, border = border,
            contentPadding = contentPadding, interactionSource = source) {
            DialogButtonForeground(plate) { content() }
            NativeButtonSurfaceProbe(nativeBounds, contentRows = 1)
        }
    }
}

internal fun GlassSettings.usesGlassDialogButtonMaterial(): Boolean =
    usesGlassMaterial(GlassArea.DialogButtons)

/** The Glass rim replaces a native outline while the dialog button material is active. */
@Composable
internal fun glassDialogButtonBorder(
    originalBorder: BorderStroke,
    settings: GlassSettings = rememberGlassSettings(),
): BorderStroke? = if (settings.usesGlassDialogButtonMaterial() || settings.usesGlassContour(GlassArea.DialogButtons)) null else originalBorder

/** Dialog action material; preserves the role fill and leaves content and input unchanged. */
@PublishedApi
@Composable
internal fun Modifier.glassDialogButtonBackground(
    backgroundColor: Color,
    shape: Shape,
    filmColor: Color = backgroundColor,
    settings: GlassSettings = rememberGlassSettings(),
): Modifier {
    if (!settings.usesGlassDialogButtonMaterial()) return background(backgroundColor).glassAreaContour(settings, GlassArea.DialogButtons, shape)

    // The shared modal capture excludes hosted dialogs. Native dialog windows must not sample
    // a window/page capture that could contain their own action.
    val dialogBackground = surfaceColor(colorPalette().background1)
    val drawBackground: DrawScope.() -> Unit = remember(dialogBackground) {
        { drawRect(dialogBackground) }
    }
    val backdrop = rememberCanvasBackdrop(drawBackground)
    val host = LocalGlassMenuHost.current
    val sharedBackdrop = if (host?.dialogs?.windowRoot === LocalView.current.rootView) host?.modalBackdrop else null
    return glassMaterial(
        settings = settings.forArea(GlassArea.DialogButtons),
        shape = shape,
        ownBackdrop = LocalGlassContainerBackdrop.current ?: sharedBackdrop ?: backdrop,
        menu = true,
        excludeBottomRim = false,
        filmColor = filmColor,
    )
}

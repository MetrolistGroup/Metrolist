package com.metrolist.android.ui.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metrolist.android.R
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.ui.theme.colorPalette

/** Glass keeps the transparent mini-player glyph; Material retains its filled native badge. */
@Composable
fun explicitBadgeIconId(settings: GlassSettings = rememberGlassSettings()): Int =
    if (settings.usesGlassMaterial(GlassArea.ActionButtons) || settings.usesGlassContour(GlassArea.ActionButtons))
        R.drawable.explicit_glass_glyph else R.drawable.explicit

/** Shared translation bubble with the original active tint and both native callbacks. */
@Composable
fun GlassDescriptionTranslate(
    color: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    settings: GlassSettings = rememberGlassSettings(),
    nativeContent: @Composable () -> Unit,
) {
    if (settings.usesGlassMaterial(GlassArea.ActionButtons)) {
        GlassToolbarRow(actionCount = 1, settings = settings) {
            GlassToolbarIcon(painterResource(R.drawable.translate), color, 18.dp, true,
                onClick = onClick, onLongClick = onLongClick)
        }
    } else if (settings.usesGlassContour(GlassArea.ActionButtons)) {
        Box(Modifier.glassAreaContour(settings, GlassArea.ActionButtons, uiRoundnessShape())) { nativeContent() }
    } else nativeContent()
}

/** Adds a text-button surface while retaining the exact native label when Glass is off. */
@Composable
fun GlassDescriptionExpansion(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable () -> Unit,
) {
    if (settings.usesGlassMaterial(GlassArea.ActionButtons) || settings.usesGlassContour(GlassArea.ActionButtons)) {
        PageTextButton(onClick = onClick, modifier = modifier, shape = uiRoundnessShape(), settings = settings) {
            content()
        }
    } else {
        content()
    }
}

/** Glass implementation only; the common ExplicitBadge selects the renderer. */
@Composable
internal fun GlassExplicitBadge(
    modifier: Modifier,
    settings: GlassSettings,
    passive: Boolean,
    contentDescription: String?,
) {
    // Rows can expose both a raw scene and their processed surface. The mini-player's
    // original badge samples the processed surface; keep that same source everywhere.
    val container = LocalGlassContainerBackdrop.current
    val rendered = container?.let {
        if (it is RenderedMaterialBackdrop) it else remember(it) { RenderedMaterialBackdrop(it) }
    }
    val shape = uiRoundnessShape()
    val badgeModifier = if (passive) {
        modifier
    } else {
        val interaction = remember { MutableInteractionSource() }
        // Apply motion outside the clip so it moves the whole badge.
        modifier.retainGlassButtonGesture().glassButtonFeedback(
            interaction, shape, enabled = isGlassMotionAllowed(), retainPressUntilUp = true,
            drawHighlight = false, settings = settings,
        )
    }
    val ink = if (settings.usesGlassMaterial(GlassArea.ActionButtons)) glassPaneForeground(
        colorPalette().text, effectiveSurfaceColor(surfaceBackdropColor(),
            glassFilmColor(settings.forArea(GlassArea.ActionButtons), role = null, menu = false)),
        IconContrast, glassSurfaceDarkness(settings), glyph = true,
    ) else colorPalette().text
    CompositionLocalProvider(LocalGlassContainerBackdrop provides rendered) {
        Box(badgeModifier) {
            Image(
                painter = painterResource(R.drawable.explicit_glass_glyph), contentDescription = contentDescription,
                colorFilter = ColorFilter.tint(ink),
                modifier = Modifier.matchParentSize().clip(shape)
                    .glassChipBackground(Color.Transparent, shape, settings, filmColor = null,
                        allowInNativeQueue = true)
                    .clip(shape).testTag("glass.explicit.glyph"),
            )
        }
    }
}

/** A passive badge surface: the icon remains sharp and gains no new input action. */
@Composable
fun Modifier.glassExplicitBadgeBackground(
    settings: GlassSettings = rememberGlassSettings(),
): Modifier {
    if (!settings.usesGlassMaterial(GlassArea.ActionButtons) && !settings.usesGlassContour(GlassArea.ActionButtons)) return this
    // Transparent is the native fill, not an instruction to strip the shared Glass film.
    val interaction = remember { MutableInteractionSource() }
    // Keep a drag that starts on the badge without adding any click action.
    return retainGlassButtonGesture().glassButtonFeedback(interaction, uiRoundnessShape(),
        enabled = isGlassMotionAllowed(), retainPressUntilUp = true, drawHighlight = false)
        .glassChipBackground(Color.Transparent, uiRoundnessShape(), settings, filmColor = null,
            allowInNativeQueue = true)

}

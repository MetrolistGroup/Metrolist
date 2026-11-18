package com.metrolist.android.ui.glass

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.metrolist.android.ui.component.InlineCircularLoader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.glass.backdrop.backdrops.rememberCanvasBackdrop
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.ui.component.nativeControlIndication
import com.metrolist.android.ui.component.reservedIconActionCanvas

/** Shared native action seat; the glyph remains independently sized. */
internal val NativeIconActionSize = 42.dp

/** Legacy glyph spacing is already partly supplied by the larger native action seat. */
private fun nativeSectionEndPadding(iconSize: Dp, endSpacing: Dp): Dp =
    (endSpacing - (NativeIconActionSize - iconSize).coerceAtLeast(0.dp)).coerceAtLeast(0.dp)

/** A section-heading action with its original native spacing. */
@Stable
internal data class GlassSectionAction(
    @DrawableRes val icon: Int,
    val contentDescription: String?,
    val nativeEndPadding: Dp = 0.dp,
    val iconModifier: Modifier = Modifier,
    val nativeIconSize: Dp = 24.dp,
    val nativeColor: Color? = null,
    val loading: Boolean = false,
    val separateBefore: Boolean = false,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** One action forms a bubble; several share a capsule and its short separators. */
@Composable
internal fun GlassSectionActions(
    actions: List<GlassSectionAction>,
    modifier: Modifier = Modifier,
    settings: GlassSettings = rememberGlassSettings(),
    nativeContent: (@Composable () -> Unit)? = null,
) {
    if (actions.isEmpty()) return
    if (settings.usesGlassMaterial(GlassArea.ActionButtons)) {
        val singleAction = actions.singleOrNull()?.takeUnless { it.loading }
        if (singleAction != null) {
            val backgroundColor = colorPalette().background0
            val drawBackground: DrawScope.() -> Unit = remember(backgroundColor) { { drawRect(backgroundColor) } }
            // A supplied container is child-free; otherwise avoid sampling the ancestor page capture.
            val fallbackBackdrop = rememberCanvasBackdrop(drawBackground)
            val backdrop = LocalGlassContainerBackdrop.current ?: fallbackBackdrop
            Box(modifier.size(GlassActionCellWidth, GlassActionCellHeight), contentAlignment = Alignment.Center) {
                LiquidButton(
                    onClick = singleAction.onClick,
                    onLongClick = singleAction.onLongClick,
                    backdrop = backdrop,
                    modifier = Modifier.size(GlassStandaloneActionSize),
                    settings = settings.forArea(GlassArea.ActionButtons),
                    filmColor = glassActionFilmColor(),
                    contentHeight = GlassStandaloneActionSize,
                    contentPadding = 0.dp,
                    shape = uiRoundnessShape(),
                ) {
                    Icon(
                        painterResource(singleAction.icon), singleAction.contentDescription,
                        tint = colorPalette().textSecondary,
                        modifier = Modifier.size(GlassActionIconSize).then(singleAction.iconModifier),
                    )
                }
            }
            return
        }
        GlassActionGroup(settings, GlassArea.ActionButtons, modifier = modifier,
            separatorBefore = actions.indices.filter { it > 0 && actions[it].separateBefore }.toSet()) {
            actions.forEach { action ->
                if (action.loading) {
                    Box(
                        Modifier.size(if (actions.size == 1) GlassStandaloneActionSize else GlassActionCellWidth, GlassActionCellHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        InlineCircularLoader(Modifier.size(18.dp), color = colorPalette().textSecondary)
                    }
                } else {
                    GlassActionGroupItem(
                        onClick = action.onClick,
                        onLongClick = action.onLongClick,
                    ) {
                        Icon(
                            painterResource(action.icon), action.contentDescription,
                            tint = colorPalette().textSecondary,
                            modifier = Modifier.size(GlassActionIconSize).then(action.iconModifier),
                        )
                    }
                }
            }
        }
    } else {
        val shape = uiRoundnessShape()
        Row(
            modifier = modifier.glassAreaContour(settings, GlassArea.ActionButtons, shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (nativeContent != null) {
                nativeContent()
            } else {
                actions.forEach { action ->
                    if (action.loading) {
                        Box(Modifier.padding(end = nativeSectionEndPadding(action.nativeIconSize, action.nativeEndPadding))
                            .size(NativeIconActionSize), contentAlignment = Alignment.Center) {
                            InlineCircularLoader(Modifier.size(18.dp),
                                color = colorPalette().textSecondary)
                        }
                    } else {
                        NativeSectionIconAction(
                            icon = action.icon,
                            contentDescription = action.contentDescription,
                            endSpacing = action.nativeEndPadding,
                            iconModifier = action.iconModifier,
                            iconSize = action.nativeIconSize,
                            color = action.nativeColor ?: colorPalette().textSecondary,
                            settings = settings,
                            onLongClick = action.onLongClick,
                            onClick = action.onClick,
                        )
                    }
                }
            }
        }
    }
}

/** Native section actions share the mini-player action seat while retaining their glyph size. */
@Composable
internal fun NativeSectionIconAction(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    color: Color = colorPalette().textSecondary,
    settings: GlassSettings = rememberGlassSettings(),
    endSpacing: Dp = 0.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = uiRoundnessShape()
    Box(modifier.padding(end = nativeSectionEndPadding(iconSize, endSpacing)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(NativeIconActionSize)
            .glassAreaContour(settings, GlassArea.ActionButtons, shape)
            .reservedIconActionCanvas(shape, remember { MutableInteractionSource() },
                glassGlobalIndication(nativeControlIndication()), enabled = true,
                onClick = onClick, onLongClick = onLongClick), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription, tint = color,
                modifier = Modifier.size(iconSize).then(iconModifier))
        }
    }
}

package com.metrolist.android.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.theme.uiRoundnessShape
import com.metrolist.android.ui.theme.surfaceColor

/** Connected vertical action cells, sharing one themed surface in both rendering modes. */
@Composable
fun GlassVerticalActionGroup(
    settings: GlassSettings,
    area: GlassArea,
    modifier: Modifier = Modifier,
    trailingTrigger: GlassMenuTriggerState? = null,
    actionCount: Int = 2,
    leadingActionJoinProgress: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cellSize = LocalGlassActionCellSize.current
    val shape = uiRoundnessShape()
    val transferred = trailingTrigger?.isTransferred == true
    val rejoin = remember { Animatable(if (transferred) 0f else 1f) }
    val motion = isGlassMotionAllowed()
    LaunchedEffect(transferred, motion) {
        if (transferred || !motion) rejoin.snapTo(if (transferred) 0f else 1f)
        else rejoin.animateTo(1f, glassGroupRejoinSpec())
    }
    val film = surfaceColor(colorPalette().background1)
    val separatorColor = glassEdgeLightColor(settings, glassTint(settings))
        .copy(alpha = settings.rim.coerceIn(0f, 1f) * .65f)
    val leadingJoin = !transferred && rejoin.value == 1f && leadingActionJoinProgress != null
    val progress = if (leadingJoin) leadingActionJoinProgress ?: 1f else rejoin.value
    val cellHeight = with(LocalDensity.current) { cellSize.height.toPx() }
    val fusion = remember(shape, cellHeight, progress, leadingJoin) {
        if (leadingJoin) glassActionJoinShape(shape, cellHeight, progress,
            fixedTrailingCell = true, vertical = true)
        else glassGroupFusionShape(shape, cellHeight, progress, vertical = true)
    }
    GlassActionGroupMotionHost(
        settings = settings,
        enabled = settings.usesGlassMaterial(area) && motion && !transferred,
        modifier = modifier,
    ) {
        val surface = Modifier.matchParentSize().layout { measurable, constraints ->
            val removed = if (transferred) cellSize.height.roundToPx() else 0
            val height = (constraints.maxHeight - removed).coerceAtLeast(0)
            val pane = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
            layout(constraints.maxWidth, constraints.maxHeight) { pane.place(0, 0) }
        }.graphicsLayer { alpha = if (actionCount == 1 && transferred) 0f else 1f }.clip(if (transferred || actionCount < 2) shape else fusion)
        val joining = !transferred && actionCount > 1 && progress < 1f
        Box(if (settings.usesGlassMaterial(area)) surface.glassMaterial(
            settings.forArea(area), shape, filmColor = film,
            highlightAlpha = if (joining) 0f else .3f,
        ).then(if (joining && settings.usesGlassContour(area))
            Modifier.glassEdgeRim(settings, fusion, glassTint(settings)) else Modifier)
        else surface.glassAreaContour(settings, area, if (joining) fusion else shape))
        Column(
            modifier = (if (settings.usesGlassMaterial(area) && !transferred) Modifier.clip(fusion) else Modifier).drawWithContent {
                drawContent()
                if (!transferred && actionCount > 1 && settings.usesGlassSeparator(area)) {
                    val cell = cellSize.height.toPx()
                    val halfWidth = 10.dp.toPx().coerceAtMost(size.width / 4f)
                    var y = (size.height - cell * (actionCount - 1)).coerceAtLeast(0f)
                    while (y < size.height) {
                        drawLine(separatorColor.copy(alpha = separatorColor.alpha * (y / cell).coerceIn(0f, 1f)),
                            Offset(size.width / 2f - halfWidth, y),
                            Offset(size.width / 2f + halfWidth, y), 1.dp.toPx())
                        y += cell
                    }
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            content = {
                val column = this
                if (settings.usesGlassMaterial(area)) {
                    val areaSettings = settings.forArea(area)
                    val plate = glassFilmColor(areaSettings, film, menu = false)
                    val foregroundSurface = effectiveSurfaceColor(surfaceBackdropColor(), plate)
                    CompositionLocalProvider(LocalGlassActionGroupSurface provides foregroundSurface) {
                        SurfaceForegroundContent(foregroundSurface, glassInkDarkness = glassSurfaceDarkness(areaSettings)) {
                            content(column)
                        }
                    }
                } else content(column)
            },
        )
    }
}

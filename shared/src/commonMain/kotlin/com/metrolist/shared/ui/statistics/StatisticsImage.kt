@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.statistics

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.metrolist.shared.ui.screens.StatisticsItem
import com.metrolist.shared.ui.screens.StatisticsUiState
import kotlin.math.roundToInt

/** Colors for a ranking section card: its container, titles, ranks and secondary text. */
internal data class StatisticsImageSection(
    val container: Color,
    val content: Color,
    val accent: Color,
    val muted: Color,
)

/** Colors for the exported image; [glow] adds soft radial light behind the content when set. */
internal data class StatisticsImageColors(
    val background: Color,
    val glow: Color?,
    val content: Color,
    val muted: Color,
    val accent: Color,
    val heroContainer: Color,
    val heroContent: Color,
    val sections: List<StatisticsImageSection>,
    val artworkPlaceholder: Color,
)

internal fun ColorScheme.statisticsImageColors() =
    StatisticsImageColors(
        background = surface,
        glow = null,
        content = onSurface,
        muted = onSurfaceVariant,
        accent = primary,
        heroContainer = primaryContainer,
        heroContent = onPrimaryContainer,
        sections = listOf(StatisticsImageSection(surfaceContainer, onSurface, primary, onSurfaceVariant)),
        artworkPlaceholder = secondaryContainer,
    )

internal fun StatisticsUiState.canExportStatistics(): Boolean = hasLoaded && !isLoading && errorMessage == null && totalPlays > 0

internal fun renderStatisticsImage(
    appName: String,
    title: String,
    period: String,
    summary: String,
    plays: String,
    duration: String,
    playsLabel: String,
    timeLabel: String,
    sections: List<Pair<String, List<StatisticsItem>>>,
    artwork: Map<String, ImageBitmap>,
    artworkShapes: List<List<Shape>>,
    colors: StatisticsImageColors,
    typography: Typography,
    heroShape: Shape,
    sectionShapes: List<Shape>,
    textMeasurer: TextMeasurer,
    layoutDirection: LayoutDirection,
    logo: Painter? = null,
    footer: String = "",
): ImageBitmap {
    val width = 1080
    val padding = 48f
    val contentWidth = width - padding.toInt() * 2
    val density = Density(2f)
    val gap = 16f
    val inset = 24f
    val artworkSize = 128
    val rankWidth = 60
    val textWidth = contentWidth - (inset * 2).toInt() - rankWidth - artworkSize - 48

    fun measure(
        text: String,
        style: TextStyle,
        color: Color = colors.content,
        maxWidth: Int = contentWidth,
        lines: Int = 1,
    ) = textMeasurer.measure(
        text,
        style = style.copy(color = color),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidth),
        density = density,
        layoutDirection = layoutDirection,
    )

    // With a logo, the app name becomes a large lockup beside it instead of a small label.
    val branding = measure(appName, if (logo != null) typography.headlineMediumEmphasized else typography.labelLarge, colors.accent)
    val logoSize = if (logo != null) (branding.size.height * 1.5f).roundToInt() else 0
    val brandingHeight = maxOf(branding.size.height, logoSize)
    val heading = measure(title, typography.headlineLargeEmphasized, lines = 2)
    val range = measure(period, typography.titleMedium, colors.accent, lines = 2)
    val description = summary.takeIf { it.isNotBlank() }?.let { measure(it, typography.bodyMedium, colors.muted, lines = Int.MAX_VALUE) }
    val footerText = footer.takeIf { it.isNotBlank() }?.let { measure(it, typography.bodySmall, colors.muted, lines = Int.MAX_VALUE) }
    val metricWidth = (contentWidth - inset * 3).toInt() / 2
    val metrics =
        listOf(
            measure(plays, typography.displayLargeEmphasized, colors.heroContent, metricWidth, 2) to
                measure(playsLabel, typography.titleMediumEmphasized, colors.heroContent, metricWidth, 2),
            measure(duration, typography.displaySmallEmphasized, colors.heroContent, metricWidth, 2) to
                measure(timeLabel, typography.titleMediumEmphasized, colors.heroContent, metricWidth, 2),
        )
    // Labels share one baseline under the taller value, so differently sized figures still line up.
    val valueHeight = metrics.maxOf { (value, _) -> value.size.height }
    val metricHeight = valueHeight + metrics.maxOf { (_, label) -> label.size.height } + inset * 2
    val sectionColors = sections.indices.map { colors.sections[it % colors.sections.size] }
    val labels = sections.mapIndexed { index, (label, _) -> measure(label, typography.titleLargeEmphasized, sectionColors[index].accent) }
    val rankings =
        sections.mapIndexed { sectionIndex, (_, items) ->
            val section = sectionColors[sectionIndex]
            items.take(3).mapIndexed { index, item ->
                Triple(
                    measure((index + 1).toString(), typography.headlineSmallEmphasized, section.accent, rankWidth),
                    measure(item.title, typography.titleLargeEmphasized, section.content, textWidth, 2),
                    measure(item.subtitle, typography.bodyMedium, section.muted, textWidth),
                )
            }
        }
    val rowHeights =
        rankings.map { rows ->
            rows.map { (_, name, subtitle) -> maxOf(artworkSize, name.size.height + subtitle.size.height + 8) }
        }
    val sectionHeights =
        labels.mapIndexed { index, label ->
            inset * 2 + label.size.height + gap + rowHeights[index].sum() + gap * (rowHeights[index].size - 1).coerceAtLeast(0)
        }
    val headerHeight =
        brandingHeight + heading.size.height + range.size.height + (description?.let { it.size.height + gap } ?: 0f).toInt() +
            gap * 3 + metricHeight
    val height = (padding * 2 + headerHeight + sectionHeights.sum() + gap * sections.size + (footerText?.size?.height ?: 0)).roundToInt()
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(density, layoutDirection, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        drawRect(colors.background)
        colors.glow?.let { glow ->
            val lights = listOf(Offset(width * 0.85f, height * 0.08f) to 0.55f, Offset(width * 0.1f, height * 0.55f) to 0.4f)
            lights.forEach { (center, alpha) ->
                val radius = width * 0.9f
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = alpha), glow.copy(alpha = 0f)), center, radius), radius, center)
            }
        }
        val rtl = layoutDirection == LayoutDirection.Rtl

        fun x(
            start: Float,
            elementWidth: Int,
        ): Float = if (rtl) width - start - elementWidth else start

        fun textAt(
            text: TextLayoutResult,
            start: Float,
            top: Float,
        ) {
            drawText(text, topLeft = Offset(x(start, text.size.width), top))
        }
        var y = padding
        if (logo != null) {
            translate(x(padding, logoSize), y + (brandingHeight - logoSize) / 2f) {
                with(logo) { draw(Size(logoSize.toFloat(), logoSize.toFloat())) }
            }
            textAt(branding, padding + logoSize + 20f, y + (brandingHeight - branding.size.height) / 2f)
        } else {
            textAt(branding, padding, y)
        }
        y += brandingHeight + gap
        for (text in listOfNotNull(heading, range, description)) {
            textAt(text, padding, y)
            y += text.size.height + gap
        }
        translate(padding, y) {
            drawOutline(heroShape.createOutline(Size(contentWidth.toFloat(), metricHeight), layoutDirection, this), colors.heroContainer)
        }
        metrics.forEachIndexed { index, (value, label) ->
            val start = padding + inset + index * (metricWidth + inset)
            textAt(value, start, y + inset + valueHeight - value.size.height)
            textAt(label, start, y + inset + valueHeight)
        }
        y += metricHeight + gap
        sections.forEachIndexed { sectionIndex, (_, items) ->
            translate(padding, y) {
                drawOutline(
                    sectionShapes[sectionIndex].createOutline(
                        Size(contentWidth.toFloat(), sectionHeights[sectionIndex]),
                        layoutDirection,
                        this,
                    ),
                    sectionColors[sectionIndex].container,
                )
            }
            y += inset
            textAt(labels[sectionIndex], padding + inset, y)
            y += labels[sectionIndex].size.height + gap
            rankings[sectionIndex].forEachIndexed { index, (rank, name, subtitle) ->
                val rowHeight = rowHeights[sectionIndex][index]
                val imageStart = padding + inset + rankWidth + 16
                val imageTop = y + (rowHeight - artworkSize) / 2f
                textAt(rank, padding + inset, y + (rowHeight - rank.size.height) / 2f)
                translate(x(imageStart, artworkSize), imageTop) {
                    val outline =
                        artworkShapes[sectionIndex][index].createOutline(
                            Size(artworkSize.toFloat(), artworkSize.toFloat()),
                            layoutDirection,
                            this,
                        )
                    val path = Path().apply { addOutline(outline) }
                    clipPath(path) {
                        drawRect(colors.artworkPlaceholder)
                        artwork[items[index].thumbnailUrl]?.let { image ->
                            val crop = minOf(image.width, image.height)
                            drawImage(
                                image,
                                srcOffset = IntOffset((image.width - crop) / 2, (image.height - crop) / 2),
                                srcSize = IntSize(crop, crop),
                                dstSize = IntSize(artworkSize, artworkSize),
                            )
                        }
                    }
                }
                val textStart = imageStart + artworkSize + 24
                val textTop = y + (rowHeight - name.size.height - subtitle.size.height - 8) / 2f
                textAt(name, textStart, textTop)
                textAt(subtitle, textStart, textTop + name.size.height + 8)
                y += rowHeight + gap
            }
            y += inset
        }
        footerText?.let { textAt(it, padding, y) }
    }
    return bitmap
}

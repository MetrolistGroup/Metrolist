package com.metrolist.music.ui.screens.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.eq.data.FilterType
import com.metrolist.music.eq.data.ParametricEQBand
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Biquad coefficients (normalized, a0 = 1)
private data class BiquadCoeffs(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double
)

private const val SAMPLE_RATE = 48000
private const val MIN_FREQ = 20.0
private const val MAX_FREQ = 20000.0
private const val GRAPH_PADDING_LEFT = 40f
private const val GRAPH_PADDING_RIGHT = 8f
private const val GRAPH_PADDING_TOP = 8f
private const val GRAPH_PADDING_BOTTOM = 24f

/**
 * Frequency response graph for an EQ profile.
 * Draws a curve showing the combined magnitude response of all bands.
 */
@Composable
fun EqFrequencyResponseGraph(
    bands: List<ParametricEQBand>,
    preamp: Double,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val curveColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    val zeroLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    // Pre-compute coefficients and response curve
    val graphData = remember(bands, preamp) {
        computeGraphData(bands, preamp)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = surfaceColor,
        tonalElevation = 2.dp
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(4.dp)
        ) {
            val plotLeft = GRAPH_PADDING_LEFT
            val plotRight = size.width - GRAPH_PADDING_RIGHT
            val plotTop = GRAPH_PADDING_TOP
            val plotBottom = size.height - GRAPH_PADDING_BOTTOM
            val plotWidth = plotRight - plotLeft
            val plotHeight = plotBottom - plotTop

            val dbTop = graphData.dbTop
            val dbBottom = graphData.dbBottom
            val dbRange = graphData.dbRange
            val dbStep = graphData.dbStep

            // Helper: frequency -> x
            fun freqToX(freq: Double): Float {
                val logMin = log10(MIN_FREQ)
                val logMax = log10(MAX_FREQ)
                val logF = log10(freq)
                return plotLeft + ((logF - logMin) / (logMax - logMin) * plotWidth).toFloat()
            }

            // Helper: dB -> y
            fun dbToY(db: Double): Float {
                return plotTop + ((dbTop - db) / dbRange * plotHeight).toFloat()
            }

            // Draw horizontal grid lines + dB labels
            clipRect(0f, 0f, size.width, size.height) {
                var db = dbBottom
                while (db <= dbTop) {
                    val y = dbToY(db)
                    val isZero = abs(db) < 0.01
                    drawLine(
                        color = if (isZero) zeroLineColor else gridColor,
                        start = Offset(plotLeft, y),
                        end = Offset(plotRight, y),
                        strokeWidth = if (isZero) 1.5f else 0.5f
                    )
                    // Label
                    val labelText = if (db == 0.0) "0" else {
                        if (db == db.toLong().toDouble()) db.toLong().toString()
                        else String.format("%.1f", db)
                    }
                    drawDbLabel(textMeasurer, labelText, labelStyle, y, plotLeft)
                    db += dbStep
                }

                // Draw vertical grid lines + Hz labels
                val freqLandmarks = listOf(100.0, 1000.0, 10000.0)
                val freqLabels = listOf("100", "1k", "10k")
                for (i in freqLandmarks.indices) {
                    val x = freqToX(freqLandmarks[i])
                    drawLine(
                        color = gridColor,
                        start = Offset(x, plotTop),
                        end = Offset(x, plotBottom),
                        strokeWidth = 0.5f
                    )
                    val measured = textMeasurer.measure(freqLabels[i], labelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(x - measured.size.width / 2f, plotBottom + 4f)
                    )
                }

                // Draw the response curve
                if (graphData.points.isNotEmpty()) {
                    val curvePath = Path()
                    val fillPath = Path()

                    val firstX = freqToX(graphData.points.first().first)
                    val firstY = dbToY(graphData.points.first().second)
                    curvePath.moveTo(firstX, firstY)
                    fillPath.moveTo(firstX, plotBottom)
                    fillPath.lineTo(firstX, firstY)

                    for (i in 1 until graphData.points.size) {
                        val (freq, db2) = graphData.points[i]
                        val x = freqToX(freq)
                        val y = dbToY(db2)
                        curvePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }

                    // Close fill path down to bottom of plot
                    val lastX = freqToX(graphData.points.last().first)
                    fillPath.lineTo(lastX, plotBottom)
                    fillPath.close()

                    drawPath(fillPath, color = fillColor)
                    drawPath(curvePath, color = curveColor, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

private fun DrawScope.drawDbLabel(
    textMeasurer: TextMeasurer,
    text: String,
    style: TextStyle,
    y: Float,
    plotLeft: Float
) {
    val measured = textMeasurer.measure(text, style)
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(plotLeft - measured.size.width - 4f, y - measured.size.height / 2f)
    )
}

// Pre-computed graph data
private data class GraphData(
    val points: List<Pair<Double, Double>>, // (frequency, totalDb)
    val dbTop: Double,    // top of visible range
    val dbBottom: Double, // bottom of visible range
    val dbRange: Double,  // dbTop - dbBottom
    val dbStep: Double
)

private fun computeGraphData(bands: List<ParametricEQBand>, preamp: Double): GraphData {
    val enabledBands = bands.filter { it.enabled }

    // Compute coefficients for each band
    val coeffsList = enabledBands.map { band ->
        computeBiquadCoefficients(band.frequency, band.gain, band.q, band.filterType)
    }

    // Sample frequencies logarithmically
    val numPoints = 200
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val points = mutableListOf<Pair<Double, Double>>()

    var minDb = 0.0
    var maxDb = 0.0

    for (i in 0 until numPoints) {
        val logF = logMin + (logMax - logMin) * i / (numPoints - 1)
        val freq = 10.0.pow(logF)

        var totalDb = 0.0
        for (coeffs in coeffsList) {
            totalDb += magnitudeResponseDb(coeffs, freq)
        }

        points.add(freq to totalDb)
        if (totalDb < minDb) minDb = totalDb
        if (totalDb > maxDb) maxDb = totalDb
    }

    // Always center on 0 dB — symmetric range based on whichever extreme is larger
    val dbStep = 2.5
    val peakAbs = max(abs(minDb), abs(maxDb))
    val halfRange = max(ceil((peakAbs + 1.0) / dbStep) * dbStep, dbStep)

    return GraphData(
        points = points,
        dbTop = halfRange,
        dbBottom = -halfRange,
        dbRange = 2.0 * halfRange,
        dbStep = dbStep
    )
}

private fun computeBiquadCoefficients(
    frequency: Double,
    gain: Double,
    q: Double,
    filterType: FilterType
): BiquadCoeffs {
    val omega = 2.0 * PI * frequency / SAMPLE_RATE
    val sinOmega = sin(omega)
    val cosOmega = cos(omega)

    var b0: Double
    var b1: Double
    var b2: Double
    var a0: Double
    var a1: Double
    var a2: Double

    when (filterType) {
        FilterType.PK -> {
            val A = 10.0.pow(gain / 40.0)
            val alpha = sinOmega / (2.0 * q)
            b0 = 1.0 + alpha * A
            b1 = -2.0 * cosOmega
            b2 = 1.0 - alpha * A
            a0 = 1.0 + alpha / A
            a1 = -2.0 * cosOmega
            a2 = 1.0 - alpha / A
        }
        FilterType.LSC -> {
            val A = sqrt(10.0.pow(gain / 20.0))
            val S = 1.0
            val alpha = sinOmega / 2.0 * sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0)
            val sqrtA = sqrt(A)
            val aPlusOne = A + 1.0
            val aMinusOne = A - 1.0
            val twoSqrtAAlpha = 2.0 * sqrtA * alpha
            b0 = A * (aPlusOne - aMinusOne * cosOmega + twoSqrtAAlpha)
            b1 = 2.0 * A * (aMinusOne - aPlusOne * cosOmega)
            b2 = A * (aPlusOne - aMinusOne * cosOmega - twoSqrtAAlpha)
            a0 = aPlusOne + aMinusOne * cosOmega + twoSqrtAAlpha
            a1 = -2.0 * (aMinusOne + aPlusOne * cosOmega)
            a2 = aPlusOne + aMinusOne * cosOmega - twoSqrtAAlpha
        }
        FilterType.HSC -> {
            val A = sqrt(10.0.pow(gain / 20.0))
            val S = 1.0
            val alpha = sinOmega / 2.0 * sqrt((A + 1.0 / A) * (1.0 / S - 1.0) + 2.0)
            val sqrtA = sqrt(A)
            val aPlusOne = A + 1.0
            val aMinusOne = A - 1.0
            val twoSqrtAAlpha = 2.0 * sqrtA * alpha
            b0 = A * (aPlusOne + aMinusOne * cosOmega + twoSqrtAAlpha)
            b1 = -2.0 * A * (aMinusOne + aPlusOne * cosOmega)
            b2 = A * (aPlusOne + aMinusOne * cosOmega - twoSqrtAAlpha)
            a0 = aPlusOne - aMinusOne * cosOmega + twoSqrtAAlpha
            a1 = 2.0 * (aMinusOne - aPlusOne * cosOmega)
            a2 = aPlusOne - aMinusOne * cosOmega - twoSqrtAAlpha
        }
        FilterType.LPQ -> {
            val alpha = sinOmega / (2.0 * q)
            b0 = (1.0 - cosOmega) / 2.0
            b1 = 1.0 - cosOmega
            b2 = (1.0 - cosOmega) / 2.0
            a0 = 1.0 + alpha
            a1 = -2.0 * cosOmega
            a2 = 1.0 - alpha
        }
        FilterType.HPQ -> {
            val alpha = sinOmega / (2.0 * q)
            b0 = (1.0 + cosOmega) / 2.0
            b1 = -(1.0 + cosOmega)
            b2 = (1.0 + cosOmega) / 2.0
            a0 = 1.0 + alpha
            a1 = -2.0 * cosOmega
            a2 = 1.0 - alpha
        }
    }

    // Normalize
    b0 /= a0
    b1 /= a0
    b2 /= a0
    a1 /= a0
    a2 /= a0

    return BiquadCoeffs(b0, b1, b2, a1, a2)
}

private fun magnitudeResponseDb(coeffs: BiquadCoeffs, freq: Double): Double {
    val omega = 2.0 * PI * freq / SAMPLE_RATE
    val cosW = cos(omega)
    val cos2W = cos(2.0 * omega)
    val sinW = sin(omega)
    val sin2W = sin(2.0 * omega)

    val numReal = coeffs.b0 + coeffs.b1 * cosW + coeffs.b2 * cos2W
    val numImag = coeffs.b1 * sinW + coeffs.b2 * sin2W
    val denReal = 1.0 + coeffs.a1 * cosW + coeffs.a2 * cos2W
    val denImag = coeffs.a1 * sinW + coeffs.a2 * sin2W

    val numMagSq = numReal * numReal + numImag * numImag
    val denMagSq = denReal * denReal + denImag * denImag

    return if (denMagSq > 0.0) {
        10.0 * log10(max(numMagSq / denMagSq, 1e-10))
    } else {
        0.0
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Expressive design: ported from Expressive (see THIRD_PARTY notices upstream).
 */

package com.metrolist.shared.ui.design.expressive.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class ExpressiveMiniPlayerShape(
    private val topStart: Dp,
    private val topEnd: Dp,
    private val bottomEnd: Dp,
    private val bottomStart: Dp,
    private val smoothnessPercent: Int = 60,
) : Shape {
    constructor(radius: Dp, smoothnessPercent: Int = 60) : this(radius, radius, radius, radius, smoothnessPercent)

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val ltr = layoutDirection == LayoutDirection.Ltr
        val tl = with(density) { (if (ltr) topStart else topEnd).toPx() }
        val tr = with(density) { (if (ltr) topEnd else topStart).toPx() }
        val br = with(density) { (if (ltr) bottomEnd else bottomStart).toPx() }
        val bl = with(density) { (if (ltr) bottomStart else bottomEnd).toPx() }
        val smoothing = (smoothnessPercent / 100f).coerceIn(0f, 1f)
        val w = size.width
        val h = size.height
        val maxRadius = min(w, h) / 2f
        val path = Path()
        val tlP = CornerParams.of(min(tl, maxRadius), smoothing, min(w, h) / 2f)
        val trP = CornerParams.of(min(tr, maxRadius), smoothing, min(w, h) / 2f)
        val brP = CornerParams.of(min(br, maxRadius), smoothing, min(w, h) / 2f)
        val blP = CornerParams.of(min(bl, maxRadius), smoothing, min(w, h) / 2f)

        path.moveTo(tlP.p, 0f)
        path.lineTo(w - trP.p, 0f)
        trP.append(path, w - trP.p, 0f, 0)
        path.lineTo(w, h - brP.p)
        brP.append(path, w, h - brP.p, 1)
        path.lineTo(blP.p, h)
        blP.append(path, blP.p, h, 2)
        path.lineTo(0f, tlP.p)
        tlP.append(path, 0f, tlP.p, 3)
        path.close()
        return Outline.Generic(path)
    }

    private class CornerParams(
        val radius: Float,
        val p: Float,
        val a: Float,
        val b: Float,
        val c: Float,
        val d: Float,
        val arcLength: Float,
        val arcMeasureDeg: Float,
        val betaDeg: Float,
    ) {
        fun append(
            path: Path,
            startX: Float,
            startY: Float,
            quarterTurns: Int,
        ) {
            if (radius <= 0f) {
                return
            }

            fun rot(
                x: Float,
                y: Float,
            ): Pair<Float, Float> =
                when (quarterTurns % 4) {
                    0 -> x to y
                    1 -> -y to x
                    2 -> -x to -y
                    else -> y to -x
                }

            fun abs(
                x: Float,
                y: Float,
            ): Pair<Float, Float> {
                val (rx, ry) = rot(x, y)
                return (startX + rx) to (startY + ry)
            }
            val c1 = abs(a, 0f)
            val c2 = abs(a + b, 0f)
            val e1 = abs(a + b + c, d)
            path.cubicTo(c1.first, c1.second, c2.first, c2.second, e1.first, e1.second)
            val theta = arcMeasureDeg * PI.toFloat() / 180f
            val beta = betaDeg * PI.toFloat() / 180f
            val k = if (theta <= 0f) 0f else (4f / 3f) * tan(theta / 4f) * radius
            val sx = a + b + c
            val sy = d
            val ex = sx + arcLength
            val ey = sy + arcLength
            val u0x = cos(beta)
            val u0y = sin(beta)
            val u1x = cos(beta + theta)
            val u1y = sin(beta + theta)
            val ac1 = abs(sx + u0x * k, sy + u0y * k)
            val ac2 = abs(ex - u1x * k, ey - u1y * k)
            val ae = abs(ex, ey)
            path.cubicTo(ac1.first, ac1.second, ac2.first, ac2.second, ae.first, ae.second)
            val s1 = abs(ex + d, ey + c)
            val s2 = abs(ex + d, ey + b + c)
            val s3 = abs(ex + d, ey + a + b + c)
            path.cubicTo(s1.first, s1.second, s2.first, s2.second, s3.first, s3.second)
        }

        companion object {
            fun of(
                radius: Float,
                smoothing: Float,
                budget: Float,
            ): CornerParams {
                if (radius <= 0f) return CornerParams(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                var s = smoothing
                val maxSmoothing = budget / radius - 1f
                s = min(s, maxSmoothing).coerceAtLeast(0f)
                val p = min((1f + s) * radius, budget)
                val arcMeasure = 90f * (1f - s)
                val arcLen = sin(arcMeasure / 2f * PI.toFloat() / 180f) * radius * sqrt(2f)
                val alpha = (90f - arcMeasure) / 2f
                val p3p4 = radius * tan(alpha / 2f * PI.toFloat() / 180f)
                val beta = 45f * s
                val c = p3p4 * cos(beta * PI.toFloat() / 180f)
                val d = c * tan(beta * PI.toFloat() / 180f)
                val b = (p - arcLen - c - d) / 3f
                val a = 2f * b
                return CornerParams(radius, p, a, b, c, d, arcLen, arcMeasure, beta)
            }
        }
    }
}

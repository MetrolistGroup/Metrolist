package com.metrolist.shared.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.graphics.blur.BlurStop
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal actual val renderEffectBlurSupported: Boolean = true

@Composable
actual fun Modifier.queueVerticalBlur(
    blurHeight: Dp,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier =
    if (enabled) {
        this.blur {
            val h = size.height
            if (h > 0.dp) {
                val startFraction = (1f - (blurHeight.value / h.value)).coerceIn(0.5f, 0.95f)
                val midFraction = (startFraction + 1f) / 2f
                blurRadiusSpec =
                    BlurRadiusSpec.verticalGradient(
                        stops =
                            listOf(
                                BlurStop(fraction = startFraction, radius = 0.dp),
                                BlurStop(fraction = (startFraction + midFraction) / 2f, radius = maxRadius * 0.35f),
                                BlurStop(fraction = midFraction, radius = maxRadius * 0.65f),
                                BlurStop(fraction = 1.00f, radius = maxRadius),
                            ),
                    )
                edgeTreatment = BlurredEdgeTreatment.Unbounded
            }
        }
    } else {
        this
    }

@Composable
actual fun Modifier.artworkHorizontalProgressiveBlur(
    leftStartFraction: Float,
    leftEndFraction: Float,
    rightStartFraction: Float,
    rightEndFraction: Float,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier =
    if (enabled) {
        this.blur {
            val w = size.width
            if (w > 0.dp) {
                val stops = mutableListOf<BlurStop>()
                if (leftEndFraction > leftStartFraction) {
                    val leftMid = (leftStartFraction + leftEndFraction) / 2f
                    stops.add(BlurStop(fraction = leftStartFraction, radius = maxRadius))
                    stops.add(BlurStop(fraction = (leftStartFraction + leftMid) / 2f, radius = maxRadius * 0.65f))
                    stops.add(BlurStop(fraction = leftMid, radius = maxRadius * 0.35f))
                    stops.add(BlurStop(fraction = leftEndFraction, radius = 0.dp))
                } else {
                    stops.add(BlurStop(fraction = 0f, radius = 0.dp))
                }
                val midFraction = (rightStartFraction + rightEndFraction) / 2f
                stops.add(BlurStop(fraction = rightStartFraction, radius = 0.dp))
                stops.add(BlurStop(fraction = (rightStartFraction + midFraction) / 2f, radius = maxRadius * 0.35f))
                stops.add(BlurStop(fraction = midFraction, radius = maxRadius * 0.65f))
                stops.add(BlurStop(fraction = rightEndFraction, radius = maxRadius))

                blurRadiusSpec = BlurRadiusSpec.horizontalGradient(stops = stops)
                edgeTreatment = BlurredEdgeTreatment.Unbounded
            }
        }
    } else {
        this
    }

@Composable
actual fun Modifier.artworkVerticalProgressiveBlur(
    topStartFraction: Float,
    topEndFraction: Float,
    bottomStartFraction: Float,
    bottomEndFraction: Float,
    maxRadius: Dp,
    enabled: Boolean,
): Modifier =
    if (enabled) {
        this.blur {
            val h = size.height
            if (h > 0.dp) {
                val stops = mutableListOf<BlurStop>()
                if (topEndFraction > topStartFraction) {
                    val topMid = (topStartFraction + topEndFraction) / 2f
                    stops.add(BlurStop(fraction = topStartFraction, radius = maxRadius))
                    stops.add(BlurStop(fraction = (topStartFraction + topMid) / 2f, radius = maxRadius * 0.65f))
                    stops.add(BlurStop(fraction = topMid, radius = maxRadius * 0.35f))
                    stops.add(BlurStop(fraction = topEndFraction, radius = 0.dp))
                } else {
                    stops.add(BlurStop(fraction = 0f, radius = 0.dp))
                }
                val midFraction = (bottomStartFraction + bottomEndFraction) / 2f
                stops.add(BlurStop(fraction = bottomStartFraction, radius = 0.dp))
                stops.add(BlurStop(fraction = (bottomStartFraction + midFraction) / 2f, radius = maxRadius * 0.35f))
                stops.add(BlurStop(fraction = midFraction, radius = maxRadius * 0.65f))
                stops.add(BlurStop(fraction = bottomEndFraction, radius = maxRadius))

                blurRadiusSpec = BlurRadiusSpec.verticalGradient(stops = stops)
                edgeTreatment = BlurredEdgeTreatment.Unbounded
            }
        }
    } else {
        this
    }

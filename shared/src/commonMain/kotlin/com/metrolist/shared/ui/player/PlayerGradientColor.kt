package com.metrolist.shared.ui.player

import androidx.compose.ui.graphics.Color

fun interpolatePlayerGradientColor(
    start: Color,
    stop: Color,
    fraction: Float,
): Color =
    Color(
        red = start.red + fraction * (stop.red - start.red),
        green = start.green + fraction * (stop.green - start.green),
        blue = start.blue + fraction * (stop.blue - start.blue),
        alpha = start.alpha + fraction * (stop.alpha - start.alpha),
    )

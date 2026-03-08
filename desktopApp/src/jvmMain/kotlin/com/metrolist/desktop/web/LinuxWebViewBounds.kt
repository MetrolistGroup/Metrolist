package com.metrolist.desktop.web

import kotlin.math.roundToInt

internal data class LinuxWebViewBounds(
    val width: Int,
    val height: Int,
)

internal fun linuxWebViewBounds(
    width: Int,
    height: Int,
    awtScaleX: Double,
    awtScaleY: Double,
    gtkScale: Int,
): LinuxWebViewBounds {
    require(gtkScale > 0)
    require(awtScaleX.isFinite() && awtScaleX > 0)
    require(awtScaleY.isFinite() && awtScaleY > 0)
    return LinuxWebViewBounds(
        width = (width * awtScaleX / gtkScale).roundToInt().coerceAtLeast(1),
        height = (height * awtScaleY / gtkScale).roundToInt().coerceAtLeast(1),
    )
}

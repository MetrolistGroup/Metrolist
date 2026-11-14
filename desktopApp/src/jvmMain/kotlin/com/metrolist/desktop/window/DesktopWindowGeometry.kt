package com.metrolist.desktop.window

import java.awt.Point
import java.awt.Rectangle

internal fun restoreWindowPosition(
    saved: String,
    displays: List<Rectangle>,
): Point? {
    val coordinates = saved.split(',').map { it.toIntOrNull() }
    if (coordinates.size != 2) return null
    val x = coordinates[0] ?: return null
    val y = coordinates[1] ?: return null
    return Point(x, y).takeIf { displays.any { display -> display.contains(Rectangle(x, y, 64, 32)) } }
}

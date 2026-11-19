/*
 * Vendored from Kyant0/Capsule
 * https://github.com/Kyant0/Capsule - Copyright 2025 Kyant0, Apache License 2.0
 * capsule/src/main/java/com/kyant/capsule/MathUtils.kt
 *
 * Vendored so the shape ships as source with this app. Package renamed
 * accordingly (com.kyant.capsule -> com.metrolist.android.ui.glass.shapes).
 */
package com.metrolist.android.ui.glass.shapes

@Suppress("NOTHING_TO_INLINE")
internal inline fun lerp(start: Double, stop: Double, fraction: Double): Double {
    return start + (stop - start) * fraction
}

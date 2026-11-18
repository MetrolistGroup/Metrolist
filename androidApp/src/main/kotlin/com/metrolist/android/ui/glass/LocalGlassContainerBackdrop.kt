package com.metrolist.android.ui.glass

import androidx.compose.runtime.staticCompositionLocalOf
import com.metrolist.android.ui.glass.backdrop.Backdrop

/**
 * The nearest rendered container material, exported before drawing its children.
 * Never provide a capture containing the consuming controls: that creates draw feedback.
 */
val LocalGlassContainerBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Safe child-free source before any Glass film or optics; null means no independent source.
 * Owners replacing [LocalGlassContainerBackdrop] must also replace this source: a new scene
 * supplies itself, a Glass export supplies its input, and a native fill supplies its real fill.
 */
val LocalGlassMaterialSource = staticCompositionLocalOf<Backdrop?> { null }

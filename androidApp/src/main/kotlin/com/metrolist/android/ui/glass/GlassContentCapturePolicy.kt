package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable

/** Scene/list captures serve optical consumers; flat fills and material exports stay independent. */
internal fun glassNeedsContentCapture(settings: GlassSettings, renderer: GlassRenderer): Boolean =
    settings.enabled && renderer != GlassRenderer.Tint

@Composable
internal fun glassContentCaptureEnabled(settings: GlassSettings): Boolean =
    glassNeedsContentCapture(settings, glassRenderer(settings))

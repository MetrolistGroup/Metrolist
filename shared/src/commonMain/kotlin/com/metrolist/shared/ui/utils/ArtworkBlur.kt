package com.metrolist.shared.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

// Apply directly to artwork drawing, before any child graphics layers.
expect fun Modifier.artworkBlur(
    radius: Dp,
    backgroundColor: Color,
): Modifier

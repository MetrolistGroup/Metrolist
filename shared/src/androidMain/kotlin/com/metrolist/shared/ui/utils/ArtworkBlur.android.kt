package com.metrolist.shared.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

actual fun Modifier.artworkBlur(
    radius: Dp,
    backgroundColor: Color,
): Modifier = blur(radius)

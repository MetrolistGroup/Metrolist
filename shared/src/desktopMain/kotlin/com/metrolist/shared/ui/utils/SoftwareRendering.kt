package com.metrolist.shared.ui.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the main window rasterises on the CPU. Skiko repaints the whole scene for every change there,
 * so per-pixel shaders and blurs must be drawn once and reused, or drawn at a reduced size.
 */
var softwareRendering by mutableStateOf(false)

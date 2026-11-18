package com.metrolist.android.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.theme.surfaceColor

/** Retains the native elevated theme film, as navigation and header glass do. */
@Composable
internal fun glassActionFilmColor(): Color = surfaceColor(colorPalette().background1)

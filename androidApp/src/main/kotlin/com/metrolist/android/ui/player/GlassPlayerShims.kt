package com.metrolist.android.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.theme.ColorPalette

val PlayerInformationVerticalPadding: Dp = 12.dp
val LocalMiniPlayerFeedbackOverflow = compositionLocalOf { 0.dp }

class PlayerTransportGeometry {
    var host by mutableStateOf<LayoutCoordinates?>(null)
    var toolbarSnapshot by mutableStateOf<Rect?>(null)
    var visibleToolbarSnapshot by mutableStateOf<Rect?>(null)
    var actionPanelSnapshot by mutableStateOf<Rect?>(null)
}

val LocalPlayerTransportGeometry = staticCompositionLocalOf { PlayerTransportGeometry() }

data class ArtworkDisplayColors(
    val palette: ColorPalette? = null,
    val dominant: Color? = null,
    val background: Color? = null,
    val foreground: Color? = null,
    val forceDark: Boolean = false,
    val isMonochrome: Boolean = false,
)

data class ArtworkDisplayResult(
    val colors: ArtworkDisplayColors? = null,
    val resolved: Boolean = true
)

object SharedArtworkColors {
    fun invalidate() {}
}

@Composable
fun rememberArtworkDisplayResult(
    uri: String?,
    isDark: Boolean,
    revision: String? = null,
    awaitArtworkMetadata: Boolean = false,
): ArtworkDisplayResult {
    return remember(uri, isDark) {
        ArtworkDisplayResult(
            colors = ArtworkDisplayColors(
                dominant = Color.DarkGray,
                background = if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5),
                foreground = if (isDark) Color.White else Color.Black,
                forceDark = isDark,
                isMonochrome = false
            ),
            resolved = true
        )
    }
}

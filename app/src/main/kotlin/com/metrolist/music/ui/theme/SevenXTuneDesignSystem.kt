package com.metrolist.music.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 7xTune's visual language foundation.
 *
 * Keep brand primitives here so screen redesigns can share the same vocabulary
 * without leaking layout decisions between unrelated surfaces.
 */
object SevenXTunePalette {
    val Midnight = Color(0xFF090817)
    val MidnightSoft = Color(0xFF0F0E1D)
    val Surface = Color(0xFF141324)
    val SurfaceRaised = Color(0xFF1B1930)
    val SurfaceBright = Color(0xFF242141)

    val Violet = Color(0xFF9D72FF)
    val ElectricBlue = Color(0xFF67D8FF)
    val PulsePink = Color(0xFFFF76C8)

    val TextPrimary = Color(0xFFF5F1FF)
    val TextSecondary = Color(0xFFB8B2CC)
    val TextMuted = Color(0xFF858096)
}

object SevenXTuneSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 40.dp
}

val SevenXTuneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

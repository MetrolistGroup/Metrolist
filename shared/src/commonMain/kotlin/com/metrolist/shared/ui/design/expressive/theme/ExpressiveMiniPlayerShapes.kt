/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Expressive design: ported from Expressive.
 */

package com.metrolist.shared.ui.design.expressive.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ExpressiveMiniPlayerShapes =
    Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
    )

object ExpressiveMiniPlayerShapeCache {
    val smooth8 = ExpressiveMiniPlayerShape(8.dp)
    val smooth10 = ExpressiveMiniPlayerShape(10.dp)
    val smooth12 = ExpressiveMiniPlayerShape(12.dp)
    val smooth14 = ExpressiveMiniPlayerShape(14.dp)
    val smooth16 = ExpressiveMiniPlayerShape(16.dp)
    val smooth20 = ExpressiveMiniPlayerShape(20.dp)
    val smooth24 = ExpressiveMiniPlayerShape(24.dp)
    val smooth28 = ExpressiveMiniPlayerShape(28.dp)
    val smooth32 = ExpressiveMiniPlayerShape(32.dp)
    val smoothPill = ExpressiveMiniPlayerShape(50.dp)
}

package com.metrolist.shared.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButtonShapes
import com.metrolist.shared.ui.player.asStaticShape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun ToggleButtonShapes.withStaticCorners(): ToggleButtonShapes =
    copy(
        shape = shape.asStaticShape(),
        pressedShape = pressedShape.asStaticShape(),
        checkedShape = checkedShape.asStaticShape(),
    )

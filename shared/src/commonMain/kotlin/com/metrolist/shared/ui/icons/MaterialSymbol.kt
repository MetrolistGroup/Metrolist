package com.metrolist.shared.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun materialSymbol(
    name: String,
    pathData: String,
    autoMirror: Boolean = false,
    fill: Color = Color.Black,
    defaultSize: Dp = 24.dp,
): ImageVector =
    ImageVector
        .Builder(
            name = name,
            defaultWidth = defaultSize,
            defaultHeight = defaultSize,
            viewportWidth = 960f,
            viewportHeight = 960f,
            autoMirror = autoMirror,
        ).apply {
            addGroup(translationY = 960f)
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(fill),
            )
            clearGroup()
        }.build()

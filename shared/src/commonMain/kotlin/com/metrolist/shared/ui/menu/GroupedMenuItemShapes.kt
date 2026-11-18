package com.metrolist.shared.ui.menu

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun groupedMenuItemShapes(
    index: Int,
    count: Int,
    groupIndex: Int = 0,
    groupCount: Int = 1,
): MenuItemShapes {
    val shapes = MenuDefaults.itemShape(index, count)
    val group = MenuDefaults.groupShape(groupIndex, groupCount).shape
    return shapes.copy(shape = matchMenuGroupCorners(shapes.shape, group, index, count))
}

internal fun matchMenuGroupCorners(
    item: Shape,
    group: Shape,
    index: Int,
    count: Int,
): Shape {
    if (item !is CornerBasedShape || group !is CornerBasedShape) return item
    return item.copy(
        topStart = if (index == 0) group.topStart else item.topStart,
        topEnd = if (index == 0) group.topEnd else item.topEnd,
        bottomStart = if (index == count - 1) group.bottomStart else item.bottomStart,
        bottomEnd = if (index == count - 1) group.bottomEnd else item.bottomEnd,
    )
}

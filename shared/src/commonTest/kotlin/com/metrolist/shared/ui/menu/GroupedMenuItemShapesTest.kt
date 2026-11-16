package com.metrolist.shared.ui.menu

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GroupedMenuItemShapesTest {
    private val item = RoundedCornerShape(4.dp)
    private val group = RoundedCornerShape(topStart = 28.dp, topEnd = 24.dp, bottomStart = 20.dp, bottomEnd = 16.dp)

    @Test
    fun onlyOuterCornersFollowTheGroup() {
        assertEquals(
            RoundedCornerShape(topStart = 28.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
            matchMenuGroupCorners(item, group, 0, 3),
        )
        assertEquals(item, matchMenuGroupCorners(item, group, 1, 3))
        assertEquals(
            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 16.dp),
            matchMenuGroupCorners(item, group, 2, 3),
        )
        assertEquals(group, matchMenuGroupCorners(item, group, 0, 1))
    }

    @Test
    fun shapesWithoutCornersKeepTheOriginalItem() {
        assertSame(item, matchMenuGroupCorners(item, RectangleShape, 0, 1))
        assertSame(RectangleShape, matchMenuGroupCorners(RectangleShape, group, 0, 1))
    }
}

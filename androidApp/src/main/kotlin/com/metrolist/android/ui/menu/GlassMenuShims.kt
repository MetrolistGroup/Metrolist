package com.metrolist.android.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
fun CenteredMenuSheetDragHandle(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 4.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
fun MenuSheetDragHandle(modifier: Modifier = Modifier) {
    CenteredMenuSheetDragHandle(modifier)
}

@Composable
fun NativeMenuSheetDragHandle(modifier: Modifier = Modifier) {
    CenteredMenuSheetDragHandle(modifier)
}

fun straightBottomMenuShape(radius: androidx.compose.ui.unit.Dp = 16.dp): Shape {
    return RoundedCornerShape(topStart = radius, topEnd = radius, bottomStart = 0.dp, bottomEnd = 0.dp)
}

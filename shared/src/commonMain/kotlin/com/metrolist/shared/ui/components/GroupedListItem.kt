package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GroupedListItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isCurrent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val topRadius = if (index == 0) 16.dp else 4.dp
    val bottomRadius = if (index == count - 1) 16.dp else 4.dp

    val colors =
        animateCurrentSongColors(
            isCurrent = isCurrent,
            container = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        )

    Box(modifier.padding(bottom = if (index < count - 1) 3.dp else 0.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape =
                if (isCurrent) {
                    CircleShape
                } else {
                    RoundedCornerShape(
                        topStart = topRadius,
                        topEnd = topRadius,
                        bottomStart = bottomRadius,
                        bottomEnd = bottomRadius,
                    )
                },
            color = colors.container,
            content = content,
        )
    }
}

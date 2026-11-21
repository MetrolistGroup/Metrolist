package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun HoverTooltip(
    text: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)

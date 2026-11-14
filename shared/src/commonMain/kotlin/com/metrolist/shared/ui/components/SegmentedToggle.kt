package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SegmentedToggle(
    selectedIndex: Int,
    labels: List<String>,
    modifier: Modifier = Modifier,
    onSelected: (Int) -> Unit,
) {
    require(labels.size >= 2) { "SegmentedToggle requires at least 2 labels" }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            labels.forEachIndexed { index, label ->
                val shapes =
                    if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    }
                ToggleButton(
                    checked = selectedIndex == index,
                    onCheckedChange = { onSelected(index) },
                    modifier = Modifier.fillMaxHeight(),
                    shapes = shapes,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

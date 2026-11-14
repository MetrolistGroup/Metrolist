package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog as MaterialAlertDialog
import androidx.compose.material3.BasicAlertDialog as MaterialBasicAlertDialog

// Android dialog windows replace LocalDensity, so restore the app appearance scale inside them.

@Composable
fun BasicAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    MaterialBasicAlertDialog(
        onDismissRequest,
        modifier.popupMotion(),
        properties,
    ) {
        WithDensity(density, content)
    }
}

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val density = LocalDensity.current
    val scaled = { slot: @Composable () -> Unit -> @Composable { WithDensity(density, slot) } }
    // Material buttons only draw a faint focus layer; outline the focused action for remotes and keyboards.
    val action = { slot: @Composable () -> Unit -> @Composable { Box(Modifier.focusOutline(CircleShape)) { WithDensity(density, slot) } } }
    MaterialAlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = action(confirmButton),
        modifier = modifier.popupMotion(),
        dismissButton = dismissButton?.let(action),
        icon = icon?.let(scaled),
        title = title?.let(scaled),
        text = text?.let(scaled),
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

@Composable
private fun WithDensity(
    density: Density,
    content: @Composable () -> Unit,
) = CompositionLocalProvider(LocalDensity provides density, content = content)

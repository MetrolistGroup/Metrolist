package com.metrolist.shared.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal actual fun ModalSheetDialog(
    onDismissRequest: suspend () -> Unit,
    properties: ModalBottomSheetProperties,
    contentColor: Color,
    content: @Composable (backProgress: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val onDismiss = rememberUpdatedState(onDismissRequest)
    Dialog(
        onDismissRequest = { scope.launch { onDismiss.value() } },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                securePolicy = properties.securePolicy,
                dismissOnBackPress = properties.shouldDismissOnBackPress,
                dismissOnClickOutside = properties.shouldDismissOnClickOutside,
            ),
    ) {
        val progress by rememberSheetBackProgress(
            enabled = properties.shouldDismissOnBackPress,
            onBack = { onDismiss.value() },
        )
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = contentColor.luminance() < 0.5f
                isAppearanceLightNavigationBars = contentColor.luminance() < 0.5f
            }
        }
        content(progress)
    }
}

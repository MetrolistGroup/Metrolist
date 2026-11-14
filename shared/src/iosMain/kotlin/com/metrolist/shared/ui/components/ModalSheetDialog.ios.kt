package com.metrolist.shared.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun ModalSheetDialog(
    onDismissRequest: suspend () -> Unit,
    properties: ModalBottomSheetProperties,
    contentColor: Color,
    content: @Composable (backProgress: Float) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { scope.launch { onDismissRequest() } },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                usePlatformInsets = false,
                useSoftwareKeyboardInset = false,
                scrimColor = Color.Transparent,
                animateTransition = false,
                dismissOnBackPress = properties.shouldDismissOnBackPress,
                dismissOnClickOutside = properties.shouldDismissOnClickOutside,
            ),
        content = { content(0f) },
    )
}

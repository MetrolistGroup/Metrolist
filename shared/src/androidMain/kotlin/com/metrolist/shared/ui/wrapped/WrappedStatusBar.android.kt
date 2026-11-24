package com.metrolist.shared.ui.wrapped

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal actual fun WrappedStatusBarAppearance(lightBackground: Boolean) {
    val view = LocalView.current
    val controller =
        remember(view) {
            generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?.window
                ?.let { WindowCompat.getInsetsController(it, view) }
        }
    val original = remember(controller) { controller?.isAppearanceLightStatusBars }
    SideEffect { controller?.isAppearanceLightStatusBars = lightBackground }
    DisposableEffect(controller) { onDispose { original?.let { controller?.isAppearanceLightStatusBars = it } } }
}

package com.metrolist.shared.utils

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.atomic.AtomicInteger

private val activeCount = AtomicInteger(0)

@Composable
actual fun BlockScreenshots() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = (context as? Activity)?.window

        if (window != null) {
            if (activeCount.incrementAndGet() == 1) {
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
        }

        onDispose {
            if (window != null && activeCount.decrementAndGet() == 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

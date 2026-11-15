package com.metrolist.shared.ui.crash

import android.app.Activity
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

@Composable
internal actual fun ObserveCrashScreenshots(onScreenshot: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val activity = LocalContext.current as? Activity ?: return
    val owner = activity as? LifecycleOwner ?: return
    val currentCallback = rememberUpdatedState(onScreenshot)
    DisposableEffect(activity, owner) {
        val callback = Activity.ScreenCaptureCallback { currentCallback.value() }
        var registered = false
        val observer =
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
                    registered = true
                }

                override fun onStop(owner: LifecycleOwner) {
                    activity.unregisterScreenCaptureCallback(callback)
                    registered = false
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (registered) activity.unregisterScreenCaptureCallback(callback)
        }
    }
}

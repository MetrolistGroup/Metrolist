package com.metrolist.android.ui

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back_again_to_exit
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RootExitBackHandler(
    enabled: Boolean,
    onExit: () -> Unit,
) {
    val confirmation = remember(enabled) { BackExitConfirmation() }
    val context = LocalContext.current
    val message = stringResource(Res.string.back_again_to_exit)
    val toast = remember(context, message, confirmation) { Toast.makeText(context, message, Toast.LENGTH_SHORT) }
    LifecycleResumeEffect(confirmation, toast) {
        onPauseOrDispose {
            confirmation.reset()
            toast.cancel()
        }
    }
    NavigationBackHandler(enabled = enabled) {
        if (confirmation.onBack(SystemClock.uptimeMillis())) {
            toast.cancel()
            onExit()
        } else {
            toast.show()
        }
    }
}

internal class BackExitConfirmation {
    private var lastBackMillis: Long? = null

    fun onBack(nowMillis: Long): Boolean {
        val previous = lastBackMillis
        if (previous != null && nowMillis - previous in 0L until 2_000L) {
            reset()
            return true
        }
        lastBackMillis = nowMillis
        return false
    }

    fun reset() {
        lastBackMillis = null
    }
}

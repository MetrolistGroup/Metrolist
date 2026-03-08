package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.desktop.state.auth.AuthState
import com.metrolist.shared.ui.TokenLoginScreen
import com.metrolist.shared.ui.components.Dialog

@Composable
fun TokenLoginDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var saved by remember { mutableStateOf(false) }
    val close = { if (saved) onSaved() else onDismiss() }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            TokenLoginScreen(
                onNavigateBack = close,
                onCredentialsSaved = { credentials ->
                    saved = true
                    AuthState.updateAuth(
                        cookie = credentials.cookie,
                        visitorData = credentials.visitorData,
                        dataSyncId = credentials.dataSyncId,
                        authUser = credentials.authUser,
                        accountInfo = credentials.accountInfo,
                    )
                },
            )
        }
    }
}

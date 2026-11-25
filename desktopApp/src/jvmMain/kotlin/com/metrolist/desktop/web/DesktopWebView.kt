@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.web

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.error_title
import com.metrolist.shared.generated.resources.webview_runtime_unavailable
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.WebViewState
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopWebView(
    state: WebViewState,
    navigator: WebViewNavigator,
    modifier: Modifier = Modifier,
    reportFailure: Boolean = true,
) {
    val failure by DesktopWebViewAvailability.shared.creationFailure.collectAsState()
    when {
        failure == null -> WebView(state = state, navigator = navigator, modifier = modifier)
        reportFailure -> DesktopWebViewError()
    }
}

@Composable
private fun DesktopWebViewError() {
    var dismissed by remember { mutableStateOf(false) }
    PopupVisibility(visible = !dismissed) {
        AlertDialog(
            onDismissRequest = { dismissed = true },
            title = { Text(stringResource(Res.string.error_title)) },
            text = { Text(stringResource(Res.string.webview_runtime_unavailable)) },
            confirmButton = {
                TextButton(onClick = { dismissed = true }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.close))
                }
            },
        )
    }
}

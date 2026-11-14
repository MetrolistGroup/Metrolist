@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.utils.LINUX_WEBKIT_SONAME
import com.metrolist.desktop.utils.isLinuxWebKitAvailable
import com.metrolist.desktop.utils.linuxWebKitInstallCommand
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.loading
import com.metrolist.shared.generated.resources.webkit_appimage_hint
import com.metrolist.shared.generated.resources.webkit_install_command
import com.metrolist.shared.generated.resources.webkit_install_hint
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberLinuxWebKitAvailable(): Boolean? {
    val available by produceState<Boolean?>(if (OsUtil.isLinux) null else true) {
        if (OsUtil.isLinux) value = withContext(Dispatchers.IO) { isLinuxWebKitAvailable() }
    }
    return available
}

@Composable
internal fun WebViewAvailabilityLoading() {
    val loading = stringResource(Res.string.loading)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.semantics { contentDescription = loading })
    }
}

@Composable
internal fun WebViewUnavailableContent(
    title: String,
    onDismissRequest: () -> Unit,
) {
    val command = remember { linuxWebKitInstallCommand() }
    val isAppImage = remember { !System.getenv("APPIMAGE").isNullOrBlank() || !System.getenv("APPDIR").isNullOrBlank() }
    val hint =
        stringResource(Res.string.webkit_install_hint, LINUX_WEBKIT_SONAME) +
            (command?.let { "\n\n" + stringResource(Res.string.webkit_install_command, it) }.orEmpty()) +
            (if (isAppImage) "\n\n" + stringResource(Res.string.webkit_appimage_hint) else "")
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScrollWithDrag(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            hint,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDismissRequest, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.close)) }
    }
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.crash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.metrolist.shared.crash.CrashLogsState
import com.metrolist.shared.crash.CrashReport
import com.metrolist.shared.crash.CrashStore
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.crash_clear
import com.metrolist.shared.generated.resources.crash_close
import com.metrolist.shared.generated.resources.crash_copy_logs
import com.metrolist.shared.generated.resources.crash_logs
import com.metrolist.shared.generated.resources.crash_no_log
import com.metrolist.shared.generated.resources.crash_share_logs
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.setText
import com.metrolist.shared.utils.logTextChunks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
fun CrashLogsScreen(
    store: CrashStore,
    onClose: () -> Unit,
    onShare: (String) -> Unit,
) {
    val state = remember { CrashLogsState(store) }
    val reports by state.reports.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    var selected by remember { mutableStateOf<CrashReport?>(null) }
    val chunks by produceState(emptyList<String>(), selected) {
        value = withContext(Dispatchers.Default) { selected?.let { logTextChunks(it.text) }.orEmpty() }
    }
    val clipboard = LocalClipboard.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        Text(stringResource(Res.string.crash_logs), style = MaterialTheme.typography.headlineSmall)
        Row {
            TextButton(onClick = {
                if (selected !=
                    null
                ) {
                    selected = null
                } else {
                    onClose()
                }
            }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.crash_close)) }
            TextButton(onClick = {
                selected = null
                scope.launch { state.clear() }
            }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.crash_clear)) }
        }
        val report = selected
        if (report != null) {
            Row {
                TextButton(
                    onClick = { scope.launch { clipboard.setText(report.text) } },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.crash_copy_logs)) }
                TextButton(
                    onClick = { onShare(report.text) },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.crash_share_logs)) }
            }
            SelectionContainer(Modifier.weight(1f)) {
                DragScrollableColumn {
                    items(chunks.size) { index -> Text(chunks[index].removeSuffix("\n")) }
                }
            }
        } else {
            if (reports.isEmpty()) Text(stringResource(Res.string.crash_no_log))
            DragScrollableColumn {
                items(reports, key = { it.id }) { entry ->
                    TextButton(onClick = { selected = entry }, shapes = ButtonDefaults.shapes()) {
                        Text("${Instant.fromEpochMilliseconds(entry.timestamp)} ·\u00a0${entry.version}")
                    }
                }
            }
        }
    }
}

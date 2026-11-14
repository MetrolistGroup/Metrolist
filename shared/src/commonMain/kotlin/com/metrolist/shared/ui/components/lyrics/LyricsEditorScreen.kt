@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.metrolist.shared.api.lyrics.LyricsEditor
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.copy
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.lyrics_editor_delete_line
import com.metrolist.shared.generated.resources.lyrics_editor_earlier
import com.metrolist.shared.generated.resources.lyrics_editor_export
import com.metrolist.shared.generated.resources.lyrics_editor_import
import com.metrolist.shared.generated.resources.lyrics_editor_import_error
import com.metrolist.shared.generated.resources.lyrics_editor_invalid_timestamp
import com.metrolist.shared.generated.resources.lyrics_editor_later
import com.metrolist.shared.generated.resources.lyrics_editor_limit_error
import com.metrolist.shared.generated.resources.lyrics_editor_more
import com.metrolist.shared.generated.resources.lyrics_editor_redo
import com.metrolist.shared.generated.resources.lyrics_editor_revert
import com.metrolist.shared.generated.resources.lyrics_editor_revert_message
import com.metrolist.shared.generated.resources.lyrics_editor_save_error
import com.metrolist.shared.generated.resources.lyrics_editor_shift_all_earlier
import com.metrolist.shared.generated.resources.lyrics_editor_shift_all_later
import com.metrolist.shared.generated.resources.lyrics_editor_sync
import com.metrolist.shared.generated.resources.lyrics_editor_title
import com.metrolist.shared.generated.resources.lyrics_editor_undo
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.repository.LyricsEditorAutosave
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.BasicAlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.utils.setText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

@Composable
fun LyricsEditorScreen(
    songId: String,
    songTitle: String,
    rawLyrics: String,
    onChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repository = remember { KoinPlatform.getKoin().get<LyricsRepository>() }
    val scope = remember(songId) { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val latestChanged by rememberUpdatedState(onChanged)
    val editor = remember(songId) { runCatching { LyricsEditor(rawLyrics) }.getOrElse { LyricsEditor("").apply { edit(rawLyrics, 0) } } }
    val autosave = remember(songId) { LyricsEditorAutosave(songId, repository, scope) { latestChanged() } }
    var revision by remember(songId) { mutableIntStateOf(0) }
    var ready by remember(songId) { mutableStateOf(false) }
    var busy by remember(songId) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var reverting by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    val textState = remember(songId) { LyricsEditorTextState(editor) }
    val textFieldState = textState.field
    val failed by autosave.failed.collectAsState()
    val playback = LocalPlaybackController.current
    val clipboard = LocalClipboard.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    fun syncInput() {
        val before = editor.current
        textState.syncInput()
        if (before != editor.current) revision++
        if (before.text != editor.current.text || before.document != editor.current.document) autosave.changed(editor.current)
    }

    LaunchedEffect(songId) {
        repository.getUserLyricsDraft(songId)?.let { editor.edit(it, 0) }
        textState.resetInput()
        revision++
        ready = true
    }
    DisposableEffect(songId, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    syncInput()
                    scope.launch { autosave.flush() }
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            syncInput()
            scope.launch {
                autosave.flush()
                scope.cancel()
            }
        }
    }

    fun update(action: () -> Unit) {
        syncInput()
        val before = editor.current
        action()
        textState.resetInput()
        revision++
        if (before.text != editor.current.text || before.document != editor.current.document) autosave.changed(editor.current)
    }

    fun close() {
        if (busy) return
        syncInput()
        busy = true
        scope.launch {
            if (autosave.flush()) onDismiss()
            busy = false
        }
    }
    // Reading revision makes model changes observable without putting Compose state in the parser.
    val current = remember(editor, revision) { editor.current }
    if (ready) {
        LaunchedEffect(textFieldState) {
            snapshotFlow { textFieldState.text.toString() to textFieldState.selection }.collect { syncInput() }
        }
    }
    val errors = current.errors
    val errorColor = MaterialTheme.colorScheme.error
    val invalidMessage = errors.firstOrNull()?.let { stringResource(Res.string.lyrics_editor_invalid_timestamp, it + 1) }
    val outputTransformation =
        remember(errors, errorColor) {
            OutputTransformation {
                val text = asCharSequence().toString()
                var offset = 0
                text.split('\n').forEachIndexed { index, row ->
                    if (index in errors) {
                        addStyle(
                            SpanStyle(color = errorColor, textDecoration = TextDecoration.Underline),
                            offset,
                            offset + row.length,
                        )
                    }
                    offset += row.length + 1
                }
            }
        }
    BasicAlertDialog(
        onDismissRequest = ::close,
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            modifier =
                Modifier.fillMaxSize().imePadding().onPreviewKeyEvent { event ->
                    if (!ready || busy || event.type != KeyEventType.KeyDown || event.key != Key.Z ||
                        !(event.isCtrlPressed || event.isMetaPressed)
                    ) {
                        false
                    } else {
                        update { if (event.isShiftPressed) editor.redo() else editor.undo() }
                        true
                    }
                },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(Res.string.lyrics_editor_title, songTitle),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = ::close,
                            enabled = !busy,
                        ) { Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back)) }
                    },
                )
            },
            bottomBar = {
                BottomAppBar {
                    TextButton(onClick = {
                        update { editor.shift(-500) }
                    }, enabled = ready && !busy && current.isValid) { Text(stringResource(Res.string.lyrics_editor_earlier)) }
                    TextButton(onClick = {
                        update { editor.shift(500) }
                    }, enabled = ready && !busy && current.isValid) { Text(stringResource(Res.string.lyrics_editor_later)) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        update {
                            editor.deleteLines()
                        }
                    }, enabled = ready && !busy) {
                        Icon(
                            MetrolistIcons.Outlined.Delete,
                            stringResource(Res.string.lyrics_editor_delete_line),
                        )
                    }
                    IconButton(onClick = {
                        update {
                            editor.undo()
                        }
                    }, enabled = ready && !busy && editor.canUndo) {
                        Icon(
                            MetrolistIcons.Outlined.Replay,
                            stringResource(Res.string.lyrics_editor_undo),
                        )
                    }
                    IconButton(onClick = { update { editor.redo() } }, enabled = ready && !busy && editor.canRedo) {
                        Icon(
                            MetrolistIcons.Outlined.Replay,
                            stringResource(Res.string.lyrics_editor_redo),
                            Modifier.graphicsLayer {
                                scaleX =
                                    -1f
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = {
                            more = true
                        }, enabled = ready && !busy) {
                            Icon(
                                MetrolistIcons.Outlined.MoreVert,
                                stringResource(Res.string.lyrics_editor_more),
                            )
                        }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = {
                                Text(
                                    stringResource(Res.string.lyrics_editor_shift_all_earlier),
                                )
                            }, enabled = current.isValid, onClick = {
                                more =
                                    false
                                ; update { editor.shift(-500, all = true) }
                            })
                            DropdownMenuItem(text = {
                                Text(
                                    stringResource(Res.string.lyrics_editor_shift_all_later),
                                )
                            }, enabled = current.isValid, onClick = {
                                more =
                                    false
                                ; update { editor.shift(500, all = true) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.lyrics_editor_sync)) }, onClick = {
                                more = false
                                update { editor.timestamp(playback.currentPositionMs()) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.lyrics_editor_revert)) }, onClick = {
                                more = false
                                reverting =
                                    true
                            })
                            DropdownMenuItem(text = {
                                Text(
                                    stringResource(Res.string.lyrics_editor_export),
                                )
                            }, enabled = current.isValid, onClick = {
                                more =
                                    false
                                ; syncInput()
                                exporting = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(Res.string.lyrics_editor_import)) }, onClick = {
                                more = false
                                importing =
                                    true
                            })
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (!ready) {
                    CircularProgressIndicator()
                } else {
                    if (failed) {
                        Text(
                            stringResource(Res.string.lyrics_editor_save_error),
                            color = errorColor,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        TextButton(onClick = {
                            syncInput()
                            scope.launch { autosave.flush() }
                        }) { Text(stringResource(Res.string.retry)) }
                    }
                    if (current.exceedsLimits) {
                        Text(
                            stringResource(Res.string.lyrics_editor_limit_error),
                            color = errorColor,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    if (invalidMessage != null) Text(invalidMessage, color = errorColor, modifier = Modifier.padding(horizontal = 16.dp))
                    BasicTextField(
                        state = textFieldState,
                        enabled = !busy,
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        outputTransformation = outputTransformation,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
    PopupVisibility(visible = importing) {
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text(stringResource(Res.string.lyrics_editor_import)) },
            text = {
                Column {
                    OutlinedTextField(value = importText, onValueChange = {
                        importText = it
                        importError = false
                    }, modifier = Modifier.fillMaxWidth(), isError = importError)
                    if (importError) Text(stringResource(Res.string.lyrics_editor_import_error), color = errorColor)
                }
            },
            confirmButton = {
                TextButton(enabled = importText.isNotBlank(), onClick = {
                    syncInput()
                    val before = editor.current
                    if (editor.import(importText)) {
                        textState.resetInput()
                        revision++
                        if (before.text != editor.current.text ||
                            before.document != editor.current.document
                        ) {
                            autosave.changed(editor.current)
                        }
                        importing = false
                    } else {
                        importError = true
                    }
                }) { Text(stringResource(Res.string.lyrics_editor_import)) }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    PopupVisibility(visible = exporting) {
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text(stringResource(Res.string.lyrics_editor_export)) },
            text = { OutlinedTextField(value = current.json, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setText(current.json)
                        exporting = false
                    }
                }) { Text(stringResource(Res.string.copy)) }
            },
            dismissButton = { TextButton(onClick = { exporting = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    PopupVisibility(visible = reverting) {
        AlertDialog(
            onDismissRequest = { reverting = false },
            title = { Text(stringResource(Res.string.lyrics_editor_revert)) },
            text = { Text(stringResource(Res.string.lyrics_editor_revert_message)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (autosave.revert()) {
                            reverting = false
                            onDismiss()
                        }
                    }
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { reverting = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

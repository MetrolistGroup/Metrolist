/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.lyrics_offset
import com.metrolist.shared.generated.resources.lyrics_offset_decrease
import com.metrolist.shared.generated.resources.lyrics_offset_earlier
import com.metrolist.shared.generated.resources.lyrics_offset_in_sync
import com.metrolist.shared.generated.resources.lyrics_offset_increase
import com.metrolist.shared.generated.resources.lyrics_offset_later
import com.metrolist.shared.generated.resources.milliseconds_unit
import com.metrolist.shared.generated.resources.settings_reset_to_default
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

private const val OFFSET_STEP_MS = 50
private const val OFFSET_SLIDER_LIMIT_MS = 3_000

@Composable
fun LyricsOffsetEditor(
    offsetMs: Int,
    onOffsetChange: (Int) -> Unit,
    onDone: (() -> Unit)?,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    title: String = stringResource(Res.string.lyrics_offset),
) {
    var input by remember(offsetMs) { mutableStateOf(offsetMs.toString()) }
    val textFieldState = remember { TextFieldState(offsetMs.toString(), TextRange.Zero) }
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var tvKeyboardRequested by remember { mutableStateOf(false) }
    val latestOffsetChange by rememberUpdatedState(onOffsetChange)

    fun syncInput() {
        val value = textFieldState.text.toString()
        if (value != input) {
            input = value
            value.toIntOrNull()?.let(latestOffsetChange)
        }
    }

    LaunchedEffect(offsetMs) { textFieldState.updateOffsetText(offsetMs) }
    LaunchedEffect(textFieldState, offsetMs) {
        snapshotFlow { textFieldState.text.toString() }.collect { syncInput() }
    }

    fun setOffset(value: Int) {
        input = value.toString()
        textFieldState.updateOffsetText(value)
        onOffsetChange(value)
    }

    fun adjustOffset(delta: Int) {
        val value = textFieldState.text.toString().toIntOrNull() ?: offsetMs
        setOffset((value.toLong() + delta).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
    }

    val buttonColors =
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = contentColor.copy(alpha = 0.12f),
            contentColor = contentColor,
        )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { setOffset(0) }, enabled = offsetMs != 0, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    MetrolistIcons.Outlined.Replay,
                    contentDescription = stringResource(Res.string.settings_reset_to_default),
                    tint = contentColor.copy(alpha = if (offsetMs != 0) 1f else 0.38f),
                )
            }
            if (onDone != null) {
                FilledTonalIconButton(
                    onClick = {
                        syncInput()
                        focusManager.clearFocus()
                        onDone()
                    },
                    colors = buttonColors,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(MetrolistIcons.Filled.Check, contentDescription = stringResource(Res.string.done))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = { adjustOffset(-OFFSET_STEP_MS) },
                colors = buttonColors,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(MetrolistIcons.Filled.Remove, contentDescription = stringResource(Res.string.lyrics_offset_decrease, OFFSET_STEP_MS))
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    BasicTextField(
                        state = textFieldState,
                        inputTransformation =
                            InputTransformation {
                                val value = asCharSequence().toString()
                                if (value.isNotEmpty() && value != "-" && !value.removePrefix("-").all(Char::isDigit)) {
                                    revertAllChanges()
                                }
                            },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        textStyle =
                            MaterialTheme.typography.displaySmall.copy(
                                color = if (input.toIntOrNull() == null) MaterialTheme.colorScheme.error else contentColor,
                                textAlign = TextAlign.Center,
                            ),
                        cursorBrush = SolidColor(contentColor),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                                showKeyboardOnFocus = !isTv || tvKeyboardRequested,
                            ),
                        onKeyboardAction = {
                            syncInput()
                            if (isTv) {
                                keyboardController?.hide()
                                tvKeyboardRequested = false
                            } else {
                                focusManager.clearFocus()
                            }
                        },
                        modifier =
                            Modifier
                                .weight(1f, fill = false)
                                .semantics { contentDescription = title }
                                .tvFocusHighlight()
                                .onFocusChanged { if (!it.isFocused) tvKeyboardRequested = false }
                                .onInterceptKeyBeforeSoftKeyboard { event ->
                                    if (isTv && tvKeyboardRequested && event.key == Key.Back) {
                                        if (event.type == KeyEventType.KeyUp) {
                                            keyboardController?.hide()
                                            tvKeyboardRequested = false
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }.onPreviewKeyEvent { event ->
                                    if (isTv && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                        if (event.type == KeyEventType.KeyUp) {
                                            tvKeyboardRequested = true
                                            keyboardController?.show()
                                        }
                                        true
                                    } else if (isTv && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            focusManager.moveFocus(
                                                if (event.key ==
                                                    Key.DirectionUp
                                                ) {
                                                    FocusDirection.Up
                                                } else {
                                                    FocusDirection.Down
                                                },
                                            )
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                },
                    )
                    Text(
                        text = stringResource(Res.string.milliseconds_unit),
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
                Text(
                    text =
                        when {
                            offsetMs > 0 -> stringResource(Res.string.lyrics_offset_earlier, offsetMs)
                            offsetMs < 0 -> stringResource(Res.string.lyrics_offset_later, abs(offsetMs))
                            else -> stringResource(Res.string.lyrics_offset_in_sync)
                        },
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
            FilledTonalIconButton(
                onClick = { adjustOffset(OFFSET_STEP_MS) },
                colors = buttonColors,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(MetrolistIcons.Filled.Add, contentDescription = stringResource(Res.string.lyrics_offset_increase, OFFSET_STEP_MS))
            }
        }
        val range = -OFFSET_SLIDER_LIMIT_MS.toFloat()..OFFSET_SLIDER_LIMIT_MS.toFloat()
        val sliderState =
            remember { SliderState(value = 0f, trackRange = range, steps = 2 * OFFSET_SLIDER_LIMIT_MS / OFFSET_STEP_MS - 1) }
        sliderState.value = offsetMs.toFloat().coerceIn(range)
        Slider(
            state = sliderState,
            onValueChange = {
                sliderState.value = it
                setOffset((it / OFFSET_STEP_MS).roundToInt() * OFFSET_STEP_MS)
            },
            colors =
                SliderDefaults.colors(
                    thumbColor = contentColor,
                    activeTrackColor = contentColor,
                    inactiveTrackColor = contentColor.copy(alpha = 0.24f),
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            modifier = Modifier.retainPlaybackKeyFocus().fillMaxWidth().semantics { contentDescription = title },
        )
    }
}

internal fun TextFieldState.updateOffsetText(offsetMs: Int) {
    val value = offsetMs.toString()
    if (text.toString() == value) return
    val previousSelection = selection
    edit {
        replace(0, length, value)
        selection = previousSelection.coerceIn(0, length)
    }
}

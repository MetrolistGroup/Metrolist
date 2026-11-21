/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.collapse
import com.metrolist.shared.generated.resources.expand
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.generated.resources.reset
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.sleep_timer_advanced_options
import com.metrolist.shared.generated.resources.sleep_timer_cancel
import com.metrolist.shared.generated.resources.sleep_timer_end_current_song
import com.metrolist.shared.generated.resources.sleep_timer_end_of_song
import com.metrolist.shared.generated.resources.sleep_timer_fade_out
import com.metrolist.shared.generated.resources.sleep_timer_fade_out_enabled
import com.metrolist.shared.generated.resources.sleep_timer_finish_current_song
import com.metrolist.shared.generated.resources.sleep_timer_hour
import com.metrolist.shared.generated.resources.sleep_timer_minute
import com.metrolist.shared.generated.resources.sleep_timer_remaining
import com.metrolist.shared.generated.resources.sleep_timer_separator
import com.metrolist.shared.generated.resources.sleep_timer_set_default
import com.metrolist.shared.playback.SleepTimerDefaults
import com.metrolist.shared.playback.SleepTimerState
import com.metrolist.shared.playback.sleepTimerDurationMs
import com.metrolist.shared.playback.sleepTimerFieldValue
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.segmentedGroupShape
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Composable
fun SleepTimerDialog(
    state: SleepTimerState,
    onDismiss: () -> Unit,
    onStart: (durationMs: Long, fadeOut: Boolean, finishCurrentSong: Boolean) -> Unit,
    onStopAfterCurrentSong: (fadeOut: Boolean) -> Unit,
    onCancelTimer: () -> Unit,
    allowEndOfSong: Boolean = true,
) {
    val defaults = remember { Settings.shared.sleepTimerDefaults }
    var hours by rememberSaveable { mutableStateOf((defaults.durationMinutes / 60).toString().padStart(2, '0')) }
    var minutes by rememberSaveable { mutableStateOf((defaults.durationMinutes % 60).toString().padStart(2, '0')) }
    var finishCurrentSong by rememberSaveable { mutableStateOf(defaults.finishCurrentSong) }
    var fadeOut by rememberSaveable { mutableStateOf(defaults.fadeOut) }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var resetSignal by remember { mutableIntStateOf(0) }
    val durationMs = sleepTimerDurationMs(hours, minutes)

    SleepTimerDialogWindow(onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Icon(MetrolistIcons.Outlined.Bedtime, contentDescription = null, tint = AlertDialogDefaults.iconContentColor)
                Text(stringResource(Res.string.sleep_timer), style = MaterialTheme.typography.headlineSmall)
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (state.isActive) {
                        Text(
                            if (state.stopAfterCurrentSong) {
                                stringResource(Res.string.sleep_timer_end_current_song)
                            } else {
                                stringResource(Res.string.sleep_timer_remaining, formatSleepTimerRemaining(state.remainingMs))
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (state.finishCurrentSong) Text(stringResource(Res.string.sleep_timer_finish_current_song))
                        if (state.fadeOut) Text(stringResource(Res.string.sleep_timer_fade_out_enabled))
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            DurationField(
                                hours,
                                { hours = it },
                                stringResource(Res.string.sleep_timer_hour),
                                23,
                                Modifier.weight(1f),
                                resetSignal = { resetSignal },
                            )
                            Text(
                                stringResource(Res.string.sleep_timer_separator),
                                modifier = Modifier.padding(top = 20.dp),
                                style = MaterialTheme.typography.displayMedium,
                            )
                            DurationField(
                                minutes,
                                { minutes = it },
                                stringResource(Res.string.sleep_timer_minute),
                                59,
                                Modifier.weight(1f),
                                last = true,
                                resetSignal = { resetSignal },
                            )
                        }
                        if (allowEndOfSong) {
                            FilledTonalButton(
                                onClick = {
                                    onStopAfterCurrentSong(fadeOut)
                                    onDismiss()
                                },
                                modifier = Modifier.tvFocusHighlight(CircleShape),
                                shapes = ButtonDefaults.shapes(),
                            ) { Text(stringResource(Res.string.sleep_timer_end_of_song)) }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Surface(
                                shape = segmentedGroupShape(true, !expanded, false),
                                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(Res.string.sleep_timer_advanced_options),
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    val rotation by animateFloatAsState(
                                        if (expanded) 180f else 0f,
                                        MaterialTheme.motionScheme.fastSpatialSpec(),
                                    )
                                    FilledTonalIconButton(
                                        onClick = { expanded = !expanded },
                                        modifier = Modifier.tvFocusHighlight(CircleShape),
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(
                                            MetrolistIcons.Outlined.KeyboardArrowDown,
                                            stringResource(if (expanded) Res.string.collapse else Res.string.expand),
                                            Modifier.rotate(rotation),
                                        )
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = expanded,
                                enter =
                                    expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                                        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                                exit =
                                    shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                                        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TimerCheckboxRow(
                                        text = stringResource(Res.string.sleep_timer_finish_current_song),
                                        checked = finishCurrentSong,
                                        enabled = allowEndOfSong,
                                        last = false,
                                        onCheckedChange = { finishCurrentSong = it },
                                    )
                                    TimerCheckboxRow(
                                        text = stringResource(Res.string.sleep_timer_fade_out),
                                        checked = fadeOut,
                                        last = true,
                                        onCheckedChange = { fadeOut = it },
                                    )
                                }
                            }
                        }
                        Surface(
                            onClick = {
                                resetSignal++
                                durationMs?.let {
                                    Settings.shared.sleepTimerDefaults =
                                        SleepTimerDefaults((it / 60_000L).toInt(), finishCurrentSong, fadeOut)
                                }
                            },
                            enabled = durationMs != null,
                            modifier = Modifier.fillMaxWidth().tvFocusHighlight(MaterialTheme.shapes.extraLarge),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Text(
                                stringResource(Res.string.sleep_timer_set_default),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!state.isActive) {
                        OutlinedButton(
                            onClick = {
                                resetSignal++
                                val saved = Settings.shared.sleepTimerDefaults
                                hours = (saved.durationMinutes / 60).toString().padStart(2, '0')
                                minutes = (saved.durationMinutes % 60).toString().padStart(2, '0')
                                finishCurrentSong = saved.finishCurrentSong
                                fadeOut = saved.fadeOut
                            },
                            modifier = Modifier.tvFocusHighlight(CircleShape),
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.reset)) }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.tvFocusHighlight(CircleShape),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(if (state.isActive) Res.string.close else Res.string.cancel))
                    }
                    Button(
                        onClick = {
                            if (state.isActive) {
                                onCancelTimer()
                            } else {
                                durationMs?.let {
                                    onStart(
                                        it,
                                        fadeOut,
                                        finishCurrentSong && allowEndOfSong,
                                    )
                                }
                            }
                            onDismiss()
                        },
                        enabled = state.isActive || durationMs != null,
                        modifier = Modifier.tvFocusHighlight(CircleShape),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(if (state.isActive) Res.string.sleep_timer_cancel else Res.string.ok)) }
                }
            }
        }
    }
}

@Composable
private fun SleepTimerDialogWindow(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (LocalIsTv.current) {
        BasicAlertDialog(onDismissRequest = onDismiss) {
            Box(Modifier.width(360.dp)) { content() }
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val backProgress = rememberSheetBackProgress(LocalPopupSession.current?.state?.acceptsInput != false) { onDismiss() }
            // Keep the window fixed while the options animate inside it.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().graphicsLayer {
                        scaleX = 1f - 0.1f * backProgress.value
                        scaleY = scaleX
                    },
                ) { content() }
            }
        }
    }
}

@Composable
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maximum: Int,
    modifier: Modifier,
    last: Boolean = false,
    resetSignal: () -> Int = { 0 },
) {
    val focusManager = LocalFocusManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val currentResetSignal = resetSignal()
    var latestValue by remember { mutableStateOf(value) }
    var lastResetSignal by remember { mutableIntStateOf(currentResetSignal) }
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var input by remember { mutableStateOf(TextFieldValue(value)) }
    var isFocused by remember { mutableStateOf(false) }
    var lastDirection by remember { mutableIntStateOf(1) }
    var flingJob by remember { mutableStateOf<Job?>(null) }

    SideEffect {
        if (currentResetSignal != lastResetSignal) {
            lastResetSignal = currentResetSignal
            flingJob?.cancel()
            flingJob = null
        }
        latestValue = value
    }

    LaunchedEffect(value) {
        if (input.text != value) input = TextFieldValue(value, TextRange(0, value.length))
    }

    fun stepValue(steps: Int) {
        if (steps == 0) return
        lastDirection = if (steps > 0) 1 else -1
        val updated = stepDurationFieldValue(latestValue, steps, maximum)
        if (updated != latestValue) {
            latestValue = updated
            currentOnValueChange(updated)
            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    val isError = sleepTimerFieldValue(value)?.let { it > maximum } == true

    Column(
        modifier =
            modifier
                .pointerInput(maximum) {
                    val stepPx = 20.dp.toPx()
                    val velocityTracker = VelocityTracker()
                    val decay = exponentialDecay<Float>(frictionMultiplier = 2.6f)
                    var accumulatedDrag = 0f
                    var lastDragMark: TimeMark? = null
                    detectVerticalDragGestures(
                        onDragStart = {
                            flingJob?.cancel()
                            focusManager.clearFocus()
                            velocityTracker.resetTracking()
                            accumulatedDrag = 0f
                            lastDragMark = null
                        },
                        onDragCancel = {
                            velocityTracker.resetTracking()
                            accumulatedDrag = 0f
                            lastDragMark = null
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (lastDragMark == null) {
                                velocityTracker.addPosition(change.previousUptimeMillis, change.previousPosition)
                            }
                            velocityTracker.addPointerInputChange(change)
                            lastDragMark = TimeSource.Monotonic.markNow()
                            accumulatedDrag += dragAmount
                            val steps = (-accumulatedDrag / stepPx).toInt()
                            if (steps != 0) {
                                accumulatedDrag += steps * stepPx
                                stepValue(steps)
                            }
                        },
                        onDragEnd = {
                            val isRecentDrag = lastDragMark?.elapsedNow()?.inWholeMilliseconds?.let { it < 100L } == true
                            val velocityY = if (isRecentDrag) velocityTracker.calculateVelocity().y else 0f
                            velocityTracker.resetTracking()
                            lastDragMark = null
                            val startOffset = accumulatedDrag
                            accumulatedDrag = 0f
                            if (abs(velocityY) > stepPx * 3f) {
                                val flingResetSignal = resetSignal()
                                flingJob =
                                    coroutineScope.launch {
                                        var lastAnimValue = startOffset
                                        var carry = startOffset
                                        Animatable(startOffset).animateDecay(velocityY, decay) {
                                            if (resetSignal() != flingResetSignal) {
                                                flingJob?.cancel()
                                                return@animateDecay
                                            }
                                            val delta = this.value - lastAnimValue
                                            lastAnimValue = this.value
                                            carry += delta
                                            val steps = (-carry / stepPx).toInt()
                                            if (steps != 0) {
                                                carry += steps * stepPx
                                                stepValue(steps)
                                            }
                                        }
                                    }
                            }
                        },
                    )
                }.pointerInput(maximum) {
                    var accumulatedScroll = 0f
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val scrollY = event.changes.fold(0f) { acc, change -> acc + change.scrollDelta.y }
                                if (scrollY != 0f) {
                                    event.changes.forEach { it.consume() }
                                    flingJob?.cancel()
                                    accumulatedScroll += scrollY
                                    val steps = (-accumulatedScroll).toInt()
                                    if (steps != 0) {
                                        accumulatedScroll += steps
                                        stepValue(steps)
                                    }
                                }
                            }
                        }
                    }
                }.padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    if (sleepTimerFieldValue(it.text) != null) {
                        input = it
                        onValueChange(it.text)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .retainPlaybackKeyFocus()
                        .onFocusChanged {
                            isFocused = it.isFocused
                            if (it.isFocused) {
                                flingJob?.cancel()
                                input = input.copy(selection = TextRange(0, input.text.length))
                            }
                        }.semantics { contentDescription = label },
                textStyle = MaterialTheme.typography.displayMedium.copy(textAlign = TextAlign.Center),
                singleLine = true,
                isError = isError,
                shape = MaterialTheme.shapes.small,
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        focusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedTextColor = Color.Transparent,
                        errorTextColor = if (isFocused) MaterialTheme.colorScheme.error else Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = if (last) ImeAction.Done else ImeAction.Next,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        onDone = { focusManager.clearFocus() },
                    ),
            )
            if (!isFocused) {
                AnimatedContent(
                    targetState = value,
                    modifier = Modifier.clipToBounds().clearAndSetSemantics {},
                    transitionSpec = {
                        val slideOffset: (Int) -> Int = { height ->
                            if (lastDirection > 0) (height * 0.65f).toInt() else -(height * 0.65f).toInt()
                        }
                        (slideInVertically(tween(220, easing = FastOutSlowInEasing), slideOffset) + fadeIn(tween(180)))
                            .togetherWith(
                                slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { height -> -slideOffset(height) } +
                                    fadeOut(tween(150)),
                            )
                    },
                    label = "duration_digit_roll",
                ) { displayValue ->
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.displayMedium.copy(textAlign = TextAlign.Center),
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimerCheckboxRow(
    text: String,
    checked: Boolean,
    last: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Surface(
        shape = segmentedGroupShape(false, last, pressed),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Checkbox,
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onValueChange = onCheckedChange,
                    ).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

fun formatSleepTimerRemaining(remainingMs: Long?): String = remainingMs?.let { formatDuration(it, hideLeadingZeroUnits = true) } ?: "--:--"

internal fun stepDurationFieldValue(
    current: String,
    steps: Int,
    maximum: Int,
): String {
    val range = maximum + 1
    val base = (current.toIntOrNull() ?: 0).coerceIn(0, maximum)
    val next = ((base + steps) % range + range) % range
    return next.toString().padStart(2, '0')
}

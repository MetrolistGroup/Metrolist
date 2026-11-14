@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.playback_pitch
import com.metrolist.shared.generated.resources.playback_speed
import com.metrolist.shared.generated.resources.playback_speed_value
import com.metrolist.shared.generated.resources.reset
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.tempo_pitch_decrease
import com.metrolist.shared.generated.resources.tempo_pitch_delete_preset
import com.metrolist.shared.generated.resources.tempo_pitch_increase
import com.metrolist.shared.generated.resources.tempo_pitch_input_range
import com.metrolist.shared.generated.resources.tempo_pitch_preset_name
import com.metrolist.shared.generated.resources.tempo_pitch_presets
import com.metrolist.shared.generated.resources.tempo_pitch_save_preset
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.SliderDetents
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.utils.formatPlaybackPitch
import com.metrolist.shared.utils.formatPlaybackSpeed
import com.metrolist.shared.utils.parseTempoPitchInput
import com.metrolist.shared.utils.playbackPitchToSemitones
import com.metrolist.shared.utils.semitonesToPlaybackPitch
import com.metrolist.shared.utils.stepTempoPitchValue
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun TempoPitchControls(
    speed: Float,
    pitch: Float,
    onSpeedChange: (Float) -> Unit,
    onPitchChange: ((Float) -> Unit)?,
    speedEnabled: Boolean = true,
    varispeed: Boolean = false,
    onDone: () -> Unit,
) {
    val speedLabel = stringResource(Res.string.playback_speed)
    val pitchLabel = stringResource(Res.string.playback_pitch)
    val semitones = playbackPitchToSemitones(pitch)
    val linkPitchToSpeed = varispeed && speedEnabled && onPitchChange != null
    val applySpeed: (Float) -> Unit = { value ->
        onSpeedChange(value)
        if (linkPitchToSpeed) onPitchChange(value)
    }
    val currentSpeed by rememberUpdatedState(speed)
    LaunchedEffect(linkPitchToSpeed) {
        if (linkPitchToSpeed) onPitchChange(currentSpeed)
    }
    val presets = Settings.shared.tempoPitchPresets
    var savingPreset by rememberSaveable { mutableStateOf(false) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var deletingPresetId by rememberSaveable { mutableStateOf<String?>(null) }
    PopupVisibility(visible = savingPreset) {
        AlertDialog(
            onDismissRequest = { savingPreset = false },
            title = { Text(stringResource(Res.string.tempo_pitch_save_preset)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(Res.string.tempo_pitch_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        Settings.shared.saveTempoPitchPreset(presetName, speed, pitch)
                        savingPreset = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { savingPreset = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    presets.firstOrNull { it.id == deletingPresetId }?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletingPresetId = null },
            text = { Text(stringResource(Res.string.tempo_pitch_delete_preset, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    Settings.shared.deleteTempoPitchPreset(preset.id)
                    deletingPresetId = null
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deletingPresetId = null },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        TempoPitchSlider(
            label = speedLabel,
            value = speed,
            defaultValue = 1f,
            valueText = stringResource(Res.string.playback_speed_value, formatPlaybackSpeed(speed)),
            range = 0.5f..2f,
            steps = 29,
            enabled = speedEnabled,
            onValueChange = applySpeed,
        )
        if (!varispeed && onPitchChange != null) {
            Spacer(Modifier.height(16.dp))
            TempoPitchSlider(
                label = pitchLabel,
                value = semitones,
                defaultValue = 0f,
                valueText = formatPlaybackPitch(semitones),
                range = -12f..12f,
                steps = 479,
                showTicks = false,
                onValueChange = { onPitchChange(semitonesToPlaybackPitch(it)) },
            )
        }
        TextButton(modifier = Modifier.navigationPopupSource(), onClick = {
            presetName = ""
            savingPreset = true
        }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.tempo_pitch_save_preset)) }
        if (presets.isNotEmpty()) {
            Text(stringResource(Res.string.tempo_pitch_presets))
            presets.forEach { preset ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { preset.apply(speedEnabled, varispeed, onSpeedChange, onPitchChange) },
                        enabled = preset.canApply(speedEnabled, varispeed, onPitchChange != null),
                        modifier = Modifier.weight(1f),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(preset.name) }
                    TextButton(
                        onClick = { deletingPresetId = preset.id },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.delete)) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    if (speedEnabled) applySpeed(1f)
                    if (!varispeed) onPitchChange?.invoke(1f)
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.reset)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDone, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.done)) }
        }
    }
}

@Composable
private fun TempoPitchSlider(
    label: String,
    value: Float,
    defaultValue: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    showTicks: Boolean = true,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val haptics = rememberMetrolistHaptics()
    val detents = remember(range, steps) { SliderDetents(range, steps, value) }
    detents.reset(value)
    var editing by rememberSaveable { mutableStateOf(false) }
    Text(label)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { editing = true }, enabled = enabled, shapes = ButtonDefaults.shapes()) { Text(valueText) }
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { onValueChange(defaultValue) },
            enabled = enabled && value != defaultValue,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(Res.string.reset))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = { onValueChange(stepTempoPitchValue(value, -0.05f, range)) },
            enabled = enabled && value > range.start,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Filled.Remove, stringResource(Res.string.tempo_pitch_decrease, label))
        }
        val sliderState = rememberSliderState(value = value.coerceIn(range), trackRange = range, steps = steps)
        sliderState.shouldAutoSnap = false
        sliderState.value = value.coerceIn(range)
        Slider(
            state = sliderState,
            onValueChange = {
                val snapped = (it * 20).roundToInt() / 20f
                if (detents.moved(snapped)) haptics.perform(HapticEvent.SEGMENT_TICK)
                onValueChange(snapped)
            },
            enabled = enabled,
            modifier = Modifier.retainPlaybackKeyFocus().weight(1f).semantics { contentDescription = label },
            colors =
                if (showTicks) {
                    SliderDefaults.colors()
                } else {
                    SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
                },
        )
        IconButton(
            onClick = { onValueChange(stepTempoPitchValue(value, 0.05f, range)) },
            enabled = enabled && value < range.endInclusive,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Filled.Add, stringResource(Res.string.tempo_pitch_increase, label))
        }
    }
    if (editing && enabled) {
        var input by rememberSaveable(stateSaver = TextFieldValue.Saver) {
            val text = formatPlaybackSpeed(value)
            mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
        }
        val parsed = parseTempoPitchInput(input.text, range)
        val focusRequester = remember { FocusRequester() }
        val confirm = {
            if (parsed != null) {
                onValueChange(parsed)
                editing = false
            }
        }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(label) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = parsed == null,
                    supportingText = {
                        if (parsed == null) {
                            Text(
                                stringResource(
                                    Res.string.tempo_pitch_input_range,
                                    formatPlaybackSpeed(range.start),
                                    formatPlaybackSpeed(range.endInclusive),
                                ),
                            )
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            // Some decimal keyboards omit the minus key needed for pitch.
                            keyboardType = if (range.start < 0) KeyboardType.Text else KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            },
            confirmButton = {
                TextButton(
                    onClick = confirm,
                    enabled = parsed != null,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.done)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

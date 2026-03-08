/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.ui.components.BasicAlertDialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import kotlinx.coroutines.cancel
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
private fun SettingsDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            content()
        }
    }
}

@Composable
internal fun SettingsSliderDialog(
    item: SettingsItemRegistration,
    onDismiss: () -> Unit,
    onSettingValueChanged: () -> Unit,
) {
    val initial = item.sliderValue ?: return
    val range = item.sliderRange ?: return
    var value by remember(item.id, initial) { mutableStateOf(initial) }
    SettingsDialogSurface(onDismiss) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                item.sliderValueFormatter?.invoke(value) ?: value.roundToInt().toString(),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val sliderState = rememberSliderState(value = value, steps = item.sliderSteps, trackRange = range)
            sliderState.value = value
            item.sliderPreview?.invoke(value)
            Slider(
                state = sliderState,
                onValueChange = { value = it },
                modifier = Modifier.retainPlaybackKeyFocus().fillMaxWidth(),
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        drawTick = { _, _ -> },
                        drawStopIndicator = null,
                    )
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                TextButton(
                    onClick = {
                        item.onSliderChanged?.invoke(value)
                        onSettingValueChanged()
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.done)) }
            }
        }
    }
}

@Composable
internal fun SettingsTextDialog(
    item: SettingsItemRegistration,
    onDismiss: () -> Unit,
    onSettingValueChanged: () -> Unit,
) {
    val textFieldState = remember(item.id, item.textValue) { TextFieldState(item.textValue.orEmpty()) }
    var secretVisible by remember(item.id) { mutableStateOf(false) }
    SettingsDialogSurface(onDismiss) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall)
            if (item.secret) {
                OutlinedSecureTextField(
                    state = textFieldState,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = item.textPlaceholder?.let { placeholder -> ({ Text(placeholder) }) },
                    textObfuscationMode = if (secretVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                    trailingIcon = {
                        IconButton(onClick = { secretVisible = !secretVisible }, shapes = IconButtonDefaults.shapes()) {
                            Icon(
                                imageVector = if (secretVisible) MetrolistIcons.Filled.VisibilityOff else MetrolistIcons.Filled.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (secretVisible) Res.string.settings_hide_secret else Res.string.settings_show_secret,
                                    ),
                            )
                        }
                    },
                )
            } else {
                OutlinedTextField(
                    state = textFieldState,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = item.textPlaceholder?.let { placeholder -> ({ Text(placeholder) }) },
                    lineLimits =
                        if (item.multiline) {
                            TextFieldLineLimits.MultiLine(
                                minHeightInLines = 4,
                                maxHeightInLines = 12,
                            )
                        } else {
                            TextFieldLineLimits.SingleLine
                        },
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                TextButton(
                    onClick = {
                        item.onTextChanged?.invoke(textFieldState.text.toString())
                        onSettingValueChanged()
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.done)) }
            }
        }
    }
}

@Composable
internal fun SettingsConfirmationDialog(
    item: SettingsItemRegistration,
    onDismiss: () -> Unit,
    onSettingValueChanged: () -> Unit,
) {
    SettingsConfirmDialog(
        title = item.title,
        message = item.confirmationMessage,
        confirmLabel = stringResource(Res.string.clear),
        onConfirm = {
            item.onClick?.invoke()
            onSettingValueChanged()
        },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun SettingsConfirmDialog(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SettingsDialogSurface(onDismiss) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
            message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
                Button(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(confirmLabel)
                }
            }
        }
    }
}

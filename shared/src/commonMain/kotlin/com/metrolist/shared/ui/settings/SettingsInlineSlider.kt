/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.settings_reset_to_default
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.SliderDetents
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import org.jetbrains.compose.resources.stringResource

internal fun SettingsGroupRegistryBuilder.inlineSlider(
    id: String,
    title: String,
    valueLabel: String,
    value: Float,
    defaultValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    description: String? = null,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    custom(id, title, description, enabled) { onSettingValueChanged ->
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                SettingsResetButton(enabled = enabled && value != defaultValue) {
                    onValueChange(defaultValue)
                    onSettingValueChanged()
                }
            }
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            val haptics = rememberMetrolistHaptics()
            val detents = remember(range, steps) { SliderDetents(range, steps, value) }
            detents.reset(value)
            val sliderState = remember(range, steps) { SliderState(value = value, trackRange = range, steps = steps) }
            sliderState.value = value
            Slider(
                state = sliderState,
                onValueChange = {
                    if (detents.moved(it)) haptics.perform(HapticEvent.SEGMENT_TICK)
                    onValueChange(it)
                    onSettingValueChanged()
                },
                enabled = enabled,
                // Dense steps draw a row of touching dots; keep the snapping but hide the ticks.
                colors =
                    if (steps > MAX_VISIBLE_SLIDER_TICKS) {
                        SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
                    } else {
                        SliderDefaults.colors()
                    },
                modifier =
                    Modifier
                        .retainPlaybackKeyFocus()
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .semantics { contentDescription = title },
            )
        }
    }
}

@Composable
internal fun SettingsResetButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
        Icon(MetrolistIcons.Outlined.Replay, contentDescription = stringResource(Res.string.settings_reset_to_default))
    }
}

private const val MAX_VISIBLE_SLIDER_TICKS = 20

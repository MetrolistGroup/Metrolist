/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.shared.equalizer.NoiseCancellation
import com.metrolist.shared.equalizer.NoiseCancellationStatus
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_active
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_allow
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_description
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_error
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_permission
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_strength
import com.metrolist.shared.generated.resources.metroeq_noise_cancellation_unavailable
import com.metrolist.shared.generated.resources.percent_symbol
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NoiseCancellationControl() {
    val status by NoiseCancellation.status.collectAsState()
    var enabled by remember { mutableStateOf(NoiseCancellation.isEnabled) }
    var strength by remember { mutableFloatStateOf(NoiseCancellation.strength) }
    val title = stringResource(Res.string.metroeq_noise_cancellation)
    LaunchedEffect(Unit) { NoiseCancellation.refreshStatus() }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(Res.string.metroeq_noise_cancellation_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ExpressiveSwitch(
                    checked = enabled,
                    onCheckedChange = { value ->
                        enabled = value
                        NoiseCancellation.setEnabled(value)
                    },
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
            if (enabled) {
                val statusText =
                    when (status) {
                        NoiseCancellationStatus.ACTIVE -> {
                            stringResource(Res.string.metroeq_noise_cancellation_active)
                        }

                        NoiseCancellationStatus.PERMISSION_REQUIRED -> {
                            stringResource(Res.string.metroeq_noise_cancellation_permission)
                        }

                        NoiseCancellationStatus.UNAVAILABLE -> {
                            stringResource(Res.string.metroeq_noise_cancellation_unavailable)
                        }

                        NoiseCancellationStatus.ERROR -> {
                            stringResource(Res.string.metroeq_noise_cancellation_error)
                        }

                        NoiseCancellationStatus.STARTING, NoiseCancellationStatus.OFF -> {
                            null
                        }
                    }
                if (statusText != null) {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (status == NoiseCancellationStatus.ACTIVE) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
                if (status == NoiseCancellationStatus.PERMISSION_REQUIRED) {
                    TextButton(
                        onClick = NoiseCancellation::requestPermission,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.metroeq_noise_cancellation_allow))
                    }
                }
                if (status == NoiseCancellationStatus.ERROR) {
                    TextButton(
                        onClick = NoiseCancellation::refreshStatus,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.retry))
                    }
                }
                if (status == NoiseCancellationStatus.ACTIVE) {
                    val sliderState = rememberSliderState(value = strength, trackRange = 0f..1f)
                    sliderState.value = strength
                    Text(
                        stringResource(
                            Res.string.metroeq_noise_cancellation_strength,
                            (strength * 100).roundToInt().toString(),
                            stringResource(Res.string.percent_symbol),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Slider(
                        state = sliderState,
                        onValueChange = {
                            strength = it
                            sliderState.value = it
                            NoiseCancellation.setStrength(it)
                        },
                        modifier = Modifier.retainPlaybackKeyFocus().semantics { contentDescription = title },
                    )
                }
            }
        }
    }
}

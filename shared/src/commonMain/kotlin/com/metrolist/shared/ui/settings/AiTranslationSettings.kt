/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.api.lyrics.AiModelCatalog
import com.metrolist.shared.api.lyrics.AiModelInfo
import com.metrolist.shared.api.lyrics.AiProviderGroup
import com.metrolist.shared.api.lyrics.AiProviderPreset
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.ui.components.BasicAlertDialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun AiPickerRow(
    title: String,
    value: String,
    description: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(MetrolistIcons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AiDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        ) { content() }
    }
}

@Composable
private fun AiChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.weight(1f)) { content() }
        if (selected) Icon(MetrolistIcons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun AiProviderPicker(
    selected: AiProviderPreset,
    onSelected: (AiProviderPreset) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }
    Box(Modifier.trackPressPosition { anchorOffset = it.round() }) {
        AiPickerRow(
            stringResource(Res.string.ai_provider),
            lyricsAiProviderLabel(selected.id),
            description = stringResource(Res.string.settings_translation_provider_summary),
        ) { open = true }
        ExpressiveSelectionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            anchorOffset = anchorOffset,
            options = AiProviderPresets.all,
            selectedOption = selected,
            optionLabel = { lyricsAiProviderLabel(it.id) },
            optionSupportingText = { preset ->
                stringResource(
                    when (preset.group) {
                        AiProviderGroup.GATEWAY -> Res.string.ai_provider_group_gateways
                        AiProviderGroup.DIRECT -> Res.string.ai_provider_group_direct
                        AiProviderGroup.CUSTOM -> Res.string.ai_provider_group_custom
                        AiProviderGroup.SERVICE -> Res.string.ai_provider_group_services
                    },
                )
            },
            onOptionSelected = onSelected,
        )
    }
}

private sealed interface ModelsState {
    data object Loading : ModelsState

    data object Failed : ModelsState

    data class Loaded(
        val models: List<AiModelInfo>,
    ) : ModelsState
}

@Composable
internal fun AiModelPicker(
    title: String,
    description: String? = null,
    preset: AiProviderPreset,
    model: String,
    apiKey: String,
    baseUrl: String,
    allowBlank: Boolean,
    enabled: Boolean = true,
    freeOnly: Boolean = false,
    onSelected: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val blankLabel = stringResource(Res.string.ai_model_same_as_translation)
    AiPickerRow(
        title,
        model.ifBlank { if (allowBlank) blankLabel else stringResource(Res.string.ai_provider_default) },
        description = description,
        enabled = enabled,
    ) {
        if (enabled) open = true
    }
    if (!open) return
    val catalog = remember { KoinPlatform.getKoin().get<AiModelCatalog>() }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val state by produceState<ModelsState>(ModelsState.Loading, preset.id, apiKey, baseUrl, reload) {
        value = ModelsState.Loading
        value = catalog.models(preset, apiKey, baseUrl, refresh = reload > 0).fold({ ModelsState.Loaded(it) }, { ModelsState.Failed })
    }
    val pick = { id: String ->
        open = false
        onSelected(id)
    }
    AiDialog({ open = false }) {
        Column(Modifier.padding(top = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton({ reload++ }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.refresh)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(Res.string.ai_model_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
            DragScrollableColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (allowBlank && query.isBlank()) {
                    item(key = "blank") {
                        AiChoiceRow(model.isBlank(), { pick("") }) { Text(blankLabel, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                val typed = query.trim()
                val models = (state as? ModelsState.Loaded)?.models.orEmpty()
                if (!freeOnly && typed.isNotEmpty() && models.none { it.id == typed }) {
                    item(key = "custom") {
                        AiChoiceRow(false, { pick(typed) }) {
                            Text("${stringResource(Res.string.ai_model_custom_id)}: $typed", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                when (val current = state) {
                    ModelsState.Loading -> {
                        items(5) { AiModelSkeleton() }
                    }

                    ModelsState.Failed -> {
                        item(key = "failed") {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                                Text(
                                    stringResource(
                                        if (apiKey.isBlank() &&
                                            !preset.publicModelList
                                        ) {
                                            Res.string.ai_model_api_key_needed
                                        } else {
                                            Res.string.ai_model_load_failed
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton({ reload++ }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
                            }
                        }
                    }

                    is ModelsState.Loaded -> {
                        val shown =
                            current.models.filter { !freeOnly || (it.free && it.id.endsWith(":free")) }.filter {
                                typed.isEmpty() ||
                                    it.id.contains(
                                        typed,
                                        true,
                                    ) || it.name.contains(typed, true)
                            }
                        if (shown.isEmpty() && typed.isNotEmpty()) {
                            item(key = "empty") {
                                Text(
                                    stringResource(Res.string.ai_model_none_found),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                )
                            }
                        }
                        items(shown, key = { it.id }) { info ->
                            AiChoiceRow(info.id == model, { pick(info.id) }) { AiModelDetails(info, catalog, preset) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ open = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}

@Composable
private fun AiModelSkeleton() {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shape = MaterialTheme.shapes.small
        Box(
            Modifier
                .fillMaxWidth(0.6f)
                .height(16.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Box(
            Modifier
                .fillMaxWidth(0.4f)
                .height(12.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    }
}

@Composable
private fun AiModelDetails(
    info: AiModelInfo,
    catalog: AiModelCatalog,
    preset: AiProviderPreset,
) {
    val speed by produceState<Double?>(null, info.id) { if (preset.id == "OpenRouter") value = catalog.throughput(info.id) }
    Column {
        Text(info.name, style = MaterialTheme.typography.bodyLarge)
        if (info.name !=
            info.id
        ) {
            Text(info.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val price =
            when {
                info.free -> {
                    stringResource(Res.string.ai_model_free)
                }

                info.inputUsdPerMTok != null && info.outputUsdPerMTok != null -> {
                    stringResource(Res.string.ai_model_price, formatUsd(info.inputUsdPerMTok), formatUsd(info.outputUsdPerMTok))
                }

                else -> {
                    null
                }
            }
        val tps = speed?.let { stringResource(Res.string.ai_model_speed, it.roundToInt().toString()) }
        listOfNotNull(price, tps).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("  ·\u00a0\u00a0"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun formatUsd(value: Double): String {
    val digits = if (value >= 1.0) 2 else 3
    val scale = 10.0.pow(digits).roundToLong()
    val scaled = (value * scale).roundToLong()
    return "\$${scaled / scale}.${(scaled % scale).toString().padStart(digits, '0')}"
}

/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.equalizer

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.equalizer.AutoEqCatalog
import com.metrolist.shared.equalizer.AutoEqEntry
import com.metrolist.shared.equalizer.AutoEqModel
import com.metrolist.shared.equalizer.EqualizerPreset
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.equalizer.FilterType
import com.metrolist.shared.equalizer.MAX_AUDIO_BOOST_DB
import com.metrolist.shared.equalizer.MAX_EQUALIZER_BANDS
import com.metrolist.shared.equalizer.MAX_EQUALIZER_BAND_GAIN_DB
import com.metrolist.shared.equalizer.MAX_EQUALIZER_FREQUENCY
import com.metrolist.shared.equalizer.MIN_AUDIO_PREAMP_DB
import com.metrolist.shared.equalizer.MIN_EQUALIZER_BAND_GAIN_DB
import com.metrolist.shared.equalizer.MIN_EQUALIZER_FREQUENCY
import com.metrolist.shared.equalizer.ParametricEqualizer
import com.metrolist.shared.equalizer.bandsByFrequency
import com.metrolist.shared.equalizer.searchAutoEqModels
import com.metrolist.shared.equalizer.supportsGain
import com.metrolist.shared.equalizer.withAdjustedBand
import com.metrolist.shared.equalizer.withAdjustedGain
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add
import com.metrolist.shared.generated.resources.adjust_audio_equalizer
import com.metrolist.shared.generated.resources.audio_boost
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.eq_disabled
import com.metrolist.shared.generated.resources.eq_wizard
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.equalizer_autoeq_unavailable
import com.metrolist.shared.generated.resources.equalizer_band_gain
import com.metrolist.shared.generated.resources.equalizer_create_custom
import com.metrolist.shared.generated.resources.equalizer_invalid_profile
import com.metrolist.shared.generated.resources.equalizer_profile_count
import com.metrolist.shared.generated.resources.equalizer_stream_unavailable
import com.metrolist.shared.generated.resources.equalizer_usage_warning
import com.metrolist.shared.generated.resources.import_from_file
import com.metrolist.shared.generated.resources.loudness_value
import com.metrolist.shared.generated.resources.metroeq_custom_profile
import com.metrolist.shared.generated.resources.metroeq_delete_profile_confirm
import com.metrolist.shared.generated.resources.metroeq_frequency_value
import com.metrolist.shared.generated.resources.metroeq_peak_reduction
import com.metrolist.shared.generated.resources.metroeq_preset_bass
import com.metrolist.shared.generated.resources.metroeq_preset_flat
import com.metrolist.shared.generated.resources.metroeq_preset_rock
import com.metrolist.shared.generated.resources.metroeq_preset_treble
import com.metrolist.shared.generated.resources.metroeq_preset_v_shape
import com.metrolist.shared.generated.resources.metroeq_preset_vocal
import com.metrolist.shared.generated.resources.metroeq_presets
import com.metrolist.shared.generated.resources.metroeq_profile_name
import com.metrolist.shared.generated.resources.metroeq_profiles
import com.metrolist.shared.generated.resources.metroeq_q_value
import com.metrolist.shared.generated.resources.metroeq_save_as
import com.metrolist.shared.generated.resources.metroeq_unsaved_changes
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.remove
import com.metrolist.shared.generated.resources.rename
import com.metrolist.shared.generated.resources.reset
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.sample_rate_value
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.saved
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.settings_filter_q_summary
import com.metrolist.shared.generated.resources.wizard_multiple_variants
import com.metrolist.shared.generated.resources.wizard_no_models
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.settings.LocalSettingsPageBottomPadding
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroEqScreen(
    rememberProfilePicker: (@Composable (onPicked: (fileName: String, content: String) -> Unit) -> () -> Unit)? = null,
    allowAutoEqSearch: Boolean = true,
    isAudioProcessingAvailable: Boolean = true,
    initialAutoEqSearch: Boolean = false,
) {
    var searchingAutoEq by remember { mutableStateOf(initialAutoEqSearch) }
    var importError by remember { mutableStateOf<StringResource?>(null) }
    val pickFile =
        rememberProfilePicker?.invoke { fileName, content ->
            importError =
                runCatching { EqualizerProfiles.importText(fileName, content) }
                    .fold({ null }, { Res.string.equalizer_invalid_profile })
        }
    Column(Modifier.fillMaxSize()) {
        if (!isAudioProcessingAvailable) {
            Text(
                stringResource(Res.string.equalizer_stream_unavailable),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        AnimatedContent(targetState = searchingAutoEq, modifier = Modifier.weight(1f)) { autoEq ->
            if (autoEq) {
                AutoEqPage(onBack = { searchingAutoEq = false }, onDismiss = { searchingAutoEq = false })
            } else {
                ProfilesPage(
                    error = importError,
                    onImportFile = pickFile,
                    onSearchAutoEq =
                        if (allowAutoEqSearch) {
                            { searchingAutoEq = true }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun ProfilesPage(
    error: StringResource?,
    onImportFile: (() -> Unit)?,
    onSearchAutoEq: (() -> Unit)?,
) {
    var meter by remember {
        mutableStateOf(
            MeterReadout(
                AudioSpectrumMeter.sampleRate,
                AudioSpectrumMeter.snapshotLevels(),
                AudioSpectrumMeter.gainReductionDb,
            ),
        )
    }
    DisposableEffect(Unit) {
        AudioSpectrumMeter.addConsumer()
        onDispose { AudioSpectrumMeter.removeConsumer() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val reduction = AudioSpectrumMeter.gainReductionDb
            meter =
                MeterReadout(
                    AudioSpectrumMeter.sampleRate,
                    withContext(Dispatchers.Default) { AudioSpectrumMeter.levels() },
                    maxOf(reduction, meter.gainReductionDb - 0.3f),
                )
            delay(100)
        }
    }
    val profiles = EqualizerProfiles.profiles
    val activeProfile = EqualizerProfiles.activeProfile
    val activeId = activeProfile?.id
    val preset = EqualizerPreset.entries.firstOrNull { it.profileId == activeId }
    val currentTitle = if (preset != null) presetName(preset) else activeProfile?.name
    val customProfileName = stringResource(Res.string.metroeq_custom_profile)
    val equalizerLabel = stringResource(Res.string.equalizer)
    val savedProfile = profiles.any { it.id == activeId }
    var namingProfile by remember { mutableStateOf(false) }
    var confirmingProfileDelete by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var profileName by remember(activeId, currentTitle) { mutableStateOf(currentTitle ?: customProfileName) }
    var pendingProfileChange by remember { mutableStateOf<(() -> Unit)?>(null) }
    val hasUnsavedChanges = EqualizerProfiles.hasUnsavedChanges

    fun changeProfile(action: () -> Unit) {
        if (hasUnsavedChanges) pendingProfileChange = action else action()
    }

    fun requestSave(asCopy: Boolean) {
        if (savedProfile && !asCopy) {
            EqualizerProfiles.saveCurrent(checkNotNull(activeProfile).name)
        } else {
            saveAs = asCopy
            profileName = currentTitle ?: customProfileName
            namingProfile = true
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    var selectingProfile by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clip(MaterialTheme.shapes.large).clickable { selectingProfile = true },
                        trailingContent = { Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) {
                        Text(
                            currentTitle ?: stringResource(Res.string.eq_disabled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    DropdownMenu(expanded = selectingProfile, onDismissRequest = { selectingProfile = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.eq_disabled)) },
                            onClick = {
                                selectingProfile = false
                                EqualizerProfiles.setEnabled(false)
                            },
                        )
                        Text(
                            stringResource(Res.string.metroeq_presets),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        EqualizerPreset.entries.forEach { choice ->
                            val title = presetName(choice)
                            DropdownMenuItem(
                                text = { Text(title) },
                                onClick = {
                                    selectingProfile = false
                                    changeProfile { EqualizerProfiles.activatePreset(choice, title) }
                                },
                            )
                        }
                        if (profiles.isNotEmpty()) {
                            Text(
                                stringResource(Res.string.metroeq_profiles),
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            profiles.forEach { profile ->
                                DropdownMenuItem(
                                    text = { Text(profile.name) },
                                    onClick = {
                                        selectingProfile = false
                                        changeProfile { EqualizerProfiles.setActive(profile.id) }
                                    },
                                )
                            }
                        }
                    }
                }
                if (savedProfile) {
                    IconButton(onClick = { confirmingProfileDelete = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            MetrolistIcons.Outlined.Delete,
                            contentDescription = stringResource(Res.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                ExpressiveSwitch(
                    checked = EqualizerProfiles.isEnabled,
                    onCheckedChange = EqualizerProfiles::setEnabled,
                    modifier =
                        Modifier.padding(end = 12.dp).semantics {
                            contentDescription = equalizerLabel
                        },
                )
            }
        }
        error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        EqualizerGraph(activeProfile, meter.sampleRate, meter.levels)
        PreampControl(meter.gainReductionDb)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (EqualizerProfiles.hasUnsavedChanges) Res.string.metroeq_unsaved_changes else Res.string.saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                TextButton(
                    onClick = { requestSave(false) },
                    enabled = EqualizerProfiles.hasUnsavedChanges,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.save))
                }
                Box {
                    var actionsExpanded by remember { mutableStateOf(false) }
                    IconButton(
                        modifier = Modifier.navigationPopupSource(),
                        onClick = { actionsExpanded = true },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_options))
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.metroeq_save_as)) },
                            onClick = {
                                actionsExpanded = false
                                requestSave(true)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.equalizer_create_custom)) },
                            onClick = {
                                actionsExpanded = false
                                changeProfile { EqualizerProfiles.createCustom(customProfileName) }
                            },
                        )
                        if (activeProfile != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.reset)) },
                                onClick = {
                                    actionsExpanded = false
                                    EqualizerProfiles.resetAllBands()
                                },
                            )
                        }
                        onSearchAutoEq?.let { search ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.eq_wizard)) },
                                onClick = {
                                    actionsExpanded = false
                                    changeProfile { search() }
                                },
                            )
                        }
                        onImportFile?.let { import ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.import_from_file)) },
                                onClick = {
                                    actionsExpanded = false
                                    changeProfile { import() }
                                },
                            )
                        }
                        if (activeProfile != null && savedProfile) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.rename)) },
                                onClick = {
                                    actionsExpanded = false
                                    saveAs = false
                                    profileName = activeProfile.name
                                    namingProfile = true
                                },
                            )
                        }
                    }
                }
            }
        }
        Text(
            stringResource(Res.string.equalizer_usage_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
    PopupVisibility(visible = namingProfile) {
        AlertDialog(
            onDismissRequest = { namingProfile = false },
            title = { Text(stringResource(if (saveAs) Res.string.metroeq_save_as else Res.string.save)) },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text(stringResource(Res.string.metroeq_profile_name)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = profileName.isNotBlank(),
                    onClick = {
                        EqualizerProfiles.saveCurrent(profileName, saveAs = saveAs)
                        namingProfile = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.save)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { namingProfile = false },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    PopupContent(pendingProfileChange) { action ->
        ConfirmEqualizerProfileChange(action = action, onDismiss = { pendingProfileChange = null })
    }
    if (confirmingProfileDelete && activeProfile != null) {
        AlertDialog(
            onDismissRequest = { confirmingProfileDelete = false },
            text = {
                Text(stringResource(Res.string.metroeq_delete_profile_confirm, activeProfile.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        EqualizerProfiles.delete(activeProfile.id)
                        confirmingProfileDelete = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingProfileDelete = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

private data class MeterReadout(
    val sampleRate: Int,
    val levels: FloatArray,
    val gainReductionDb: Float,
)

@Composable
private fun presetName(preset: EqualizerPreset): String =
    stringResource(
        when (preset) {
            EqualizerPreset.FLAT -> Res.string.metroeq_preset_flat
            EqualizerPreset.BASS -> Res.string.metroeq_preset_bass
            EqualizerPreset.TREBLE -> Res.string.metroeq_preset_treble
            EqualizerPreset.VOCAL -> Res.string.metroeq_preset_vocal
            EqualizerPreset.ROCK -> Res.string.metroeq_preset_rock
            EqualizerPreset.V_SHAPE -> Res.string.metroeq_preset_v_shape
        },
    )

@Composable
private fun EqualizerGraph(
    profile: EqualizerProfile?,
    rate: Int,
    levels: FloatArray,
) {
    var selectedBand by remember(profile?.id) { mutableIntStateOf(0) }
    var editingQ by remember(profile?.id, selectedBand) { mutableStateOf(false) }
    val latestProfile by rememberUpdatedState(profile)
    val maxFrequency = minOf(MAX_EQUALIZER_FREQUENCY, rate * 0.475)
    val logarithmicSpan = ln(maxFrequency / MIN_EQUALIZER_FREQUENCY)
    val preamp = EqualizerProfiles.selectedConfiguration.preampDb.coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
    var response by remember { mutableStateOf(emptyList<Pair<Double, Double>>()) }
    LaunchedEffect(profile, rate, preamp, logarithmicSpan) {
        response =
            withContext(Dispatchers.Default) {
                val preview = ParametricEqualizer(rate, 1).apply { update(profile) }
                List(160) { index ->
                    val fraction = index / 159.0
                    fraction to (preview.responseDb(MIN_EQUALIZER_FREQUENCY * exp(fraction * logarithmicSpan)) + preamp)
                }
            }
    }
    var dragRange by remember { mutableStateOf<Double?>(null) }
    val range =
        dragRange ?: maxOf(
            MAX_EQUALIZER_BAND_GAIN_DB + abs(preamp),
            ceil((response.maxOfOrNull { abs(it.second) } ?: 0.0) / MAX_AUDIO_BOOST_DB) * MAX_AUDIO_BOOST_DB,
        ).coerceAtMost(96.0)
    val currentRange by rememberUpdatedState(range)
    val currentSpan by rememberUpdatedState(logarithmicSpan)
    val currentMaxFrequency by rememberUpdatedState(maxFrequency)
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gainLabels = listOf(range, 0.0, -range).map { stringResource(Res.string.loudness_value, it.toInt().toString()) }
    val labelInset = with(density) { 16.dp.toPx() }
    val left = labelInset + gainLabels.maxOf { textMeasurer.measure(it, labelStyle).size.width }.toFloat() + with(density) { 8.dp.toPx() }
    val currentLeft by rememberUpdatedState(left)
    val right = with(density) { 16.dp.toPx() }
    val top = with(density) { 20.dp.toPx() }
    val bottom = with(density) { 32.dp.toPx() }
    val frequencies = listOf(100, 1000, 10000).filter { it < maxFrequency }
    val frequencyLabels = frequencies.map { stringResource(Res.string.sample_rate_value, it) }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainer
    val graphDescription = stringResource(Res.string.adjust_audio_equalizer)

    fun editableBand(index: Int): Boolean {
        val band = latestProfile?.bands?.getOrNull(index) ?: return false
        return band.enabled && band.frequency in MIN_EQUALIZER_FREQUENCY..currentMaxFrequency
    }

    fun updateGain(
        index: Int,
        gain: Double,
    ) {
        val current = latestProfile ?: return
        if (!editableBand(index)) return
        val updatedBand = current.bands[index].withAdjustedGain(gain)
        if (current.bands[index] == updatedBand) return
        val updated = current.bands.toMutableList()
        updated[index] = updatedBand
        EqualizerProfiles.editCurrent(current.copy(bands = updated))
    }

    fun updateQ(
        index: Int,
        q: Double,
    ) {
        val current = latestProfile ?: return
        if (!editableBand(index)) return
        val updatedQ = q.coerceIn(0.1, 20.0)
        if (current.bands[index].q == updatedQ) return
        val updated = current.bands.toMutableList()
        updated[index] = updated[index].copy(q = updatedQ)
        EqualizerProfiles.editCurrent(current.copy(bands = updated))
    }

    fun updateBand(
        index: Int,
        gain: Double,
        frequency: Double,
    ): Double? {
        val current = latestProfile ?: return null
        if (!editableBand(index)) return null
        val updated = current.withAdjustedBand(index, gain, frequency, currentMaxFrequency)
        if (current != updated) EqualizerProfiles.editCurrent(updated)
        return updated.bands[index].frequency
    }

    fun nearestBand(
        x: Float,
        width: Int,
    ): Int? {
        val current = latestProfile ?: return null
        return current.bands.indices.filter(::editableBand).minByOrNull { index ->
            val frequency = current.bands[index].frequency
            abs(currentLeft + ln(frequency / MIN_EQUALIZER_FREQUENCY) / currentSpan * (width - currentLeft - right).coerceAtLeast(1f) - x)
        }
    }

    val gestures =
        if (profile == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(profile.id, top, bottom) {
                    detectTapGestures { position -> nearestBand(position.x, size.width)?.let { selectedBand = it } }
                }.pointerInput(profile.id, top, bottom) {
                    var dragBand: Int? = null
                    var dragGain: Double? = null
                    var dragFrequency: Double? = null
                    detectDragGestures(
                        onDragEnd = { dragRange = null },
                        onDragCancel = { dragRange = null },
                        onDragStart = { position ->
                            dragRange = currentRange
                            dragBand = nearestBand(position.x, size.width)
                            dragGain = null
                            dragFrequency = null
                            dragBand?.let { index ->
                                selectedBand = index
                                dragGain = latestProfile?.bands?.getOrNull(index)?.gain
                                dragFrequency = latestProfile?.bands?.getOrNull(index)?.frequency
                            }
                        },
                        onDrag = { change, drag ->
                            val index = dragBand ?: return@detectDragGestures
                            val band = latestProfile?.bands?.getOrNull(index)
                            val previousGain = dragGain
                            val previousFrequency = dragFrequency
                            if (band != null && previousGain != null && previousFrequency != null && editableBand(index)) {
                                change.consume()
                                val sensitivity = if (band.filterType == FilterType.PK) 1.0 else 0.5
                                val nextGain =
                                    (previousGain - drag.y * 2 * currentRange / ((size.height - top - bottom) * sensitivity))
                                        .coerceIn(MIN_EQUALIZER_BAND_GAIN_DB, MAX_EQUALIZER_BAND_GAIN_DB)
                                val graphWidth = (size.width - currentLeft - right).coerceAtLeast(1f)
                                val nextFrequency =
                                    (previousFrequency * exp(drag.x / graphWidth * currentSpan))
                                        .coerceIn(MIN_EQUALIZER_FREQUENCY, currentMaxFrequency)
                                dragGain = nextGain
                                dragFrequency = updateBand(index, nextGain, nextFrequency)
                            }
                        },
                    )
                }
        }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(224.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(backgroundColor)
                    .semantics { contentDescription = graphDescription }
                    .then(gestures),
            ) {
                val width = size.width - left - right
                val height = size.height - top - bottom

                val plotRange = range.toFloat()

                fun x(frequency: Double) = left + (ln(frequency / MIN_EQUALIZER_FREQUENCY) / logarithmicSpan * width).toFloat()

                fun y(db: Double) = top + ((plotRange - db.toFloat()) / (plotRange * 2) * height)
                frequencies.forEachIndexed { index, frequency ->
                    val px = x(frequency.toDouble())
                    drawLine(gridColor, Offset(px, top), Offset(px, top + height))
                    val label = textMeasurer.measure(frequencyLabels[index], labelStyle)
                    drawText(label, topLeft = Offset(px - label.size.width / 2, top + height + 4.dp.toPx()))
                }
                listOf(plotRange, 0f, -plotRange).forEachIndexed { index, gain ->
                    val py = top + ((plotRange - gain) / (plotRange * 2) * height)
                    drawLine(gridColor, Offset(left, py), Offset(left + width, py), if (gain == 0f) 2.dp.toPx() else 1.dp.toPx())
                    val label = textMeasurer.measure(gainLabels[index], labelStyle)
                    drawText(label, topLeft = Offset(2.dp.toPx(), py - label.size.height / 2))
                }
                clipRect(left, top, left + width, top + height) {
                    val barWidth = minOf(width / 24, 4.dp.toPx())
                    levels.forEachIndexed { index, level ->
                        val px = x(AudioSpectrumMeter.frequencies[index])
                        drawRect(
                            lineColor.copy(alpha = 0.12f + level * 0.08f),
                            Offset(px - barWidth / 2, top + height * (1 - level)),
                            Size(
                                barWidth,
                                height * level,
                            ),
                        )
                    }
                    response.zipWithNext().forEach { (start, end) ->
                        drawLine(
                            lineColor,
                            Offset(left + start.first.toFloat() * width, y(start.second)),
                            Offset(left + end.first.toFloat() * width, y(end.second)),
                            2.dp.toPx(),
                        )
                    }
                    profile?.bands?.forEachIndexed { index, band ->
                        if (editableBand(index)) {
                            val px = x(band.frequency)
                            val gainY = y(band.gain + preamp)
                            val selected = index == selectedBand
                            val color = if (selected) lineColor else lineColor.copy(alpha = 0.62f)
                            if (selected) {
                                drawLine(lineColor.copy(alpha = 0.18f), Offset(left, gainY), Offset(left + width, gainY), 1.dp.toPx())
                                drawCircle(lineColor.copy(alpha = 0.18f), 11.dp.toPx(), Offset(px, gainY))
                            }
                            drawCircle(backgroundColor, if (selected) 8.dp.toPx() else 6.dp.toPx(), Offset(px, gainY))
                            drawCircle(color, if (selected) 8.dp.toPx() else 6.dp.toPx(), Offset(px, gainY), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
            }
            if (profile != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        enabled = profile.bands.size < MAX_EQUALIZER_BANDS,
                        onClick = {
                            EqualizerProfiles.addBand(MIN_EQUALIZER_FREQUENCY, maxFrequency)
                            selectedBand = profile.bands.size
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.add)) }
                    TextButton(
                        enabled = selectedBand in profile.bands.indices,
                        onClick = {
                            EqualizerProfiles.removeBand(selectedBand)
                            selectedBand = (selectedBand - 1).coerceAtLeast(0)
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.remove)) }
                    TextButton(
                        enabled = EqualizerProfiles.bandHasChanges(selectedBand),
                        onClick = { EqualizerProfiles.resetBand(selectedBand) },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.reset)) }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    profile.bandsByFrequency.forEach { (index, band) ->
                        val bandLabel =
                            stringResource(
                                Res.string.equalizer_band_gain,
                                band.frequency.toInt().toString(),
                                band.gain.toString(),
                            )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                stringResource(Res.string.sample_rate_value, band.frequency.toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                            VerticalBandFader(
                                gain = band.gain,
                                selected = index == selectedBand,
                                enabled = editableBand(index) && band.supportsGain,
                                onSelected = { selectedBand = index },
                                onGainChange = { gain -> updateGain(index, gain) },
                            )
                            ExpressiveSwitch(
                                checked = band.enabled,
                                onCheckedChange = { enabled ->
                                    val current = latestProfile ?: return@ExpressiveSwitch
                                    val updated = current.bands.toMutableList()
                                    updated[index] = band.copy(enabled = enabled)
                                    EqualizerProfiles.editCurrent(current.copy(bands = updated))
                                },
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = bandLabel
                                    },
                            )
                            Text(
                                stringResource(Res.string.loudness_value, band.gain.toString()),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
                profile.bands.getOrNull(selectedBand)?.takeIf { editableBand(selectedBand) }?.let { band ->
                    val gainLabel =
                        stringResource(
                            Res.string.equalizer_band_gain,
                            band.frequency.toInt().toString(),
                            band.gain.toString(),
                        )
                    val frequencyLabel = stringResource(Res.string.metroeq_frequency_value, band.frequency.toInt().toString())
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (band.supportsGain) {
                            val gainSliderState =
                                rememberSliderState(
                                    value = band.gain.toFloat(),
                                    trackRange = MIN_EQUALIZER_BAND_GAIN_DB.toFloat()..MAX_EQUALIZER_BAND_GAIN_DB.toFloat(),
                                )
                            gainSliderState.value = band.gain.toFloat()
                            Slider(
                                state = gainSliderState,
                                onValueChange = {
                                    gainSliderState.value = it
                                    updateGain(selectedBand, it.toDouble())
                                },
                                modifier = Modifier.retainPlaybackKeyFocus().semantics { contentDescription = gainLabel },
                            )
                        }
                        val frequency = (ln(band.frequency / MIN_EQUALIZER_FREQUENCY) / logarithmicSpan).toFloat()
                        val frequencySliderState = rememberSliderState(value = frequency, trackRange = 0f..1f)
                        frequencySliderState.value = frequency
                        Slider(
                            state = frequencySliderState,
                            onValueChange = { fraction ->
                                frequencySliderState.value = fraction
                                updateBand(selectedBand, band.gain, MIN_EQUALIZER_FREQUENCY * exp(fraction * logarithmicSpan))
                            },
                            modifier = Modifier.retainPlaybackKeyFocus().semantics { contentDescription = frequencyLabel },
                        )
                    }
                }
            }
            profile?.bands?.getOrNull(selectedBand)?.let { band ->
                Box(Modifier.fillMaxWidth()) {
                    var choosingBand by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { choosingBand = true },
                        trailingContent = { Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) {
                        Text(
                            stringResource(Res.string.equalizer_band_gain, band.frequency.toInt().toString(), band.gain.toString()),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    DropdownMenu(expanded = choosingBand, onDismissRequest = { choosingBand = false }) {
                        profile.bandsByFrequency.forEach { (index, item) ->
                            if (editableBand(index)) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                Res.string.equalizer_band_gain,
                                                item.frequency.toInt().toString(),
                                                item.gain.toString(),
                                            ),
                                        )
                                    },
                                    onClick = {
                                        selectedBand = index
                                        choosingBand = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (editableBand(selectedBand)) {
                    TextButton(onClick = { editingQ = true }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.metroeq_q_value, (round(band.q * 100) / 100).toString()))
                    }
                    Text(
                        stringResource(Res.string.settings_filter_q_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (editingQ) {
        profile?.bands?.getOrNull(selectedBand)?.let { band ->
            var qValue by remember(selectedBand, band.q) { mutableFloatStateOf(band.q.toFloat()) }
            val qFraction = ln(qValue.coerceIn(0.1f, 20f) / 0.1f) / ln(20f / 0.1f)
            val qSliderState = rememberSliderState(value = qFraction)
            qSliderState.value = qFraction
            AlertDialog(
                onDismissRequest = { editingQ = false },
                title = { Text(stringResource(Res.string.metroeq_q_value, (round(qValue * 100) / 100).toString())) },
                text = {
                    Slider(
                        state = qSliderState,
                        onValueChange = { fraction ->
                            qSliderState.value = fraction
                            qValue = (0.1f * exp(fraction * ln(20f / 0.1f))).coerceIn(0.1f, 20f)
                        },
                        modifier = Modifier.retainPlaybackKeyFocus(),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        updateQ(selectedBand, qValue.toDouble())
                        editingQ = false
                    }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { editingQ = false }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
                },
            )
        }
    }
}

@Composable
private fun VerticalBandFader(
    gain: Double,
    selected: Boolean,
    enabled: Boolean,
    onSelected: () -> Unit,
    onGainChange: (Double) -> Unit,
) {
    val color =
        if (!enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        } else if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
        }
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val gainSpan = MAX_EQUALIZER_BAND_GAIN_DB - MIN_EQUALIZER_BAND_GAIN_DB
    val latestOnSelected by rememberUpdatedState(onSelected)
    val latestOnGainChange by rememberUpdatedState(onGainChange)
    Canvas(
        Modifier
            .size(width = 48.dp, height = 132.dp)
            .pointerInput(enabled) {
                detectTapGestures { position ->
                    latestOnSelected()
                    if (enabled) {
                        val inset = 8.dp.toPx()
                        val fraction = ((position.y - inset) / (size.height - inset * 2)).coerceIn(0f, 1f)
                        latestOnGainChange(MAX_EQUALIZER_BAND_GAIN_DB - fraction * gainSpan)
                    }
                }
            }.pointerInput(enabled) {
                detectDragGestures(
                    onDragStart = { latestOnSelected() },
                    onDrag = { change, _ ->
                        change.consume()
                        val inset = 8.dp.toPx()
                        val fraction = ((change.position.y - inset) / (size.height - inset * 2)).coerceIn(0f, 1f)
                        latestOnGainChange(MAX_EQUALIZER_BAND_GAIN_DB - fraction * gainSpan)
                    },
                )
            },
    ) {
        val inset = 8.dp.toPx()
        val x = size.width / 2
        val zeroY = size.height / 2
        val gainY =
            inset +
                (
                    (MAX_EQUALIZER_BAND_GAIN_DB - gain.coerceIn(MIN_EQUALIZER_BAND_GAIN_DB, MAX_EQUALIZER_BAND_GAIN_DB)) /
                        gainSpan * (size.height - inset * 2)
                ).toFloat()
        val trackWidth = if (selected) 6.dp.toPx() else 4.dp.toPx()
        drawLine(color = background, start = Offset(x, inset), end = Offset(x, size.height - inset), strokeWidth = trackWidth)
        if (gain != 0.0) {
            drawLine(color = color, start = Offset(x, zeroY), end = Offset(x, gainY), strokeWidth = trackWidth)
        }
        drawCircle(background, if (selected) 8.dp.toPx() else 6.dp.toPx(), Offset(x, gainY))
        drawCircle(color, if (selected) 8.dp.toPx() else 6.dp.toPx(), Offset(x, gainY), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun PreampControl(reduction: Float) {
    val preamp =
        EqualizerProfiles.selectedConfiguration.preampDb
            .coerceIn(MIN_AUDIO_PREAMP_DB, MAX_AUDIO_BOOST_DB)
            .toFloat()
    val preampRange = MIN_AUDIO_PREAMP_DB.toFloat()..MAX_AUDIO_BOOST_DB.toFloat()
    val preampSteps = ((MAX_AUDIO_BOOST_DB - MIN_AUDIO_PREAMP_DB) * 2).toInt() - 1
    val preampSliderState = rememberSliderState(value = preamp, trackRange = preampRange, steps = preampSteps)
    preampSliderState.value = preamp
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.audio_boost),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Text(
                    stringResource(Res.string.loudness_value, preamp.toString()),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                state = preampSliderState,
                onValueChange = {
                    preampSliderState.value = it
                    EqualizerProfiles.setPreamp(it.toDouble())
                },
                modifier = Modifier.retainPlaybackKeyFocus(),
            )
            Box(Modifier.fillMaxWidth().height(24.dp)) {
                if (reduction >= 0.5f) {
                    Text(
                        stringResource(Res.string.metroeq_peak_reduction, (-reduction).roundToInt().toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoEqPage(
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    var models by remember { mutableStateOf<List<AutoEqModel>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var model by remember { mutableStateOf<AutoEqModel?>(null) }
    var loadingEntry by remember { mutableStateOf<AutoEqEntry?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(attempt) {
        failed = false
        try {
            models = AutoEqCatalog.models()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }
    val results = remember(models, query) { models?.let { searchAutoEqModels(it, query) } }
    val goBack = { if (model != null) model = null else onBack() }
    NavigationBackHandler(onBack = goBack)

    fun add(entry: AutoEqEntry) {
        if (loadingEntry != null) return
        loadingEntry = entry
        failed = false
        scope.launch {
            try {
                val profile = AutoEqCatalog.load(entry)
                val nameTaken = EqualizerProfiles.profiles.any { it.name == profile.name && it.id != profile.id }
                EqualizerProfiles.importProfile(if (nameTaken) profile.copy(name = "${profile.name} · ${entry.source}") else profile)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                loadingEntry = null
            }
        }
    }

    Column(Modifier.padding(vertical = 24.dp)) {
        val shownModel = model
        DialogHeader(
            title = shownModel?.name ?: stringResource(Res.string.eq_wizard),
            subtitle =
                (shownModel?.entries?.size ?: models?.sumOf { it.entries.size })?.let {
                    pluralStringResource(Res.plurals.equalizer_profile_count, it, it)
                },
            onBack = goBack,
            onDismiss = onDismiss,
        )
        if (shownModel == null) {
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                enabled = models != null,
                placeholder = { Text(stringResource(Res.string.search)) },
                leadingIcon = { Icon(MetrolistIcons.Outlined.Search, contentDescription = null) },
                trailingIcon =
                    if (query.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { query = "" }, shapes = IconButtonDefaults.shapes()) {
                                Icon(MetrolistIcons.Outlined.Close, contentDescription = stringResource(Res.string.close))
                            }
                        }
                    },
                shape = CircleShape,
                colors =
                    TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(360.dp)) {
            when {
                models == null && failed -> {
                    AutoEqError(onRetry = { attempt++ })
                }

                results == null -> {
                    SkeletonRows()
                }

                shownModel != null -> {
                    DragScrollableColumn(Modifier.padding(top = 8.dp)) {
                        items(shownModel.entries, key = { it.path }) { entry ->
                            val variant =
                                entry.name
                                    .removePrefix(shownModel.name)
                                    .trim()
                                    .removeSurrounding("(", ")")
                            AutoEqRow(
                                title = entry.source,
                                supporting = listOfNotNull(variant.ifEmpty { null }, entry.rig).joinToString(" ·\u00a0").ifEmpty { null },
                                loading = loadingEntry == entry,
                                showChevron = false,
                            ) { add(entry) }
                        }
                    }
                }

                results.isEmpty() -> {
                    Text(
                        stringResource(Res.string.wizard_no_models),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    DragScrollableColumn {
                        items(results, key = { it.name }) { item ->
                            val single = item.entries.singleOrNull()
                            AutoEqRow(
                                title = item.name,
                                supporting = single?.describe() ?: stringResource(Res.string.wizard_multiple_variants),
                                loading = single != null && loadingEntry == single,
                                showChevron = single == null,
                            ) { if (single != null) add(single) else model = item }
                        }
                    }
                }
            }
        }
        if (failed && models != null) {
            Text(
                stringResource(Res.string.equalizer_autoeq_unavailable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
        }
    }
}

private fun AutoEqEntry.describe() = listOfNotNull(source, rig).joinToString(" ·\u00a0")

@Composable
private fun AutoEqRow(
    title: String,
    supporting: String?,
    loading: Boolean,
    showChevron: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent =
            when {
                loading -> {
                    { ContainedLoadingIndicator(Modifier.size(24.dp)) }
                }

                showChevron -> {
                    { Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null) }
                }

                else -> {
                    null
                }
            },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
    ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SkeletonRows() {
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        repeat(5) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.45f + (index % 3) * 0.15f).height(16.dp).background(color, MaterialTheme.shapes.extraSmall))
                Box(Modifier.fillMaxWidth(0.3f).height(12.dp).background(color, MaterialTheme.shapes.extraSmall))
            }
        }
    }
}

@Composable
private fun AutoEqError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(Res.string.equalizer_autoeq_unavailable),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilledTonalButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
    }
}

@Composable
private fun DialogHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (onBack == null) 24.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let {
            IconButton(onClick = it, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
            }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack == null) 0.dp else 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
            Icon(MetrolistIcons.Outlined.Close, contentDescription = stringResource(Res.string.close))
        }
    }
}

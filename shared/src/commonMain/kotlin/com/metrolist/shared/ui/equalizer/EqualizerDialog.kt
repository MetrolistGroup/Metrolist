/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.equalizer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.edit_in_metroeq
import com.metrolist.shared.generated.resources.eq_disabled
import com.metrolist.shared.generated.resources.eq_wizard
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.equalizer_invalid_profile
import com.metrolist.shared.generated.resources.equalizer_profile_count
import com.metrolist.shared.generated.resources.equalizer_stream_unavailable
import com.metrolist.shared.generated.resources.import_from_file
import com.metrolist.shared.generated.resources.import_profile
import com.metrolist.shared.generated.resources.metroeq
import com.metrolist.shared.generated.resources.metroeq_delete_profile_confirm
import com.metrolist.shared.generated.resources.metroeq_discard_changes
import com.metrolist.shared.generated.resources.metroeq_switch_profile_confirm
import com.metrolist.shared.generated.resources.metroeq_unsaved_changes
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.no_profiles
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.system_equalizer
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.BasicAlertDialog
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.toPolygonShape
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun EqualizerDialog(
    onDismiss: () -> Unit,
    rememberProfilePicker: (@Composable (onPicked: (fileName: String, content: String) -> Unit) -> () -> Unit)? = null,
    isAudioProcessingAvailable: Boolean = true,
    onOpenSystemEqualizer: (() -> Unit)? = null,
    metroEqContent: (@Composable (onDismiss: () -> Unit, searchAutoEq: Boolean) -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(true) }
    var openMetroEq by remember { mutableStateOf(false) }
    var searchAutoEq by remember { mutableStateOf(false) }
    var editorVisible by remember { mutableStateOf(false) }
    PopupVisibility(
        visible = visible,
        onHidden = { if (openMetroEq) editorVisible = true else onDismiss() },
    ) {
        EqualizerProfileDialog(
            onDismiss = { visible = false },
            rememberProfilePicker = rememberProfilePicker,
            isAudioProcessingAvailable = isAudioProcessingAvailable,
            onOpenSystemEqualizer = onOpenSystemEqualizer,
            onOpenMetroEq = { search ->
                searchAutoEq = search
                openMetroEq = true
                visible = false
            },
        )
    }
    if (editorVisible) {
        if (metroEqContent != null) {
            metroEqContent(onDismiss, searchAutoEq)
        } else {
            var editorDialogVisible by remember { mutableStateOf(true) }
            PopupVisibility(visible = editorDialogVisible, onHidden = onDismiss) {
                MetroEqDialog(
                    onDismiss = { editorDialogVisible = false },
                    rememberProfilePicker = rememberProfilePicker,
                    isAudioProcessingAvailable = isAudioProcessingAvailable,
                    initialAutoEqSearch = searchAutoEq,
                )
            }
        }
    }
}

@Composable
private fun EqualizerProfileDialog(
    onDismiss: () -> Unit,
    rememberProfilePicker: (@Composable (onPicked: (fileName: String, content: String) -> Unit) -> () -> Unit)?,
    isAudioProcessingAvailable: Boolean,
    onOpenSystemEqualizer: (() -> Unit)?,
    onOpenMetroEq: (searchAutoEq: Boolean) -> Unit,
) {
    val profiles = EqualizerProfiles.profiles
    val activeId = EqualizerProfiles.activeProfileId
    val enabled = EqualizerProfiles.isEnabled
    var importError by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deletingProfile by remember { mutableStateOf<EqualizerProfile?>(null) }
    var pendingProfileChange by remember { mutableStateOf<(() -> Unit)?>(null) }
    val pickFile =
        rememberProfilePicker?.invoke { name, content ->
            importError = runCatching { EqualizerProfiles.importText(name, content) }.isFailure
        }

    fun changeProfile(action: () -> Unit) {
        if (EqualizerProfiles.hasUnsavedChanges) pendingProfileChange = action else action()
    }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.heightIn(max = 600.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.equalizer), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            pluralStringResource(Res.plurals.equalizer_profile_count, profiles.size, profiles.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close))
                    }
                }
                if (!isAudioProcessingAvailable) {
                    Text(
                        stringResource(Res.string.equalizer_stream_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LazyColumn(Modifier.weight(1f, fill = false).selectableGroup()) {
                    if (profiles.isEmpty()) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Box(
                                    Modifier.size(120.dp).background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        MaterialShapes.Cookie12Sided.toPolygonShape(),
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        MetrolistIcons.Outlined.BarChart,
                                        null,
                                        Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                                Text(
                                    stringResource(Res.string.no_profiles),
                                    style = MaterialTheme.typography.titleLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        item {
                            EqualizerProfileRow(
                                name = stringResource(Res.string.eq_disabled),
                                selected = !enabled,
                                onSelect = { EqualizerProfiles.setEnabled(false) },
                            )
                        }
                        items(profiles, key = { it.id }) { profile ->
                            EqualizerProfileRow(
                                name = profile.name,
                                selected = enabled && activeId == profile.id,
                                onSelect = {
                                    if (activeId == profile.id) {
                                        EqualizerProfiles.setEnabled(true)
                                    } else {
                                        changeProfile { EqualizerProfiles.setActive(profile.id) }
                                    }
                                },
                                onEdit = {
                                    if (activeId == profile.id) {
                                        onOpenMetroEq(false)
                                    } else {
                                        changeProfile {
                                            EqualizerProfiles.setActive(profile.id)
                                            onOpenMetroEq(false)
                                        }
                                    }
                                },
                                onDelete = { deletingProfile = profile },
                            )
                        }
                    }
                }
                if (importError) {
                    Text(
                        stringResource(Res.string.equalizer_invalid_profile),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { onOpenMetroEq(false) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.metroeq))
                    }
                    onOpenSystemEqualizer?.let { open ->
                        FilledTonalButton(onClick = open, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.system_equalizer))
                        }
                    }
                    Box {
                        Button(onClick = { importing = true }, shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.import_profile))
                        }
                        DropdownMenu(expanded = importing, onDismissRequest = { importing = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.eq_wizard)) },
                                onClick = {
                                    importing = false
                                    changeProfile { onOpenMetroEq(true) }
                                },
                            )
                            pickFile?.let { pick ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.import_from_file)) },
                                    onClick = {
                                        importing = false
                                        changeProfile { pick() }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    PopupContent(pendingProfileChange) { action ->
        ConfirmEqualizerProfileChange(action = action, onDismiss = { pendingProfileChange = null })
    }
    PopupContent(deletingProfile) { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            text = { Text(stringResource(Res.string.metroeq_delete_profile_confirm, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        EqualizerProfiles.delete(profile.id)
                        deletingProfile = null
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfile = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun EqualizerProfileRow(
    name: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onEdit != null && onDelete != null) {
            Box {
                var expanded by remember { mutableStateOf(false) }
                IconButton(onClick = { expanded = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_options))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    Text(name, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.edit_in_metroeq)) },
                        leadingIcon = { Icon(MetrolistIcons.Outlined.Tune, null) },
                        onClick = {
                            expanded = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.delete)) },
                        leadingIcon = { Icon(MetrolistIcons.Outlined.Delete, null) },
                        onClick = {
                            expanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConfirmEqualizerProfileChange(
    action: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.metroeq_unsaved_changes)) },
        text = { Text(stringResource(Res.string.metroeq_switch_profile_confirm)) },
        confirmButton = {
            TextButton(
                onClick = {
                    EqualizerProfiles.activeProfile?.let { EqualizerProfiles.saveCurrent(it.name) }
                    onDismiss()
                    action()
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    action()
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.metroeq_discard_changes)) }
                TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

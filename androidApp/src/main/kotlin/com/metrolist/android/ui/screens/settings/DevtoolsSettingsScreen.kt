@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.metrolist.android.ui.component.Material3SettingsGroup
import com.metrolist.android.ui.component.Material3SettingsItem
import com.metrolist.android.utils.CrashHandler
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PlaybackClientManifest
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.cd_devtools
import com.metrolist.shared.generated.resources.crash_logs
import com.metrolist.shared.generated.resources.devtools_disable_message
import com.metrolist.shared.generated.resources.devtools_disable_title
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_description
import com.metrolist.shared.generated.resources.devtools_playback_benchmark_title
import com.metrolist.shared.generated.resources.devtools_playback_client_automatic
import com.metrolist.shared.generated.resources.devtools_playback_client_automatic_description
import com.metrolist.shared.generated.resources.devtools_playback_client_legend
import com.metrolist.shared.generated.resources.devtools_playback_client_legend_access
import com.metrolist.shared.generated.resources.devtools_playback_client_legend_content
import com.metrolist.shared.generated.resources.devtools_playback_client_sabr_first
import com.metrolist.shared.generated.resources.devtools_playback_client_sabr_first_description
import com.metrolist.shared.generated.resources.devtools_playback_direct_clients
import com.metrolist.shared.generated.resources.devtools_playback_direct_clients_description
import com.metrolist.shared.generated.resources.devtools_playback_sabr_clients
import com.metrolist.shared.generated.resources.devtools_playback_sabr_clients_description
import com.metrolist.shared.generated.resources.devtools_switch_description
import com.metrolist.shared.generated.resources.devtools_title
import com.metrolist.shared.generated.resources.disable
import com.metrolist.shared.generated.resources.settings_crash_reports_summary
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.CapabilityStatusKey
import com.metrolist.shared.ui.settings.ClientAccessKey
import com.metrolist.shared.ui.settings.ClientAccessLabel
import com.metrolist.shared.ui.settings.ClientCapabilityChips
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DevtoolsSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPlaybackBenchmark: () -> Unit,
) {
    val crashContext = LocalContext.current
    var showDisableConfirmation by remember { mutableStateOf(false) }
    val storedClientId = Settings.shared.devtoolsPlaybackClient
    val initialClientId =
        storedClientId.takeIf {
            PlaybackClientCatalog.isDevtoolsSelectionAllowed(it)
        } ?: PlaybackClientCatalog.AUTOMATIC_ID
    var selectedClientId by remember { mutableStateOf(initialClientId) }
    LaunchedEffect(initialClientId, storedClientId) {
        if (initialClientId != storedClientId) {
            Settings.shared.devtoolsPlaybackClient = initialClientId
        }
    }

    val automaticOption =
        ClientRow(
            id = PlaybackClientCatalog.AUTOMATIC_ID,
            title = stringResource(Res.string.devtools_playback_client_automatic),
            description = stringResource(Res.string.devtools_playback_client_automatic_description),
        )
    val sabrFirstOption =
        ClientRow(
            id = PlaybackClientCatalog.SABR_FIRST_ID,
            title = stringResource(Res.string.devtools_playback_client_sabr_first),
            description = stringResource(Res.string.devtools_playback_client_sabr_first_description),
            isSabr = true,
        )
    val clientOptions =
        remember {
            PlaybackClientCatalog.devtoolsOverrideOptions.map { option ->
                ClientRow(
                    id = option.id,
                    title = option.displayName,
                    description = "${option.client.clientName} ·\u00a0${option.client.clientVersion}",
                    manifest = option.manifest,
                    isSabr = option.client.useSabr,
                )
            }
        }
    val directOptions = listOf(automaticOption) + clientOptions.filterNot(ClientRow::isSabr)
    val sabrOptions = listOf(sabrFirstOption) + clientOptions.filter(ClientRow::isSabr)

    SettingsSubScreen(
        title = stringResource(Res.string.devtools_title),
        onNavigateBack = onNavigateBack,
    ) {
        TextButton(
            onClick = {
                crashContext.startActivity(CrashHandler.logsIntent(crashContext))
            },
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(Res.string.crash_logs))
        }
        Text(
            stringResource(Res.string.settings_crash_reports_summary),
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DevtoolsMasterCard(
            onTurnOff = { showDisableConfirmation = true },
        )
        PlaybackEventLogSection()
        PlaybackBenchmarkEntry(onOpen = onNavigateToPlaybackBenchmark)
        ClientCapabilityLegend()
        ClientRadioGroup(
            title = stringResource(Res.string.devtools_playback_direct_clients),
            description = stringResource(Res.string.devtools_playback_direct_clients_description),
            options = directOptions,
            selectedClientId = selectedClientId,
            onSelect = { clientId ->
                selectedClientId = clientId
                Settings.shared.devtoolsPlaybackClient = clientId
            },
        )
        ClientRadioGroup(
            title = stringResource(Res.string.devtools_playback_sabr_clients),
            description = stringResource(Res.string.devtools_playback_sabr_clients_description),
            options = sabrOptions,
            selectedClientId = selectedClientId,
            onSelect = { clientId ->
                selectedClientId = clientId
                Settings.shared.devtoolsPlaybackClient = clientId
            },
        )
        PopupVisibility(visible = showDisableConfirmation) {
            AlertDialog(
                onDismissRequest = { showDisableConfirmation = false },
                title = { Text(stringResource(Res.string.devtools_disable_title)) },
                text = { Text(stringResource(Res.string.devtools_disable_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDisableConfirmation = false
                            Settings.shared.setDevtoolsEnabled(false)
                            onNavigateBack()
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.disable)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDisableConfirmation = false },
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(stringResource(Res.string.cancel)) }
                },
            )
        }
    }
}

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun DevtoolsMasterCard(onTurnOff: () -> Unit) {
    Material3SettingsGroup(
        horizontalPadding = 16.dp,
        items =
            listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(Res.string.cd_devtools)) },
                    description = { Text(stringResource(Res.string.devtools_switch_description)) },
                    icon = MetrolistIcons.Default.Settings,
                    checked = true,
                    trailingContent = { ExpressiveSwitch(checked = true, onCheckedChange = null) },
                    onClick = onTurnOff,
                ),
            ),
    )
}

@Composable
private fun PlaybackBenchmarkEntry(onOpen: () -> Unit) {
    Material3SettingsGroup(
        horizontalPadding = 16.dp,
        items =
            listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(Res.string.devtools_playback_benchmark_title)) },
                    description = { Text(stringResource(Res.string.devtools_playback_benchmark_description)) },
                    icon = MetrolistIcons.Default.Speed,
                    trailingContent = { Icon(MetrolistIcons.Outlined.ChevronRight, contentDescription = null) },
                    onClick = onOpen,
                ),
            ),
    )
}

@Composable
internal fun ClientCapabilityLegend() {
    SettingsSubScreenGroup(title = stringResource(Res.string.devtools_playback_client_legend)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = CardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendEntry(title = stringResource(Res.string.devtools_playback_client_legend_content)) {
                    CapabilityStatusKey()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LegendEntry(title = stringResource(Res.string.devtools_playback_client_legend_access)) {
                    ClientAccessKey()
                }
            }
        }
    }
}

@Composable
private fun LegendEntry(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
private fun ClientRadioGroup(
    title: String,
    description: String,
    options: List<ClientRow>,
    selectedClientId: String,
    onSelect: (String) -> Unit,
) {
    SettingsSubScreenGroup(title = title, description = description) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, option ->
                ClientOptionRow(
                    option = option,
                    selected = selectedClientId == option.id,
                    isFirst = index == 0,
                    isLast = index == options.lastIndex,
                    onClick = { onSelect(option.id) },
                )
            }
        }
    }
}

@Composable
private fun ClientOptionRow(
    option: ClientRow,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val outer = 20.dp
    val inner = 5.dp
    val shape =
        RoundedCornerShape(
            topStart = if (isFirst) outer else inner,
            topEnd = if (isFirst) outer else inner,
            bottomStart = if (isLast) outer else inner,
            bottomEnd = if (isLast) outer else inner,
        )
    val container by animateColorAsState(
        targetValue =
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "clientRowContainer",
    )
    val content = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val muted =
        if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer.copy(
                alpha = 0.8f,
            )
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(container)
                .selectable(selected = selected, enabled = option.enabled, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(option.title, style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.weight(1f))
                option.manifest?.let { ClientAccessLabel(it.authentication, color = muted) }
            }
            Text(option.description, style = MaterialTheme.typography.labelMedium, color = muted)
            option.manifest?.let { ClientCapabilityChips(it) }
        }
        RadioButton(selected = selected, enabled = option.enabled, onClick = null)
    }
}

private data class ClientRow(
    val id: String,
    val title: String,
    val description: String,
    val manifest: PlaybackClientManifest? = null,
    val isSabr: Boolean = false,
    val enabled: Boolean = true,
)

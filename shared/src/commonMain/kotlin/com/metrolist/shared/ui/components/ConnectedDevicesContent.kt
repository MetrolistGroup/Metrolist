@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.deviceconnect.DcSyncState
import com.metrolist.shared.deviceconnect.DeviceConnectManager
import com.metrolist.shared.deviceconnect.DeviceConnectPlaybackStatus
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.available_devices
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.connected_devices
import com.metrolist.shared.generated.resources.device_connect_default_device
import com.metrolist.shared.generated.resources.device_connect_default_device_description
import com.metrolist.shared.generated.resources.device_connect_enable
import com.metrolist.shared.generated.resources.device_connect_grant_permission
import com.metrolist.shared.generated.resources.device_connect_pair_hint
import com.metrolist.shared.generated.resources.disconnect_cast
import com.metrolist.shared.generated.resources.listen_together_connected
import com.metrolist.shared.generated.resources.no_compatible_devices
import com.metrolist.shared.generated.resources.no_device_connected
import com.metrolist.shared.generated.resources.no_device_found
import com.metrolist.shared.generated.resources.no_other_devices_nearby
import com.metrolist.shared.generated.resources.none
import com.metrolist.shared.generated.resources.scrobble_connecting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.toPolygonShape
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectedDevicesContent(
    devices: List<CastDevice>,
    castState: CastState,
    onDeviceClick: (CastDevice) -> Unit,
    onDisconnectCast: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    deviceState: DeviceConnectManager.State? = null,
    syncState: DcSyncState = DcSyncState(),
    onEnableDeviceConnect: (() -> Unit)? = null,
    onTransferTo: (String) -> Unit = {},
    permissionRequired: Boolean = false,
    onOpenIntegrations: (() -> Unit)? = null,
    closeButtonModifier: Modifier = Modifier,
) {
    val activeCast = castState.device.takeIf { Settings.shared.castEnabled && castState.status != CastConnectionStatus.DISCONNECTED }
    val availableCast = devices.filter { Settings.shared.castEnabled && it.id != activeCast?.id }
    val peers = deviceState?.peers.orEmpty().filter { it.state == DeviceConnectManager.PeerState.CONNECTED }
    val activePeer = peers.firstOrNull { it.id == syncState.targetId && it.id != deviceState?.localDeviceId }
    val availablePeers = peers.filterNot { it.id == activePeer?.id }
    val empty = activeCast == null && activePeer == null && availableCast.isEmpty() && availablePeers.isEmpty()
    val connectingLabel = stringResource(Res.string.scrobble_connecting)
    Column(
        modifier.fillMaxWidth().verticalScrollWithDrag(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    Res.string.connected_devices,
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier =
                    Modifier.weight(1f).semantics {
                        heading()
                    },
            )
            FilledTonalIconButton(onClick = onClose, modifier = closeButtonModifier, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Outlined.Close, stringResource(Res.string.close))
            }
        }
        if (empty) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceEmblem(large = true)
                Text(
                    stringResource(Res.string.no_device_found),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(Res.string.no_compatible_devices),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            DeviceSectionTitle(stringResource(Res.string.listen_together_connected))
            if (activeCast == null && activePeer == null) {
                EmptyDeviceSection(stringResource(Res.string.no_device_connected))
            } else {
                val count = (if (activeCast != null) 1 else 0) + (if (activePeer != null) 1 else 0)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    activeCast?.let { device ->
                        ConnectedDeviceRow(
                            name = device.name,
                            subtitle =
                                if (castState.status ==
                                    CastConnectionStatus.CONNECTING
                                ) {
                                    connectingLabel
                                } else {
                                    protocolName(device.protocol)
                                },
                            icon = MetrolistIcons.Outlined.Cast,
                            index = 0,
                            count = count,
                            onClick = onDisconnectCast,
                            connected = true,
                            connecting = castState.status == CastConnectionStatus.CONNECTING,
                        )
                    }
                    activePeer?.let { peer ->
                        ConnectedDeviceRow(
                            name = peer.name,
                            subtitle = stringResource(Res.string.app_name),
                            icon = MetrolistIcons.Outlined.Devices,
                            index = count - 1,
                            count = count,
                            connected = true,
                            onClick = { deviceState?.localDeviceId?.let(onTransferTo) },
                        )
                    }
                }
            }
            DeviceSectionTitle(stringResource(Res.string.available_devices))
            if (availableCast.isEmpty() && availablePeers.isEmpty()) {
                Text(
                    stringResource(Res.string.no_other_devices_nearby),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    val count = availableCast.size + availablePeers.size
                    availableCast.forEachIndexed { index, device ->
                        ConnectedDeviceRow(
                            device.name,
                            protocolName(device.protocol),
                            MetrolistIcons.Outlined.Cast,
                            index,
                            count,
                            enabled = castState.status != CastConnectionStatus.CONNECTING,
                            onClick = { onDeviceClick(device) },
                        )
                    }
                    availablePeers.forEachIndexed { index, peer ->
                        ConnectedDeviceRow(
                            peer.name,
                            stringResource(Res.string.app_name),
                            MetrolistIcons.Outlined.Devices,
                            index + availableCast.size,
                            count,
                            enabled = !castState.isConnected && castState.status != CastConnectionStatus.CONNECTING,
                            onClick = { onTransferTo(peer.id) },
                        )
                    }
                }
            }
        }
        castState.error
            ?.takeIf {
                Settings.shared.castEnabled
            }?.let { Text(stringResource(it.stringResource), color = MaterialTheme.colorScheme.error) }
        if (syncState.error != null || syncState.controllerIds.isNotEmpty()) DeviceConnectPlaybackStatus(syncState)
        if (deviceState != null) {
            val pairedDevices = deviceState.pairedDevices
            if (pairedDevices.isNotEmpty()) {
                var defaultDeviceId by Settings.shared::deviceConnectDefaultDeviceId
                var showDefaults by remember { mutableStateOf(false) }
                DeviceSectionTitle(stringResource(Res.string.device_connect_default_device))
                Text(
                    stringResource(Res.string.device_connect_default_device_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    TextButton(onClick = { showDefaults = true }, shapes = ButtonDefaults.shapes()) {
                        Text(pairedDevices.firstOrNull { it.deviceId == defaultDeviceId }?.deviceName ?: stringResource(Res.string.none))
                    }
                    DropdownMenu(expanded = showDefaults, onDismissRequest = { showDefaults = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.none)) },
                            onClick = {
                                defaultDeviceId = ""
                                showDefaults = false
                            },
                        )
                        pairedDevices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device.deviceName) },
                                onClick = {
                                    defaultDeviceId = device.deviceId
                                    showDefaults = false
                                },
                            )
                        }
                    }
                }
            }
            if (!deviceState.enabled && onEnableDeviceConnect != null) {
                Button(onClick = onEnableDeviceConnect, modifier = Modifier.fillMaxWidth(), shapes = ButtonDefaults.shapes()) {
                    Text(
                        stringResource(
                            if (permissionRequired) Res.string.device_connect_grant_permission else Res.string.device_connect_enable,
                        ),
                    )
                }
            }
            Text(
                stringResource(Res.string.device_connect_pair_hint),
                style = MaterialTheme.typography.bodySmall,
                color = if (onOpenIntegrations != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(onOpenIntegrations?.let { Modifier.clickable(role = Role.Button, onClick = it) } ?: Modifier)
                        .padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DeviceSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier.semantics {
                heading()
            },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeviceEmblem(large: Boolean = false) {
    Box(
        Modifier
            .size(
                if (large) 136.dp else 56.dp,
            ).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie7Sided.toPolygonShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            MetrolistIcons.Outlined.Devices,
            null,
            Modifier.size(if (large) 40.dp else 20.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun EmptyDeviceSection(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceEmblem()
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun ConnectedDeviceRow(
    name: String,
    subtitle: String,
    icon: ImageVector,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    connected: Boolean = false,
    connecting: Boolean = false,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape =
            RoundedCornerShape(
                topStart = if (index == 0) 20.dp else 5.dp,
                topEnd = if (index == 0) 20.dp else 5.dp,
                bottomStart = if (index == count - 1) 20.dp else 5.dp,
                bottomEnd = if (index == count - 1) 20.dp else 5.dp,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connecting) LoadingIndicator(Modifier.size(20.dp))
            if (connected) {
                IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Outlined.Close, stringResource(Res.string.disconnect_cast))
                }
            }
        }
    }
}

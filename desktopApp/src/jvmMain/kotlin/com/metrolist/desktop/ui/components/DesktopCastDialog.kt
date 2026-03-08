package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.ui.components.CastDeviceDialog
import kotlinx.coroutines.launch

@Composable
fun DesktopCastDialog(onDismiss: () -> Unit) {
    val devices by PlayerState.castManager.devices.collectAsState()
    val state by PlayerState.castManager.state.collectAsState()
    val manager = DesktopDeviceConnect.manager
    val deviceState by manager.state.collectAsState()
    val syncState by manager.remote.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { manager.refreshPairingState() }
    CastDeviceDialog(
        deviceState = deviceState,
        syncState = syncState,
        onEnableDeviceConnect = { scope.launch { manager.enable(Unit) } },
        onTransferTo = manager.remote::transferTo,
        devices = devices,
        state = state,
        onDismiss = onDismiss,
        onStartDiscovery = PlayerState::startCastDiscovery,
        onStopDiscovery = PlayerState::stopCastDiscovery,
        onDeviceClick = PlayerState::castTo,
        onDisconnect = PlayerState::disconnectCast,
    )
}

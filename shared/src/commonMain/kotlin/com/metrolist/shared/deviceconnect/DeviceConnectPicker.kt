@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.deviceconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.approve
import com.metrolist.shared.generated.resources.device_connect_account_mismatch
import com.metrolist.shared.generated.resources.device_connect_account_unresolved
import com.metrolist.shared.generated.resources.device_connect_available
import com.metrolist.shared.generated.resources.device_connect_discovered
import com.metrolist.shared.generated.resources.device_connect_pair_code_accessibility
import com.metrolist.shared.generated.resources.device_connect_pair_code_message
import com.metrolist.shared.generated.resources.device_connect_pair_same_account_message
import com.metrolist.shared.generated.resources.device_connect_pair_title
import com.metrolist.shared.generated.resources.device_connect_paired
import com.metrolist.shared.generated.resources.device_connect_pairing
import com.metrolist.shared.generated.resources.device_connect_waiting_approval
import com.metrolist.shared.generated.resources.device_connect_waiting_peer
import com.metrolist.shared.generated.resources.error_title
import com.metrolist.shared.generated.resources.listen_together_connected
import com.metrolist.shared.generated.resources.listen_together_disconnected
import com.metrolist.shared.generated.resources.reject
import com.metrolist.shared.generated.resources.scrobble_connecting
import com.metrolist.shared.ui.components.AlertDialog
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceConnectPairingHost(
    state: DeviceConnectManager.State,
    onApprovePairing: (String) -> Unit,
    onRejectPairing: (String) -> Unit,
) {
    state.pairingRequests.firstOrNull { it.requiresApproval }?.let { request ->
        DeviceConnectPairingDialog(
            request = request,
            onApprove = { onApprovePairing(request.requestId) },
            onReject = { onRejectPairing(request.requestId) },
        )
    }
}

@Composable
fun deviceConnectPeerStatus(
    peer: DeviceConnectManager.PeerDevice,
    isPaired: Boolean,
    pairingRequest: DeviceConnectManager.PairingRequest?,
    showConnected: Boolean,
    showAvailable: Boolean,
): String =
    stringResource(
        when {
            pairingRequest?.requiresApproval == true -> {
                Res.string.device_connect_waiting_approval
            }

            pairingRequest != null -> {
                Res.string.device_connect_waiting_peer
            }

            peer.state == DeviceConnectManager.PeerState.CONNECTED && showConnected -> {
                Res.string.listen_together_connected
            }

            peer.state == DeviceConnectManager.PeerState.CONNECTING -> {
                Res.string.scrobble_connecting
            }

            peer.state == DeviceConnectManager.PeerState.HANDSHAKING -> {
                Res.string.device_connect_pairing
            }

            peer.state == DeviceConnectManager.PeerState.ACCOUNT_MISMATCH -> {
                Res.string.device_connect_account_mismatch
            }

            peer.state == DeviceConnectManager.PeerState.ACCOUNT_UNRESOLVED -> {
                Res.string.device_connect_account_unresolved
            }

            peer.state == DeviceConnectManager.PeerState.ERROR -> {
                Res.string.error_title
            }

            peer.state == DeviceConnectManager.PeerState.DISCONNECTED && !showAvailable -> {
                Res.string.listen_together_disconnected
            }

            showAvailable -> {
                Res.string.device_connect_available
            }

            peer.state == DeviceConnectManager.PeerState.CONNECTED || isPaired -> {
                Res.string.device_connect_paired
            }

            peer.state == DeviceConnectManager.PeerState.DISCONNECTED -> {
                Res.string.listen_together_disconnected
            }

            else -> {
                Res.string.device_connect_discovered
            }
        },
    )

@Composable
fun DeviceConnectPairingDialog(
    request: DeviceConnectManager.PairingRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val deviceName = request.deviceName.ifBlank { request.deviceId }
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(Res.string.device_connect_pair_title, deviceName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (request.sas.isBlank()) {
                    Text(stringResource(Res.string.device_connect_pair_same_account_message, deviceName))
                } else {
                    Text(stringResource(Res.string.device_connect_pair_code_message, deviceName))
                    val spokenCode = request.sas.toCharArray().joinToString(" ")
                    val codeDescription = stringResource(Res.string.device_connect_pair_code_accessibility, spokenCode)
                    Text(
                        request.sas,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = codeDescription },
                        style = MaterialTheme.typography.displaySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.approve))
            }
        },
        dismissButton = {
            TextButton(onClick = onReject, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.reject))
            }
        },
    )
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.metrolist.android.carlights.CarLightDevice
import com.metrolist.android.carlights.CarLights
import com.metrolist.android.carlights.CarLightsController
import com.metrolist.android.carlights.CarLightsLink
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.car_lights_auto_connect
import com.metrolist.shared.generated.resources.car_lights_auto_connect_description
import com.metrolist.shared.generated.resources.car_lights_auto_connect_description_legacy
import com.metrolist.shared.generated.resources.car_lights_bluetooth_off
import com.metrolist.shared.generated.resources.car_lights_device_details
import com.metrolist.shared.generated.resources.car_lights_device_section
import com.metrolist.shared.generated.resources.car_lights_disconnect
import com.metrolist.shared.generated.resources.car_lights_enable
import com.metrolist.shared.generated.resources.car_lights_enable_description
import com.metrolist.shared.generated.resources.car_lights_forget
import com.metrolist.shared.generated.resources.car_lights_forget_description
import com.metrolist.shared.generated.resources.car_lights_grant_permission
import com.metrolist.shared.generated.resources.car_lights_location_off
import com.metrolist.shared.generated.resources.car_lights_no_devices
import com.metrolist.shared.generated.resources.car_lights_permission_needed
import com.metrolist.shared.generated.resources.car_lights_role_off
import com.metrolist.shared.generated.resources.car_lights_role_primary
import com.metrolist.shared.generated.resources.car_lights_role_primary_description
import com.metrolist.shared.generated.resources.car_lights_role_secondary
import com.metrolist.shared.generated.resources.car_lights_role_secondary_description
import com.metrolist.shared.generated.resources.car_lights_role_tertiary
import com.metrolist.shared.generated.resources.car_lights_role_tertiary_description
import com.metrolist.shared.generated.resources.car_lights_roles_description
import com.metrolist.shared.generated.resources.car_lights_roles_section
import com.metrolist.shared.generated.resources.car_lights_search
import com.metrolist.shared.generated.resources.car_lights_search_description
import com.metrolist.shared.generated.resources.car_lights_searching
import com.metrolist.shared.generated.resources.car_lights_send_now
import com.metrolist.shared.generated.resources.car_lights_send_now_description
import com.metrolist.shared.generated.resources.car_lights_status
import com.metrolist.shared.generated.resources.car_lights_status_connected
import com.metrolist.shared.generated.resources.car_lights_status_connecting
import com.metrolist.shared.generated.resources.car_lights_status_off
import com.metrolist.shared.generated.resources.car_lights_status_waiting
import com.metrolist.shared.generated.resources.car_lights_title
import com.metrolist.shared.generated.resources.car_lights_turn_on_bluetooth
import com.metrolist.shared.generated.resources.car_lights_turn_on_location
import com.metrolist.shared.generated.resources.car_lights_vivid
import com.metrolist.shared.generated.resources.car_lights_vivid_description
import com.metrolist.shared.generated.resources.car_lights_zone_all
import com.metrolist.shared.generated.resources.car_lights_zone_central
import com.metrolist.shared.generated.resources.car_lights_zone_doors
import com.metrolist.shared.generated.resources.car_lights_zone_rear
import com.metrolist.shared.generated.resources.car_lights_zones_description
import com.metrolist.shared.generated.resources.car_lights_zones_section
import com.metrolist.shared.settings.CarLightRole
import com.metrolist.shared.settings.Settings
import org.jetbrains.compose.resources.stringResource
import android.provider.Settings as AndroidSettings

// >> functions
// appearance > car lights page with the master toggle, device search and connect, and zone color roles
// @params: onNavigateBack () -> Unit -- called by the back button.
@Composable
fun CarLightsScreen(onNavigateBack: () -> Unit) {
    val settings = Settings.shared
    val controller = CarLights.controller
    val link by controller.link.collectAsState()
    val discovering by controller.discovering.collectAsState()
    val discovered by controller.discovered.collectAsState()
    val bluetoothOn by controller.bluetoothOn.collectAsState()
    val locationOn by controller.locationOn.collectAsState()
    val hasZones by controller.hasZones.collectAsState()
    val connectedName by controller.connectedName.collectAsState()
    var permissionGranted by remember { mutableStateOf(controller.hasPermissions()) }
    var hasSearched by remember { mutableStateOf(false) }

    // the empty result only shows after a scan really started
    LaunchedEffect(discovering) { if (discovering) hasSearched = true }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionGranted = controller.hasPermissions()
            controller.refresh()
        }
    val enableBluetoothLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            controller.refresh()
        }

    fun requestPermissions() {
        permissionLauncher.launch(CarLightsController.requiredPermissions().toTypedArray())
    }

    val enabled = settings.carLightsEnabled
    val savedAddress = settings.carLightsDeviceAddress
    val savedName = settings.carLightsDeviceName.ifEmpty { savedAddress }
    val deviceLabel = connectedName ?: savedName

    val statusText =
        when (link) {
            CarLightsLink.CONNECTED -> stringResource(Res.string.car_lights_status_connected, deviceLabel)
            CarLightsLink.CONNECTING -> stringResource(Res.string.car_lights_status_connecting, deviceLabel)
            CarLightsLink.WAITING -> stringResource(Res.string.car_lights_status_waiting, deviceLabel)
            CarLightsLink.IDLE -> stringResource(Res.string.car_lights_status_off)
        }

    SettingsSubScreen(
        title = stringResource(Res.string.car_lights_title),
        onNavigateBack = onNavigateBack,
    ) {
        val masterItems = mutableListOf<SettingsSubScreenItem>()
        masterItems +=
            switchSettingsItem(
                title = stringResource(Res.string.car_lights_enable),
                description = stringResource(Res.string.car_lights_enable_description),
                checked = enabled,
                onCheckedChange = { value ->
                    settings.carLightsEnabled = value
                    if (value && !controller.hasPermissions()) requestPermissions()
                },
            )
        if (enabled) {
            masterItems +=
                SettingsSubScreenItem(
                    title = stringResource(Res.string.car_lights_status),
                    description = statusText,
                )
            if (!permissionGranted) {
                masterItems +=
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.car_lights_grant_permission),
                        description = stringResource(Res.string.car_lights_permission_needed),
                        onClick = ::requestPermissions,
                    )
            } else if (!bluetoothOn) {
                masterItems +=
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.car_lights_turn_on_bluetooth),
                        description = stringResource(Res.string.car_lights_bluetooth_off),
                        onClick = { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    )
            } else if (!locationOn) {
                masterItems +=
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.car_lights_turn_on_location),
                        description = stringResource(Res.string.car_lights_location_off),
                        onClick = { enableBluetoothLauncher.launch(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                    )
            }
        }
        SettingsSubScreenGroup(items = masterItems)

        val deviceItems = mutableListOf<SettingsSubScreenItem>()
        deviceItems +=
            switchSettingsItem(
                title = stringResource(Res.string.car_lights_auto_connect),
                description =
                    stringResource(
                        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R) {
                            Res.string.car_lights_auto_connect_description_legacy
                        } else {
                            Res.string.car_lights_auto_connect_description
                        },
                    ),
                checked = settings.carLightsAutoConnect,
                onCheckedChange = { settings.carLightsAutoConnect = it },
                enabled = enabled,
            )
        if (savedAddress.isNotEmpty()) {
            if (link == CarLightsLink.CONNECTED) {
                deviceItems +=
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.car_lights_disconnect),
                        description = deviceLabel,
                        enabled = enabled,
                        onClick = controller::disconnect,
                    )
            }
            deviceItems +=
                SettingsSubScreenItem(
                    title = stringResource(Res.string.car_lights_forget),
                    description = stringResource(Res.string.car_lights_forget_description, savedName),
                    enabled = enabled,
                    onClick = {
                        controller.forget {
                            settings.carLightsDeviceAddress = ""
                            settings.carLightsDeviceName = ""
                        }
                    },
                )
        }
        deviceItems +=
            SettingsSubScreenItem(
                title = stringResource(if (discovering) Res.string.car_lights_searching else Res.string.car_lights_search),
                description = stringResource(Res.string.car_lights_search_description),
                enabled = enabled && !discovering,
                onClick = {
                    if (!controller.hasPermissions()) {
                        requestPermissions()
                    } else {
                        controller.startDiscovery()
                    }
                },
            )
        discovered.forEach { device ->
            deviceItems += deviceItem(device, device.address == savedAddress && link == CarLightsLink.CONNECTED, enabled, controller)
        }
        if (hasSearched && !discovering && discovered.isEmpty()) {
            deviceItems += SettingsSubScreenItem(title = stringResource(Res.string.car_lights_no_devices))
        }
        SettingsSubScreenGroup(
            title = stringResource(Res.string.car_lights_device_section),
            items = deviceItems,
        )

        val zoneOptions = listOf(CarLightRole.PRIMARY, CarLightRole.SECONDARY, CarLightRole.TERTIARY, CarLightRole.OFF)
        val zoneItems = mutableListOf<SettingsSubScreenItem>()
        if (hasZones) {
            zoneItems +=
                listChooserSettingsItem(
                    title = stringResource(Res.string.car_lights_zone_central),
                    options = zoneOptions,
                    selectedOption = settings.carLightsCentralRole,
                    optionToString = { roleLabel(it) },
                    enabled = enabled,
                    onOptionSelected = { settings.carLightsCentralRole = it },
                )
            zoneItems +=
                listChooserSettingsItem(
                    title = stringResource(Res.string.car_lights_zone_doors),
                    options = zoneOptions,
                    selectedOption = settings.carLightsDoorsRole,
                    optionToString = { roleLabel(it) },
                    enabled = enabled,
                    onOptionSelected = { settings.carLightsDoorsRole = it },
                )
            zoneItems +=
                listChooserSettingsItem(
                    title = stringResource(Res.string.car_lights_zone_rear),
                    options = zoneOptions,
                    selectedOption = settings.carLightsRearRole,
                    optionToString = { roleLabel(it) },
                    enabled = enabled,
                    onOptionSelected = { settings.carLightsRearRole = it },
                )
        } else {
            // plain lights have one color, it follows the central role
            zoneItems +=
                listChooserSettingsItem(
                    title = stringResource(Res.string.car_lights_zone_all),
                    options = zoneOptions,
                    selectedOption = settings.carLightsCentralRole,
                    optionToString = { roleLabel(it) },
                    enabled = enabled,
                    onOptionSelected = { settings.carLightsCentralRole = it },
                )
        }
        zoneItems +=
            switchSettingsItem(
                title = stringResource(Res.string.car_lights_vivid),
                description = stringResource(Res.string.car_lights_vivid_description),
                checked = settings.carLightsVivid,
                onCheckedChange = { settings.carLightsVivid = it },
                enabled = enabled,
            )
        SettingsSubScreenGroup(
            title = stringResource(Res.string.car_lights_zones_section),
            description = stringResource(Res.string.car_lights_zones_description),
            items = zoneItems,
        )

        val scheme = MaterialTheme.colorScheme
        SettingsSubScreenGroup(
            title = stringResource(Res.string.car_lights_roles_section),
            description = stringResource(Res.string.car_lights_roles_description),
            items =
                listOf(
                    swatchItem(
                        stringResource(Res.string.car_lights_role_primary),
                        stringResource(Res.string.car_lights_role_primary_description),
                        scheme.primary,
                    ),
                    swatchItem(
                        stringResource(Res.string.car_lights_role_secondary),
                        stringResource(Res.string.car_lights_role_secondary_description),
                        scheme.secondary,
                    ),
                    swatchItem(
                        stringResource(Res.string.car_lights_role_tertiary),
                        stringResource(Res.string.car_lights_role_tertiary_description),
                        scheme.tertiary,
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.car_lights_send_now),
                        description = stringResource(Res.string.car_lights_send_now_description),
                        enabled = enabled && link == CarLightsLink.CONNECTED,
                        onClick = CarLights::resend,
                    ),
                ),
        )
    }
}

// display name of a color role
// @params: role CarLightRole -- role to name.
@Composable
private fun roleLabel(role: CarLightRole): String =
    stringResource(
        when (role) {
            CarLightRole.PRIMARY -> Res.string.car_lights_role_primary
            CarLightRole.SECONDARY -> Res.string.car_lights_role_secondary
            CarLightRole.TERTIARY -> Res.string.car_lights_role_tertiary
            CarLightRole.OFF -> Res.string.car_lights_role_off
        },
    )

// settings row for one scan result
// @params: device CarLightDevice -- scan result.
// @params: isConnected Boolean -- true if this is the connected light.
// @params: enabled Boolean -- master toggle state.
// @params: controller CarLightsController -- used to connect on tap.
@Composable
private fun deviceItem(
    device: CarLightDevice,
    isConnected: Boolean,
    enabled: Boolean,
    controller: CarLightsController,
) = SettingsSubScreenItem(
    title = device.name.ifEmpty { device.address },
    description = stringResource(Res.string.car_lights_device_details, device.address, device.rssi),
    enabled = enabled && !isConnected,
    onClick = { controller.connect(device) },
)

// settings row with a color dot, used to show the live theme colors
// @params: title String -- role name.
// @params: description String -- what the role colors in the player.
// @params: color Color -- current theme color.
private fun swatchItem(
    title: String,
    description: String,
    color: Color,
) = SettingsSubScreenItem(
    title = title,
    description = description,
    trailingContent = {
        Box(Modifier.size(28.dp).clip(CircleShape).background(color))
    },
)

// >> end << //

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
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
import com.metrolist.shared.generated.resources.car_lights_role_secondary
import com.metrolist.shared.generated.resources.car_lights_role_tertiary
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
import com.metrolist.shared.generated.resources.car_lights_zones_section
import com.metrolist.shared.generated.resources.shortcut_open_settings
import com.metrolist.shared.settings.CarLightRole
import com.metrolist.shared.settings.Settings
import org.jetbrains.compose.resources.stringResource
import android.provider.Settings as AndroidSettings

@Composable
fun CarLightsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
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
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    LaunchedEffect(discovering) { if (discovering) hasSearched = true }
    LifecycleResumeEffect(controller) {
        permissionGranted = controller.hasPermissions()
        if (permissionGranted) permissionBlocked = false
        controller.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permissionGranted = controller.hasPermissions()
            permissionBlocked =
                !permissionGranted &&
                grants.any { (permission, granted) ->
                    !granted && activity?.shouldShowRequestPermissionRationale(permission) == false
                }
            controller.refresh()
        }
    val enableBluetoothLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            controller.refresh()
        }

    fun requestPermissions() {
        if (permissionBlocked) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            permissionLauncher.launch(CarLightsController.requiredPermissions().toTypedArray())
        }
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
                        title =
                            stringResource(
                                if (permissionBlocked) Res.string.shortcut_open_settings else Res.string.car_lights_grant_permission,
                            ),
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
        if (!enabled) return@SettingsSubScreen

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
            )
        if (savedAddress.isNotEmpty()) {
            if (link == CarLightsLink.CONNECTED) {
                deviceItems +=
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.car_lights_disconnect),
                        description = deviceLabel,
                        onClick = controller::disconnect,
                    )
            }
            deviceItems +=
                SettingsSubScreenItem(
                    title = stringResource(Res.string.car_lights_forget),
                    description = stringResource(Res.string.car_lights_forget_description, savedName),
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
                enabled = !discovering,
                onClick = {
                    if (!controller.hasPermissions()) {
                        requestPermissions()
                    } else {
                        controller.startDiscovery()
                    }
                },
            )
        discovered.forEach { device ->
            deviceItems += deviceItem(device, device.address == savedAddress && link == CarLightsLink.CONNECTED, controller)
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
        zoneItems +=
            listChooserSettingsItem(
                title = stringResource(if (hasZones) Res.string.car_lights_zone_central else Res.string.car_lights_zone_all),
                options = zoneOptions,
                selectedOption = settings.carLightsCentralRole,
                optionToString = { roleLabel(it) },
                onOptionSelected = { settings.carLightsCentralRole = it },
            )
        if (hasZones) {
            zoneItems +=
                listChooserSettingsItem(
                    title = stringResource(Res.string.car_lights_zone_doors),
                    options = zoneOptions,
                    selectedOption = settings.carLightsDoorsRole,
                    optionToString = { roleLabel(it) },
                    onOptionSelected = { settings.carLightsDoorsRole = it },
                )
            zoneItems +=
                listChooserSettingsItem(
                    title = stringResource(Res.string.car_lights_zone_rear),
                    options = zoneOptions,
                    selectedOption = settings.carLightsRearRole,
                    optionToString = { roleLabel(it) },
                    onOptionSelected = { settings.carLightsRearRole = it },
                )
        }
        zoneItems +=
            switchSettingsItem(
                title = stringResource(Res.string.car_lights_vivid),
                description = stringResource(Res.string.car_lights_vivid_description),
                checked = settings.carLightsVivid,
                onCheckedChange = { settings.carLightsVivid = it },
            )
        zoneItems +=
            SettingsSubScreenItem(
                title = stringResource(Res.string.car_lights_send_now),
                description = stringResource(Res.string.car_lights_send_now_description),
                enabled = link == CarLightsLink.CONNECTED,
                onClick = CarLights::resend,
            )
        SettingsSubScreenGroup(
            title = stringResource(if (hasZones) Res.string.car_lights_zones_section else Res.string.car_lights_roles_section),
            items = zoneItems,
        )
    }
}

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

@Composable
private fun deviceItem(
    device: CarLightDevice,
    isConnected: Boolean,
    controller: CarLightsController,
) = SettingsSubScreenItem(
    title = device.name.ifEmpty { device.address },
    description = stringResource(Res.string.car_lights_device_details, device.address, device.rssi),
    enabled = !isConnected,
    onClick = { controller.connect(device) },
)

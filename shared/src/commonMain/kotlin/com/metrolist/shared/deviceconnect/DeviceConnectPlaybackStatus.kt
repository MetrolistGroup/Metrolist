package com.metrolist.shared.deviceconnect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.device_connect_device_type_and_model
import com.metrolist.shared.generated.resources.device_connect_device_type_android_auto
import com.metrolist.shared.generated.resources.device_connect_device_type_android_phone
import com.metrolist.shared.generated.resources.device_connect_device_type_desktop
import com.metrolist.shared.generated.resources.device_connect_device_type_ios
import com.metrolist.shared.generated.resources.device_connect_device_type_unknown
import com.metrolist.shared.generated.resources.device_connect_device_type_wear_os
import com.metrolist.shared.generated.resources.device_connect_sync_conflict
import com.metrolist.shared.generated.resources.device_connect_sync_stale_queue
import com.metrolist.shared.generated.resources.device_connect_sync_too_large
import com.metrolist.shared.generated.resources.device_connect_unshareable_queue
import com.metrolist.shared.generated.resources.listen_together_blocked_by_host
import com.metrolist.shared.generated.resources.listen_together_error
import com.metrolist.shared.generated.resources.unavailable
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeviceConnectDeviceTypeIcon(
    deviceType: DeviceConnectDeviceType,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
) {
    val icon =
        when (deviceType) {
            DeviceConnectDeviceType.ANDROID_PHONE -> MetrolistIcons.Outlined.PhoneAndroid
            DeviceConnectDeviceType.DESKTOP -> MetrolistIcons.Outlined.Computer
            DeviceConnectDeviceType.ANDROID_AUTO -> MetrolistIcons.Outlined.DirectionsCar
            DeviceConnectDeviceType.WEAR_OS -> MetrolistIcons.Outlined.Watch
            DeviceConnectDeviceType.IOS -> MetrolistIcons.Outlined.PhoneIphone
            DeviceConnectDeviceType.UNKNOWN -> MetrolistIcons.Outlined.DeviceUnknown
        }
    Surface(
        modifier = modifier.size(40.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = tint,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = tint,
            )
        }
    }
}

@Composable
fun deviceConnectDeviceMetadata(
    deviceType: DeviceConnectDeviceType,
    model: String = "",
): String {
    val type =
        stringResource(
            when (deviceType) {
                DeviceConnectDeviceType.ANDROID_PHONE -> Res.string.device_connect_device_type_android_phone
                DeviceConnectDeviceType.DESKTOP -> Res.string.device_connect_device_type_desktop
                DeviceConnectDeviceType.ANDROID_AUTO -> Res.string.device_connect_device_type_android_auto
                DeviceConnectDeviceType.WEAR_OS -> Res.string.device_connect_device_type_wear_os
                DeviceConnectDeviceType.IOS -> Res.string.device_connect_device_type_ios
                DeviceConnectDeviceType.UNKNOWN -> Res.string.device_connect_device_type_unknown
            },
        )
    return if (
        model.isBlank() ||
        deviceType == DeviceConnectDeviceType.DESKTOP ||
        model.equals(type, ignoreCase = true)
    ) {
        type
    } else {
        stringResource(Res.string.device_connect_device_type_and_model, type, model)
    }
}

@Composable
fun DeviceConnectPlaybackStatus(state: DcSyncState) {
    val resource =
        when (state.error) {
            DcSyncError.CONFLICT -> Res.string.device_connect_sync_conflict
            DcSyncError.UNSHAREABLE_QUEUE -> Res.string.device_connect_unshareable_queue
            DcSyncError.NOT_ALLOWED -> Res.string.listen_together_blocked_by_host
            DcSyncError.STALE_QUEUE -> Res.string.device_connect_sync_stale_queue
            DcSyncError.TOO_LARGE -> Res.string.device_connect_sync_too_large
            DcSyncError.UNAVAILABLE -> Res.string.unavailable
            DcSyncError.INVALID_MESSAGE -> Res.string.listen_together_error
            null -> if (state.sessionId != null && !state.playbackAvailable) Res.string.unavailable else return
        }
    Text(
        text = stringResource(resource),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun deviceConnectSegmentedShapes(
    index: Int,
    count: Int,
): ListItemShapes {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val groupedShape = if (count == 1) shapes.selectedShape else shapes.shape
    return shapes.copy(
        shape = groupedShape,
        selectedShape = groupedShape,
        pressedShape = groupedShape,
        focusedShape = groupedShape,
        hoveredShape = groupedShape,
        draggedShape = groupedShape,
    )
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun deviceConnectSegmentedColors(
    connected: Boolean = false,
    offline: Boolean = false,
    error: Boolean = false,
): ListItemColors {
    val containerColor =
        when {
            connected -> MaterialTheme.colorScheme.primaryContainer
            error -> MaterialTheme.colorScheme.errorContainer
            offline -> MaterialTheme.colorScheme.surfaceContainerLow
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        }
    val contentColor =
        when {
            connected -> MaterialTheme.colorScheme.onPrimaryContainer
            error -> MaterialTheme.colorScheme.onErrorContainer
            offline -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onSurface
        }
    val supportingContentColor =
        when {
            connected -> MaterialTheme.colorScheme.onPrimaryContainer
            error -> MaterialTheme.colorScheme.onErrorContainer
            offline -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val disabledContentColor =
        if (offline) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else contentColor
    return ListItemDefaults.segmentedColors(
        containerColor = containerColor,
        contentColor = contentColor,
        leadingContentColor = supportingContentColor,
        trailingContentColor = supportingContentColor,
        supportingContentColor = supportingContentColor,
        disabledContainerColor = containerColor,
        disabledContentColor = disabledContentColor,
        disabledLeadingContentColor = supportingContentColor,
        disabledTrailingContentColor = supportingContentColor,
        disabledSupportingContentColor = supportingContentColor,
        selectedContainerColor = containerColor,
        selectedContentColor = contentColor,
        selectedLeadingContentColor = supportingContentColor,
        selectedTrailingContentColor = supportingContentColor,
        selectedSupportingContentColor = supportingContentColor,
    )
}

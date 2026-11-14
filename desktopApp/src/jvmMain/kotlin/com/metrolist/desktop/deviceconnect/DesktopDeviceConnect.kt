package com.metrolist.desktop.deviceconnect

import com.metrolist.shared.deviceconnect.DeviceConnectDeviceType
import com.metrolist.shared.deviceconnect.DeviceConnectManager

object DesktopDeviceConnect {
    val manager = DeviceConnectManager(localDeviceType = DeviceConnectDeviceType.DESKTOP)
}

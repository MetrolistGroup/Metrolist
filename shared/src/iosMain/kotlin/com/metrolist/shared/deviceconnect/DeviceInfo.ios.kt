package com.metrolist.shared.deviceconnect

import platform.UIKit.UIDevice

actual fun getDeviceModelString(): String = "Apple ${UIDevice.currentDevice.model}"

internal actual fun getDefaultDeviceName(): String = getDeviceModelString()

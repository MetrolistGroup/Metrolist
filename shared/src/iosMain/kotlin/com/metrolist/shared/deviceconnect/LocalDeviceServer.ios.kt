package com.metrolist.shared.deviceconnect

actual class LocalDeviceServer {
    actual fun start(
        port: Int,
        onFailure: (Throwable) -> Unit,
        onChannel: suspend (DeviceChannel) -> Unit,
    ): Int = 0

    actual fun stop() {}
}

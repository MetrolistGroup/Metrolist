/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

internal actual fun createBluetoothFallbackTransport(
    context: Any?,
    onError: (message: String, throwable: Throwable?) -> Unit,
): LocalDeviceTransport? = null

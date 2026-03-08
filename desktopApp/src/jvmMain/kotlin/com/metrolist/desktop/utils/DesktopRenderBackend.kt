package com.metrolist.desktop.utils

internal enum class DesktopRenderBackend {
    AUTO,
    DIRECT3D,
    OPENGL,
    SOFTWARE,
    METAL,
}

internal fun desktopRenderBackends(
    osName: String = System.getProperty("os.name"),
    osArch: String = System.getProperty("os.arch"),
): List<DesktopRenderBackend> {
    val windows = osName.startsWith("Windows", ignoreCase = true)
    val mac = osName.startsWith("Mac", ignoreCase = true)
    val arm64 = osArch.equals("aarch64", ignoreCase = true) || osArch.equals("arm64", ignoreCase = true)
    return DesktopRenderBackend.entries.filter {
        when (it) {
            DesktopRenderBackend.DIRECT3D -> windows
            DesktopRenderBackend.OPENGL -> !windows || !arm64
            DesktopRenderBackend.METAL -> mac
            else -> true
        }
    }
}

internal fun desktopRenderApiOverride(
    choice: String,
    osName: String,
    osArch: String,
    environment: String?,
    property: String?,
): String? {
    if (environment != null || property != null) return null
    return desktopRenderBackends(osName, osArch)
        .firstOrNull { it.name == choice && it != DesktopRenderBackend.AUTO }
        ?.name
}

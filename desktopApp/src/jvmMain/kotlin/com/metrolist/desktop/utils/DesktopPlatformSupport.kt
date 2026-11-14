package com.metrolist.desktop.utils

import com.metrolist.desktop.window.jna.structure.windowsBuildNumber
import java.io.File

private const val WINDOWS_10_BUILD = 10240

internal fun isDesktopPlatformSupported(
    osName: String = System.getProperty("os.name"),
    windowsBuild: Int = windowsBuildNumber,
    omarchy: Boolean = isOmarchy(),
): Boolean =
    when {
        osName.startsWith("Windows", ignoreCase = true) -> windowsBuild >= WINDOWS_10_BUILD
        osName.contains("Linux", ignoreCase = true) -> !omarchy
        else -> true
    }

internal fun isOmarchy(
    osRelease: String? = readOsRelease(),
    omarchyPath: String? = System.getenv("OMARCHY_PATH"),
    installationDirectories: List<File> =
        listOf(
            File("/usr/share/omarchy"),
            File(System.getProperty("user.home"), ".local/share/omarchy"),
        ),
): Boolean =
    osReleaseMatches(osRelease, "omarchy") ||
        !omarchyPath.isNullOrBlank() ||
        installationDirectories.any(File::isDirectory)

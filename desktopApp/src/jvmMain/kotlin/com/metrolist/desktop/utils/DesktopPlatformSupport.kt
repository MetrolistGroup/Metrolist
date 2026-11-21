package com.metrolist.desktop.utils

import com.metrolist.desktop.window.jna.structure.windowsBuildNumber
import java.io.File
import java.util.concurrent.TimeUnit

private const val WINDOWS_10_BUILD = 10240

internal const val STOP_OMARCHY_URL = "https://stopomarchy.org/"

internal fun isDesktopPlatformSupported(
    osName: String = System.getProperty("os.name"),
    windowsBuild: Int = windowsBuildNumber,
): Boolean = !osName.startsWith("Windows", ignoreCase = true) || windowsBuild >= WINDOWS_10_BUILD

internal fun isOmarchy(
    osRelease: String? = readOsRelease(),
    omarchyPath: String? = System.getenv("OMARCHY_PATH"),
    versionFiles: List<File> =
        listOfNotNull(
            File("/usr/bin/omarchy-version"),
            File("/usr/share/omarchy/bin/omarchy-version"),
            File(System.getProperty("user.home"), ".local/share/omarchy/bin/omarchy-version"),
            omarchyPath?.takeIf(String::isNotBlank)?.let { File(it, "bin/omarchy-version") },
        ),
): Boolean =
    osReleaseMatches(osRelease, "omarchy") ||
        versionFiles.any(File::isFile)

internal fun openStopOmarchy() {
    runCatching { ProcessBuilder("xdg-open", STOP_OMARCHY_URL).start().waitFor(5, TimeUnit.SECONDS) }
}

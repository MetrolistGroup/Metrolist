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
    pacmanConf: String? = File("/etc/pacman.conf").takeIf(File::isFile)?.let { runCatching(it::readText).getOrNull() },
    markers: List<File> =
        listOf(
            // Omarchy 4 installs as a pacman package; older releases clone into the home directory.
            File("/usr/share/omarchy"),
            File("/usr/bin/omarchy-version"),
            File("/etc/profile.d/omarchy.sh"),
            File("/usr/share/uwsm/env.d/10-omarchy"),
            File("/etc/omarchy.conf"),
            File(System.getProperty("user.home"), ".local/share/omarchy"),
            File(System.getProperty("user.home"), ".config/omarchy"),
        ),
): Boolean =
    osReleaseMatches(osRelease, "omarchy") ||
        !omarchyPath.isNullOrBlank() ||
        pacmanConf?.let { Regex("""(?m)^\s*\[omarchy]\s*$""").containsMatchIn(it) } == true ||
        markers.any(File::exists)

internal fun openStopOmarchy() {
    runCatching { ProcessBuilder("xdg-open", STOP_OMARCHY_URL).start().waitFor(5, TimeUnit.SECONDS) }
}

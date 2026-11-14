/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.utils

object OsUtil {
    val isLinux: Boolean get() = System.getProperty("os.name").lowercase().contains("linux")
    val isMac: Boolean get() = System.getProperty("os.name").lowercase().contains("mac")
    val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")
    val name: String get() = System.getProperty("os.name")
}

package com.metrolist.desktop.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPlatformSupportTest {
    private val arch = "NAME=\"Arch Linux\"\nID=arch\n"

    private fun omarchy(
        osRelease: String? = arch,
        omarchyPath: String? = null,
        pacmanConf: String? = "[core]\nInclude = /etc/pacman.d/mirrorlist\n",
        markers: List<File> = emptyList(),
    ) = isOmarchy(osRelease, omarchyPath, pacmanConf, markers)

    @Test
    fun plainArchIsNotOmarchy() {
        val missing = Files.createTempDirectory("omarchy-test").toFile().resolve("absent")
        assertFalse(omarchy(markers = listOf(missing)))
        assertFalse(omarchy(pacmanConf = "#[omarchy]\n[extra]\n"))
    }

    @Test
    fun eachOmarchyMarkerIsDetected() {
        val dir = Files.createTempDirectory("omarchy-test").toFile()
        val marker = dir.resolve("omarchy-version").apply { writeText("4.0.0") }
        assertTrue(omarchy(markers = listOf(marker)))
        assertTrue(omarchy(markers = listOf(dir)))
        assertTrue(omarchy(omarchyPath = "/usr/share/omarchy"))
        assertTrue(omarchy(pacmanConf = "[core]\n[omarchy]\nServer = https://pkgs.omarchy.org/stable/\$arch\n"))
        assertTrue(omarchy(osRelease = "ID=omarchy\nID_LIKE=arch\n"))
    }

    @Test
    fun onlyOldWindowsIsRejectedByThePlatformCheck() {
        assertFalse(isDesktopPlatformSupported("Windows 7", 7601))
        assertTrue(isDesktopPlatformSupported("Windows 11", 26100))
        assertTrue(isDesktopPlatformSupported("Linux", 0))
        assertTrue(isDesktopPlatformSupported("Mac OS X", 0))
    }
}

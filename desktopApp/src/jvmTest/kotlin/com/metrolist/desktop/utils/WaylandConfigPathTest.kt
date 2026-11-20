package com.metrolist.desktop.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WaylandConfigPathTest {
    @Test
    fun relativeScaleConfigUsesHomeInsteadOfWorkingDirectory() = checkConfig("relative", cursor = false)

    @Test
    fun relativeCursorConfigUsesHomeInsteadOfWorkingDirectory() = checkConfig("relative", cursor = true)

    @Test
    fun absoluteConfigOverridesHome() {
        checkConfig("absolute", cursor = false)
        checkConfig("absolute", cursor = true)
    }

    @Test
    fun missingAbsoluteConfigDoesNotFallBackToHome() {
        checkConfig("absolute-missing", cursor = false)
        checkConfig("absolute-missing", cursor = true)
    }

    @Test
    fun emptyAndMissingConfigUseHome() {
        for (value in listOf("", null)) {
            checkConfig(value, cursor = false)
            checkConfig(value, cursor = true)
        }
    }

    private fun checkConfig(
        value: String?,
        cursor: Boolean,
    ) {
        val workingDirectory = Path.of("").toAbsolutePath()
        // On Windows the system temp directory can be on another drive.
        val root = Files.createTempDirectory(workingDirectory, "metrolist-kde-config").toFile()
        val previousHome = System.getProperty("user.home")
        try {
            val home = File(root, "home")
            val defaultConfig = File(home, ".config").apply { mkdirs() }
            val overrideConfig = File(root, "override").apply { mkdirs() }
            File(defaultConfig, "kdeglobals").writeText("[KScreen]\nScaleFactor=1.25\n")
            File(overrideConfig, "kdeglobals").writeText("[KScreen]\nScaleFactor=2\n")
            File(defaultConfig, "kcminputrc").writeText("[Mouse]\ncursorTheme=HomeCursor\ncursorSize=24\n")
            File(overrideConfig, "kcminputrc").writeText("[Mouse]\ncursorTheme=OverrideCursor\ncursorSize=48\n")
            if (value == "absolute-missing") {
                File(overrideConfig, "kdeglobals").delete()
                File(overrideConfig, "kcminputrc").delete()
            }
            System.setProperty("user.home", home.path)
            val environment = mutableMapOf("XDG_CURRENT_DESKTOP" to "KDE")
            when (value) {
                "relative" -> {
                    environment["XDG_CONFIG_HOME"] =
                        workingDirectory
                            .relativize(overrideConfig.toPath())
                            .toString()
                }

                "absolute", "absolute-missing" -> {
                    environment["XDG_CONFIG_HOME"] = overrideConfig.path
                }

                null -> {
                    Unit
                }

                else -> {
                    environment["XDG_CONFIG_HOME"] = value
                }
            }
            if (cursor) {
                val actual =
                    WaylandScale.resolveCursorSettings(environment, query = { null }, readConfig = {
                        WaylandScale.readKdeCursorConfig(environment)
                    })
                assertEquals(
                    when (value) {
                        "absolute" -> "OverrideCursor" to 48
                        "absolute-missing" -> null to null
                        else -> "HomeCursor" to 24
                    },
                    actual,
                )
            } else {
                assertEquals(
                    when (value) {
                        "absolute" -> 2f
                        "absolute-missing" -> null
                        else -> 1.25f
                    },
                    WaylandScale.detectScale(environment, wayland = true, query = { _, _ -> null }),
                )
            }
        } finally {
            if (previousHome == null) System.clearProperty("user.home") else System.setProperty("user.home", previousHome)
            root.deleteRecursively()
        }
    }
}

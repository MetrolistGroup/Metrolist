package com.metrolist.desktop.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopRenderBackendTest {
    @Test
    fun optionsAndPropertyValuesMatchSkikoPlatformSupport() {
        val platforms =
            listOf(
                Triple("Windows 11", "amd64", listOf("AUTO", "DIRECT3D", "OPENGL", "SOFTWARE")),
                Triple("Windows 11", "aarch64", listOf("AUTO", "DIRECT3D", "SOFTWARE")),
                Triple("Windows 11", "ARM64", listOf("AUTO", "DIRECT3D", "SOFTWARE")),
                Triple("Linux", "amd64", listOf("AUTO", "OPENGL", "SOFTWARE")),
                Triple("Linux", "aarch64", listOf("AUTO", "OPENGL", "SOFTWARE")),
                Triple("Mac OS X", "x86_64", listOf("AUTO", "OPENGL", "SOFTWARE", "METAL")),
                Triple("Mac OS X", "aarch64", listOf("AUTO", "OPENGL", "SOFTWARE", "METAL")),
            )
        for ((os, arch, expected) in platforms) {
            assertEquals(expected, desktopRenderBackends(os, arch).map { it.name }, "$os $arch")
            for (choice in DesktopRenderBackend.entries.map { it.name } + "unknown") {
                assertEquals(
                    choice.takeIf { it in expected && it != "AUTO" },
                    desktopRenderApiOverride(choice, os, arch, environment = null, property = null),
                    "$os $arch $choice",
                )
            }
        }
    }

    @Test
    fun explicitEnvironmentOrJvmPropertyPreventsPreferenceOverride() {
        for ((os, arch) in listOf("Windows 11" to "amd64", "Linux" to "aarch64", "Mac OS X" to "aarch64")) {
            for (choice in desktopRenderBackends(os, arch)) {
                for ((environment, property) in listOf(
                    "OPENGL" to null,
                    null to "SOFTWARE",
                    "SOFTWARE" to "OPENGL",
                    "" to null,
                    null to "",
                )) {
                    assertNull(
                        desktopRenderApiOverride(choice.name, os, arch, environment, property),
                        "$os $arch $choice env=$environment property=$property",
                    )
                }
            }
        }
    }
}

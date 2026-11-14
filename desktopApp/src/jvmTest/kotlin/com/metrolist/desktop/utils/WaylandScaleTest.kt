package com.metrolist.desktop.utils

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaylandScaleTest {
    private val kde = mapOf("XDG_CURRENT_DESKTOP" to "KDE")

    @Test
    fun fractionalKdeScaleWinsOverNeutralToolkitEnvironment() {
        assertEquals(
            1.35f,
            detect(kde + ("GDK_SCALE" to "1"), outputs = "Output: 1 eDP-1\nScale: 1.35\nGeometry: 0,0 1920x1080"),
        )
    }

    @Test
    fun kdeUsesGlobalXwaylandScaleOnMixedScaleDisplays() {
        assertEquals(
            2f,
            detect(kde, outputs = "Output: 1\nScale: 1.35\nGeometry: 0,0 1920x1080\nOutput: 2\nScale: 2\nGeometry: 1920,0 1920x1080"),
        )
    }

    @Test
    fun kscreenIgnoresMissingAndInvalidMonitorGeometry() {
        val output =
            "Output: 1\nScale: 2\nGeometry: 0,0 0x0\n" +
                "Output: 2\nScale: 2\nGeometry: 999999999999,0 1920x1080\n" +
                "Output: 3\nScale: 3\n" +
                "Output: 4\nScale: 1.35\nGeometry: -1920,0 1920x1080"
        assertEquals(listOf(WaylandScale.Monitor(-1920, 0, 1920, 1080, 1.35f)), WaylandScale.parseKScreenMonitors(output))
        assertEquals(1.35f, detect(kde, outputs = output))
    }

    @Test
    fun kdeCompositorScalingDoesNotScaleTheClientAgain() {
        assertEquals(
            1f,
            detect(
                kde + ("QT_SCALE_FACTOR" to "1.35"),
                outputs = "Output: 1\nScale: 1.35\nGeometry: 0,0 1920x1080",
                dpi = "Xft.dpi: 129.6",
                config = "[KScreen]\nXwaylandClientsScale=false\nScaleFactor=1.35",
            ),
        )
    }

    @Test
    fun xftDpiIsUsedWhenCompositorToolsAreUnavailable() {
        assertEquals(1.35f, detect(kde + ("GDK_SCALE" to "1"), dpi = "Xft.antialias: 1\nXft.dpi:\t129.6\n"))
    }

    @Test
    fun kdeConfigIsUsedWhenToolsAndXftDpiAreUnavailable() {
        assertEquals(1.35f, detect(kde + ("GDK_SCALE" to "1"), config = "[KScreen]\nScaleFactor=1.35\n"))
    }

    @Test
    fun x11AlsoUsesFractionalDpiAndIgnoresWaylandOnlyPolicy() {
        assertEquals(
            1.35f,
            detect(kde, dpi = "Xft.dpi: 129.6", config = "[KScreen]\nXwaylandClientsScale=false", wayland = false),
        )
        assertEquals(1.35f, detect(emptyMap(), dpi = "Xft.dpi: 129.6", wayland = false))
    }

    @Test
    fun x11DetectionPreservesNativeAwtGeometryAtIntegerAndFractionalScales() {
        for (scale in listOf(1f, 1.35f, 2f)) {
            val properties = Properties()
            WaylandScale.applyScaleProperties(scale, forceScale = null, wayland = false, properties)
            assertNull(properties.getProperty("sun.java2d.uiScale"))
            assertEquals(scale.toString(), properties.getProperty("skiko.uiScale"))
        }
    }

    @Test
    fun x11DetectionPreservesAnExplicitAwtScale() {
        val properties = Properties().apply { setProperty("sun.java2d.uiScale", "2") }
        WaylandScale.applyScaleProperties(1.35f, forceScale = null, wayland = false, properties)
        assertEquals("2", properties.getProperty("sun.java2d.uiScale"))
    }

    @Test
    fun xwaylandRetainsClientSideScaleCorrection() {
        val properties = Properties()
        WaylandScale.applyScaleProperties(1.35f, forceScale = null, wayland = true, properties)
        assertEquals("1", properties.getProperty("sun.java2d.uiScale"))
        assertEquals("1.35", properties.getProperty("skiko.uiScale"))
    }

    @Test
    fun forcedScaleStillOverridesAwtOnBothLinuxBackends() {
        for (wayland in listOf(false, true)) {
            val properties = Properties()
            WaylandScale.applyScaleProperties(2f, forceScale = 2f, wayland, properties)
            assertEquals("2.0", properties.getProperty("sun.java2d.uiScale"))
            assertEquals("2.0", properties.getProperty("skiko.uiScale"))
        }
    }

    @Test
    fun toolkitFactorsRetainFractionsAndCombineGtkDpiScale() {
        assertEquals(1.35f, WaylandScale.parseEnvironmentScale(mapOf("QT_SCALE_FACTOR" to "1.35", "GDK_SCALE" to "1")))
        assertEquals(1.35f, WaylandScale.parseEnvironmentScale(mapOf("GDK_SCALE" to "2", "GDK_DPI_SCALE" to "0.675")))
        assertEquals(1f, WaylandScale.parseEnvironmentScale(mapOf("GDK_SCALE" to "2", "GDK_DPI_SCALE" to "0.5")))
        assertEquals(1.35f, WaylandScale.parseEnvironmentScale(mapOf("QT_SCALE_FACTOR" to "1", "GDK_SCALE" to "1.35")))
        assertEquals(1.35f, detect(mapOf("QT_SCALE_FACTOR" to "1.35")))
    }

    @Test
    fun invalidScalesAreIgnoredInsteadOfChangingDensity() {
        for (value in listOf("", "NaN", "Infinity", "-1", "0", "100", "1.35x", "1.2.3")) {
            assertNull(WaylandScale.parseScale(value), value)
            assertNull(WaylandScale.parseEnvironmentScale(mapOf("QT_SCALE_FACTOR" to value)), value)
            assertNull(detect(kde, config = "[KScreen]\nScaleFactor=$value"), value)
        }
        for (value in listOf("NaN", "Infinity", "-96", "0", "100000", "129.6x")) {
            assertNull(WaylandScale.parseXftDpi("Xft.dpi: $value"), value)
        }
        assertNull(WaylandScale.parseXftDpi("OtherXft.dpi: 129.6"))
    }

    @Test
    fun kdeSettingsOnlyComeFromTheKscreenGroup() {
        assertEquals(
            1.35f,
            detect(kde, config = "[Other]\nScaleFactor=2\nXwaylandClientsScale=false\n[KScreen]\n ScaleFactor = 1.35\n"),
        )
        assertNull(detect(kde, config = "[Other]\nScaleFactor=2\n"))
    }

    @Test
    fun gnomeRetainsItsPrimaryLogicalMonitorScale() {
        val state = "[(0, 0, 1.25, uint32 0, true, [('eDP-1', '', '', '')]), (1920, 0, 2.0, 0, false, [('HDMI-1', '', '', '')])]"
        assertEquals(
            1.25f,
            WaylandScale.detectScale(
                environment = mapOf("XDG_CURRENT_DESKTOP" to "GNOME", "GDK_SCALE" to "1"),
                wayland = true,
                query = { command, _ -> if (command == "gdbus") state else null },
                readConfig = { null },
            ),
        )
    }

    @Test
    fun perMonitorSelectionRetainsLargestIntersectionAndNegativeCoordinates() {
        val left = WaylandScale.Monitor(-1920, 0, 1920, 1080, 1.25f)
        val right = WaylandScale.Monitor(0, 0, 2560, 1440, 2f)
        assertEquals(left, WaylandScale.monitorForWindow(listOf(left, right), -600, 100, 800, 600))
        assertEquals(right, WaylandScale.monitorForWindow(listOf(left, right), -200, 100, 800, 600))
        assertNull(WaylandScale.monitorForWindow(listOf(left, right), 4000, 0, 800, 600))
    }

    private fun detect(
        environment: Map<String, String>,
        outputs: String? = null,
        dpi: String? = null,
        config: String? = null,
        wayland: Boolean = true,
    ): Float? =
        WaylandScale.detectScale(
            environment = environment,
            wayland = wayland,
            query = { command, _ ->
                when (command) {
                    "kscreen-doctor" -> outputs
                    "xrdb" -> dpi
                    else -> null
                }
            },
            readConfig = { config },
        )
}

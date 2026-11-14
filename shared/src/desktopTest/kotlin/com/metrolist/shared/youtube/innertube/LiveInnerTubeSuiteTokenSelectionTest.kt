package com.metrolist.shared.youtube.innertube

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveInnerTubeSuiteTokenSelectionTest {
    @Test
    fun requestedDesktopSessionWinsAndFailsWithoutCredentials() {
        val desktopSettings =
            Settings(MapSettings()).apply {
                innerTubeCookie = "desktop-cookie"
                visitorData = "desktop-visitor"
                dataSyncId = "desktop-sync"
                innerTubeAuthUser = "2"
            }
        var fallbackReads = 0
        val fallbackToken = {
            fallbackReads++
            "inherited-token"
        }

        val selected =
            LiveInnerTubeSuite.selectTokenText(
                useDesktopSession = true,
                desktopSessionToken = { LiveInnerTubeSuite.readDesktopSettingsToken(desktopSettings) },
                fallbackToken = fallbackToken,
            )

        assertEquals(
            "***INNERTUBE COOKIE***\ndesktop-cookie\n***VISITOR DATA***\ndesktop-visitor\n" +
                "***DATASYNC ID***\ndesktop-sync\n***AUTH USER***\n2",
            selected,
        )
        assertEquals(0, fallbackReads)

        assertFailsWith<AssertionError> {
            LiveInnerTubeSuite.selectTokenText(
                useDesktopSession = true,
                desktopSessionToken = { LiveInnerTubeSuite.readDesktopSettingsToken(Settings(MapSettings())) },
                fallbackToken = fallbackToken,
            )
        }
        assertEquals(0, fallbackReads)
        assertEquals(
            "inherited-token",
            LiveInnerTubeSuite.selectTokenText(false, { error("Desktop credentials must not be read") }, fallbackToken),
        )
        assertEquals(1, fallbackReads)
    }
}

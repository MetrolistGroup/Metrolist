package com.metrolist.shared.ui.crash

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashScreenshotsTest {
    @Test
    fun onlyPrintScreenReleaseInTheFocusedWindowShowsTheHint() {
        assertFalse(isCrashScreenshotKey(KeyEvent.KEY_RELEASED, KeyEvent.VK_PRINTSCREEN, focused = false))
        assertFalse(isCrashScreenshotKey(KeyEvent.KEY_PRESSED, KeyEvent.VK_PRINTSCREEN, focused = true))
        assertFalse(isCrashScreenshotKey(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, focused = true))
        assertFalse(isCrashScreenshotKey(KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER, focused = true))
        assertTrue(isCrashScreenshotKey(KeyEvent.KEY_RELEASED, KeyEvent.VK_PRINTSCREEN, focused = true))
    }
}

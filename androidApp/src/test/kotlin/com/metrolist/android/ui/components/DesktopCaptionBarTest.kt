package com.metrolist.android.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCaptionBarTest {
    @Test
    fun touchFloatingWindowsKeepTheAccountInTheAppBar() {
        assertFalse(shouldUseDesktopCaption(sdkInt = 35, captionHeight = 32, hasPointerDevice = false))
        assertFalse(shouldUseDesktopCaption(sdkInt = 36, captionHeight = 32, hasPointerDevice = false))
    }

    @Test
    fun pointerWindowsUseTheCaptionRegardlessOfWindowWidth() {
        assertTrue(shouldUseDesktopCaption(sdkInt = 35, captionHeight = 32, hasPointerDevice = true))
        assertTrue(shouldUseDesktopCaption(sdkInt = 36, captionHeight = 48, hasPointerDevice = true))
    }

    @Test
    fun captionControlsRequireACaptionAndSupportedApi() {
        assertFalse(shouldUseDesktopCaption(sdkInt = 35, captionHeight = 0, hasPointerDevice = true))
        assertFalse(shouldUseDesktopCaption(sdkInt = 34, captionHeight = 32, hasPointerDevice = true))
    }
}

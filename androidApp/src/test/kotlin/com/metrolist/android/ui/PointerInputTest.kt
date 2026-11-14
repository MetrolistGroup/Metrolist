package com.metrolist.android.ui

import android.view.InputDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerInputTest {
    @Test
    fun touchpadsDoNotRequestDesktopLayout() {
        assertFalse(hasMousePointer(InputDevice.SOURCE_TOUCHPAD or InputDevice.SOURCE_MOUSE))
        assertFalse(hasMousePointer(InputDevice.SOURCE_TOUCHPAD))
        assertTrue(hasMousePointer(InputDevice.SOURCE_MOUSE))
    }
}

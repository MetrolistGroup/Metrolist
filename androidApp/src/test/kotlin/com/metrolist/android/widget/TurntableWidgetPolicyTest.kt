package com.metrolist.android.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class TurntableWidgetPolicyTest {
    @Test
    fun squareHostsKeepTheExistingContentInset() {
        assertEquals(4f to 4f, turntableWidgetPadding(110, 110))
        assertEquals(4f to 4f, turntableWidgetPadding(300, 300))
    }

    @Test
    fun rectangularHostsKeepArtworkAndControlsInTheSameCenteredSquare() {
        assertEquals(4f to 104f, turntableWidgetPadding(200, 400))
        assertEquals(104f to 4f, turntableWidgetPadding(400, 200))
        assertEquals(4f to 104.5f, turntableWidgetPadding(199, 400))
        assertEquals(104.5f to 4f, turntableWidgetPadding(400, 199))
    }
}

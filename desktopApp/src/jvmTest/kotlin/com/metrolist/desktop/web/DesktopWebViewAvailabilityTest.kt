package com.metrolist.desktop.web

import io.github.kdroidfilter.webview.wry.WebViewException
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DesktopWebViewAvailabilityTest {
    @Test
    fun nativeCreationFailureLeavesBrowserUnavailableAndDispatchContinues() {
        for (code in listOf("0x80070002", "0x80070005")) {
            val availability = DesktopWebViewAvailability()
            val error = creationFailure(code)
            availability.dispatchEvent { throw error }
            assertSame(error, availability.creationFailure.value)

            var dispatched = 0
            availability.dispatchEvent { dispatched++ }
            availability.dispatchEvent { throw creationFailure(code) }
            assertEquals(1, dispatched)
            assertSame(error, availability.creationFailure.value)
        }
    }

    @Test
    fun unrelatedDispatchFailuresStillPropagate() {
        val availability = DesktopWebViewAvailability()
        val error = IllegalStateException("Unrelated event failure")
        assertSame(error, assertFailsWith<IllegalStateException> { availability.dispatchEvent { throw error } })
        assertNull(availability.creationFailure.value)
    }

    @Test
    fun browserErrorsOutsideCreationStillPropagate() {
        val availability = DesktopWebViewAvailability()
        val error =
            creationFailure("0x80070002").apply {
                stackTrace = arrayOf(StackTraceElement(WryWebViewPanel::class.java.name, "evaluateJavaScript", "WryWebViewPanel.kt", 1))
            }
        assertSame(error, assertFailsWith<WebViewException> { availability.dispatchEvent { throw error } })
        assertNull(availability.creationFailure.value)
    }

    private fun creationFailure(code: String): WebViewException =
        WebViewException.WryException("WebView2 error: HRESULT($code)").apply {
            stackTrace = arrayOf(StackTraceElement(WryWebViewPanel::class.java.name, "createIfNeeded", "WryWebViewPanel.kt", 398))
        }
}

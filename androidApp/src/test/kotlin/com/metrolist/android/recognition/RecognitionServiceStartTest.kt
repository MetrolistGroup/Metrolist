package com.metrolist.android.recognition

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecognitionServiceStartTest {
    @Test
    fun backgroundDenialDoesNotRetryUntilAnotherRequest() {
        val application = RuntimeEnvironment.getApplication()
        val context = StartContext(application)
        context.failure = ForegroundServiceStartNotAllowedException("Background start denied")

        assertFalse(RecognitionWidgetService.start(context))
        assertEquals(1, context.attempts)
        assertNull(shadowOf(application).nextStartedService)

        context.failure = null
        assertTrue(RecognitionWidgetService.start(context))
        assertEquals(2, context.attempts)
        val command = shadowOf(application).nextStartedService
        assertEquals(RecognitionWidgetService::class.java.name, command.component?.className)
        assertEquals(RecognitionWidgetService.ACTION_START, command.action)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun whileInUsePermissionDenialDoesNotStartAnotherService() {
        val application = RuntimeEnvironment.getApplication()
        val context = StartContext(application)
        context.failure = SecurityException("Microphone permission unavailable")

        assertFalse(RecognitionWidgetService.start(context, RecognitionWidgetService.ACTION_TOGGLE))
        assertEquals(1, context.attempts)
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test
    fun unexpectedFailuresStillPropagate() {
        val context = StartContext(RuntimeEnvironment.getApplication())
        val failure = IllegalArgumentException("Invalid service intent")
        context.failure = failure

        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { RecognitionWidgetService.start(context) })
    }

    @Test
    @Config(sdk = [24, 33])
    fun widgetAndTileToggleActionSurvivesCompatStart() {
        val application = RuntimeEnvironment.getApplication()

        assertTrue(RecognitionWidgetService.start(application, RecognitionWidgetService.ACTION_TOGGLE))
        val command = shadowOf(application).nextStartedService
        assertEquals(RecognitionWidgetService::class.java.name, command.component?.className)
        assertEquals(RecognitionWidgetService.ACTION_TOGGLE, command.action)
    }

    private class StartContext(
        base: Context,
    ) : ContextWrapper(base) {
        var failure: RuntimeException? = null
        var attempts = 0

        override fun startForegroundService(service: Intent): ComponentName? {
            attempts++
            failure?.let { throw it }
            return super.startForegroundService(service)
        }
    }
}

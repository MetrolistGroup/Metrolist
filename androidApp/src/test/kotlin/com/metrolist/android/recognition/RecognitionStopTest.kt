package com.metrolist.android.recognition

import com.metrolist.shared.recognition.RecognitionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecognitionStopTest {
    @Test
    fun screenCancellationRequestsJobCancellationAndReturnsToReady() {
        val application = RuntimeEnvironment.getApplication()
        RecognitionWidgetService.stop(application)
        val command = shadowOf(application).nextStartedService
        assertEquals(RecognitionWidgetService::class.java.name, command.component?.className)
        assertEquals(RecognitionWidgetService.ACTION_STOP, command.action)
        assertEquals(RecognitionStatus.Ready, MusicRecognitionService.status.value)
        assertNull(shadowOf(application).nextStartedService)
    }
}

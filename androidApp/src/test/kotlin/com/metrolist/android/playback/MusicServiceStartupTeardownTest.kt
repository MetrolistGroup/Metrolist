package com.metrolist.android.playback

import android.app.Application
import com.metrolist.android.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class MusicServiceStartupTeardownTest {
    @Test
    fun destroyBeforePlaybackInitializationDoesNotResolveDependencies() {
        stopKoin()
        val service = stoppedBeforeInitialization()
        service.onDestroy()
        for (field in listOf("serviceScope", "podcastWriterScope")) {
            val scope = ReflectionHelpers.getField<CoroutineScope>(service, field)
            assertFalse(scope.coroutineContext[Job]!!.isActive)
        }
    }

    @Test
    fun taskRemovalBeforePlaybackInitializationDoesNotResolveDependencies() {
        stopKoin()
        val service = stoppedBeforeInitialization()
        try {
            service.onTaskRemoved(null)
        } finally {
            for (field in listOf("serviceScope", "podcastWriterScope")) {
                ReflectionHelpers.getField<CoroutineScope>(service, field).cancel()
            }
        }
    }

    private fun stoppedBeforeInitialization(): MusicService {
        val controller = Robolectric.buildService(MusicService::class.java)
        val service = controller.get()
        ReflectionHelpers.setField(service, "mApplication", App())
        controller.create()
        assertTrue(shadowOf(service).isStoppedBySelf)
        return service
    }
}

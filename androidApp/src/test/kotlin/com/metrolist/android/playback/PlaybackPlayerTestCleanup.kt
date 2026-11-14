package com.metrolist.android.playback

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

internal fun ExoPlaybackPlayer.releaseAndAwaitScope() {
    val job = ReflectionHelpers.getField<CoroutineScope>(this, "scope").coroutineContext.job
    release()
    // release() finishes reporting on IO; cancellation can still post to Robolectric's main looper.
    runBlocking {
        withTimeout(10_000L) {
            while (!job.isCompleted) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1)
            }
        }
    }
}

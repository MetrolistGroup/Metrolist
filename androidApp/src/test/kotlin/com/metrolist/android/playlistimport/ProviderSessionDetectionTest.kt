package com.metrolist.android.playlistimport

import com.metrolist.shared.deezerimport.DeezerImportManager
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.spotifyimport.spotifySessionCookie
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSessionDetectionTest {
    @Test
    fun detectsSpotifySessionAddedAfterTheLoginPageFinished() =
        runTest {
            var cookies = "sp_t=device"
            val session = async { awaitProviderCredentials { spotifySessionCookie(cookies) } }
            runCurrent()
            assertFalse(session.isCompleted)
            cookies = "sp_t=device; sp_dc=session-value"
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("session-value", session.await())
        }

    @Test
    fun detectsDeezerSessionAddedWithoutNavigation() =
        runTest {
            var cookies = "arl="
            val session = async { awaitProviderCredentials { DeezerImportManager.extractArl(cookies) } }
            runCurrent()
            assertFalse(session.isCompleted)
            cookies = "arl=deezer-session"
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("deezer-session", session.await())
        }
}

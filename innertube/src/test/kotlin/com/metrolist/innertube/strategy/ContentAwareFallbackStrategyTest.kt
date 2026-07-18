package com.metrolist.innertube.strategy

import com.metrolist.innertube.models.YouTubeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentAwareFallbackStrategyTest {
    private val strategy = ContentAwareFallbackStrategy()

    @Test
    fun `default clients exclude known broken profiles`() {
        val clients = strategy.resolveClients(ContentHints())

        assertEquals(YouTubeClient.VISIONOS, clients.first())
        assertFalse(clients.contains(YouTubeClient.IOS))
        assertFalse(clients.contains(YouTubeClient.IPADOS))
        assertFalse(clients.contains(YouTubeClient.MOBILE))
        assertFalse(clients.contains(YouTubeClient.ANDROID_CREATOR))
    }

    @Test
    fun `uploaded content prioritizes television client`() {
        val clients = strategy.resolveClients(ContentHints(isUploaded = true))

        assertEquals(YouTubeClient.TVHTML5, clients.first())
        assertTrue(clients.contains(YouTubeClient.WEB_REMIX))
    }
}

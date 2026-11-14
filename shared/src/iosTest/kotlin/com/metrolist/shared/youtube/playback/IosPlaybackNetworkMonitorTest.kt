package com.metrolist.shared.youtube.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosPlaybackNetworkMonitorTest {
    @Test
    fun pathUpdatesPublishConnectivityAndFreshPrivateScopesAcrossInstances() {
        val original = PlaybackNetworkStateStore.current
        val monitor = IosPlaybackNetworkMonitor()
        try {
            val scopes = mutableSetOf<String>()
            monitor.onPathChanged(true, isCellular = true)
            assertTrue(PlaybackNetworkStateStore.current.connected)
            assertTrue(PlaybackNetworkStateStore.current.isCellular)
            scopes += PlaybackNetworkStateStore.current.scope
            monitor.onPathChanged(true)
            assertFalse(PlaybackNetworkStateStore.current.isCellular)
            scopes += PlaybackNetworkStateStore.current.scope
            monitor.onPathChanged(false)
            assertFalse(PlaybackNetworkStateStore.current.connected)
            scopes += PlaybackNetworkStateStore.current.scope
            IosPlaybackNetworkMonitor().onPathChanged(true)
            assertTrue(PlaybackNetworkStateStore.current.connected)
            scopes += PlaybackNetworkStateStore.current.scope
            assertEquals(4, scopes.size)
            assertTrue(scopes.all { it.matches(Regex("ios-[A-Fa-f0-9-]+-[0-9]+")) })
        } finally {
            monitor.stop()
            PlaybackNetworkStateStore.publish(original)
        }
    }
}

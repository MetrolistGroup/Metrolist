package com.metrolist.shared.youtube.strategy

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientFailureKind
import com.metrolist.innertubex.extraction.strategy.ClientHealthScope
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientHealthTrackerTest {
    @Test
    fun cooldownSelectionExpiresAndStaysWithinAccountNetworkAndContentScope() {
        var now = 1_000L
        val settings = Settings(MapSettings())
        var network = PlaybackNetworkState(scope = "first")
        val tracker = ClientHealthTracker(nowMs = { now }, settings = settings, networkState = { network })
        val audio = ClientHealthScope.from(ContentHints(), authenticated = false)
        val video = ClientHealthScope.from(ContentHints(wantVideo = true), authenticated = false)
        tracker.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, audio)
        assertEquals(emptySet(), tracker.clientsInCooldown(audio))
        tracker.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, audio)
        assertEquals(setOf("TVHTML5_SIMPLY"), tracker.clientsInCooldown(audio))
        assertEquals(emptySet(), tracker.clientsInCooldown(video))

        tracker.forgivePlayabilityFailuresSince(now + 1)
        assertEquals(setOf("TVHTML5_SIMPLY"), tracker.clientsInCooldown(audio))
        tracker.forgivePlayabilityFailuresSince(now)
        assertEquals(emptySet(), tracker.clientsInCooldown(audio))
        repeat(2) { tracker.recordFailure("TVHTML5_SIMPLY", ClientFailureKind.PLAYABILITY, audio) }

        network = PlaybackNetworkState(scope = "second")
        assertEquals(emptySet(), tracker.clientsInCooldown(audio))
        network = PlaybackNetworkState(scope = "first")
        settings.accountName = "another-account"
        assertEquals(emptySet(), tracker.clientsInCooldown(audio))
        settings.accountName = ""
        now += 120_000L
        assertEquals(emptySet(), tracker.clientsInCooldown(audio))
    }

    @Test
    fun healthIsPersistentAndScopedByChannelAndNetworkValidation() {
        val settings = Settings(MapSettings()).apply { dataSyncId = "channel-a" }
        var network = PlaybackNetworkState(scope = "network-a", connected = true)
        val scope = ClientHealthScope.from(ContentHints(), authenticated = true)
        val tracker = ClientHealthTracker(nowMs = { 1_000L }, settings = settings, networkState = { network })

        tracker.recordFailure("WEB_REMIX", ClientFailureKind.MEDIA_FORBIDDEN, scope)
        assertEquals(1, tracker.health("WEB_REMIX", scope).failures)
        val restored = ClientHealthTracker(nowMs = { 1_000L }, settings = settings, networkState = { network })
        assertEquals(1, restored.health("WEB_REMIX", scope).failures)

        network = network.copy(connected = false)
        assertEquals(0, tracker.health("WEB_REMIX", scope).failures)

        network = PlaybackNetworkState(scope = "network-b", connected = true)
        assertEquals(0, tracker.health("WEB_REMIX", scope).failures)

        network = PlaybackNetworkState(scope = "network-a", connected = true)
        settings.dataSyncId = "channel-b"
        assertEquals(0, tracker.health("WEB_REMIX", scope).failures)
    }

    @Test
    fun successClearsForbiddenCooldown() {
        val settings = Settings(MapSettings())
        val network = PlaybackNetworkState(scope = "network", connected = true)
        val tracker = ClientHealthTracker(nowMs = { 1_000L }, settings = settings, networkState = { network })

        tracker.recordFailure("WEB_REMIX", ClientFailureKind.MEDIA_FORBIDDEN)
        assertTrue(tracker.scoreAdjustment("WEB_REMIX") < -1_000)

        tracker.recordSuccess("WEB_REMIX")
        assertEquals(2, tracker.scoreAdjustment("WEB_REMIX"))
    }

    @Test
    fun multipleTrackersMergePersistedUpdates() {
        val settings = Settings(MapSettings())
        val network = PlaybackNetworkState(scope = "network", connected = true)
        val first = ClientHealthTracker(nowMs = { 1_000L }, settings = settings, networkState = { network })
        val second = ClientHealthTracker(nowMs = { 1_000L }, settings = settings, networkState = { network })

        first.recordFailure("WEB_REMIX", ClientFailureKind.MEDIA_FAILURE)
        second.recordSuccess("WEB_REMIX")

        val persisted = ClientHealthTracker(nowMs = { 1_000L }, settings = settings, networkState = { network }).health("WEB_REMIX")
        assertEquals(1, persisted.failures)
        assertEquals(1, persisted.successes)
    }
}

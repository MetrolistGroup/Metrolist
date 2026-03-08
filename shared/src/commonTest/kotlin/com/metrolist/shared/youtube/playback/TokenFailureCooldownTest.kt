package com.metrolist.shared.youtube.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenFailureCooldownTest {
    @Test
    fun failuresExpireBackOffAndResetOnSessionNetworkAndSuccess() {
        var now = 0L
        var network = PlaybackNetworkState("first")
        val cooldown = TokenFailureCooldown({ now }, { network })
        cooldown.recordFailure("visitor", "cookie")
        assertTrue(cooldown.isCoolingDown("visitor", "cookie"))
        assertTrue(cooldown.isCoolingDown(null, "cookie"))
        now = 30_000
        assertFalse(cooldown.isCoolingDown("visitor", "cookie"))
        cooldown.recordFailure("visitor", "cookie")
        now = 89_999
        assertTrue(cooldown.isCoolingDown("visitor", "cookie"))
        now++
        assertFalse(cooldown.isCoolingDown("visitor", "cookie"))
        cooldown.recordFailure("visitor", "cookie")
        assertFalse(cooldown.isCoolingDown("new-visitor", "cookie"))
        cooldown.recordFailure("new-visitor", "cookie")
        assertFalse(cooldown.isCoolingDown("new-visitor", "new-cookie"))
        cooldown.recordFailure("new-visitor", "new-cookie")
        network = PlaybackNetworkState("second")
        cooldown.recordFailure("new-visitor", "new-cookie")
        assertFalse(cooldown.isCoolingDown("new-visitor", "new-cookie"))
        cooldown.recordFailure("new-visitor", "new-cookie")
        cooldown.reset()
        assertFalse(cooldown.isCoolingDown("new-visitor", "new-cookie"))
        cooldown.recordFailure(null, "prewarm-cookie")
        assertTrue(cooldown.isCoolingDown("prewarm-visitor", "prewarm-cookie"))
    }
}

package com.metrolist.music.utils.cipher

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unknown-hash self-heal (forceRefresh) and the stream-rejection refresh
 * (refreshAfterStreamRejection) MUST use independent cooldown stamps. A stream rejection fires on
 * any 403 — including unrelated/expired-URL ones — so if it shared forceRefresh's cooldown it could
 * delay a real player-rotation self-heal by up to 5 minutes (and vice versa). These are the exact
 * gate functions the two refresh paths call, so this proves the separation without any network.
 */
class PlayerConfigStoreCooldownTest {

    @After
    fun tearDown() {
        // Disarm both so we don't leak cooldown state into other tests in the same JVM.
        PlayerConfigStore.armForcedCooldownForTest(0L)
        PlayerConfigStore.armRejectionCooldownForTest(0L)
    }

    @Test
    fun `arming the forced cooldown does not gate the stream-rejection refresh`() {
        val now = 10_000_000L
        PlayerConfigStore.armForcedCooldownForTest(now)
        PlayerConfigStore.armRejectionCooldownForTest(0L)

        assertTrue("forceRefresh must be on cooldown", PlayerConfigStore.forcedCooldownActive(now))
        assertFalse(
            "a stream rejection must NOT be blocked by the unknown-hash cooldown",
            PlayerConfigStore.rejectionCooldownActive(now),
        )
    }

    @Test
    fun `arming the rejection cooldown does not gate the unknown-hash forceRefresh`() {
        val now = 10_000_000L
        PlayerConfigStore.armRejectionCooldownForTest(now)
        PlayerConfigStore.armForcedCooldownForTest(0L)

        assertTrue("rejection refresh must be on cooldown", PlayerConfigStore.rejectionCooldownActive(now))
        assertFalse(
            "the unknown-hash self-heal must NOT be blocked by a stream-rejection cooldown",
            PlayerConfigStore.forcedCooldownActive(now),
        )
    }
}

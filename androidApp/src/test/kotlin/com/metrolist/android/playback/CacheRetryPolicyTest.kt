package com.metrolist.android.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRetryPolicyTest {
    @Test
    fun failuresBackOffAndStopUntilANewQueueOrSuccess() {
        var now = 0L
        val policy = CacheRetryPolicy { now }
        assertTrue(policy.canAttempt("song"))
        repeat(3) { attempt ->
            policy.failed("song")
            assertFalse(policy.canAttempt("song"))
            now += 60_000
            if (attempt < 2) assertTrue(policy.canAttempt("song"))
        }
        assertFalse(policy.canAttempt("song"))
        policy.reset()
        assertTrue(policy.canAttempt("song"))
        policy.failed("song")
        policy.succeeded("song")
        assertTrue(policy.canAttempt("song"))
    }
}

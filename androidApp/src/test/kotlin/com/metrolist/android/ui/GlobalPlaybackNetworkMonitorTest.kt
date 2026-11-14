package com.metrolist.android.ui

import android.os.DeadSystemException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalPlaybackNetworkMonitorTest {
    @Test
    fun blockedNetworkRecoversWithoutAnotherAvailableCallbackAndOldCallbacksCannotOverwriteNewDefault() {
        val state = DefaultPlaybackNetworkState<String>()
        state.available("wifi")
        state.capabilities("wifi", true)
        assertTrue(state.connected)
        state.blocked("wifi", true)
        assertFalse(state.connected)
        state.available("wifi")
        assertFalse(state.connected)
        state.blocked("wifi", false)
        assertTrue(state.connected)
        state.available("mobile")
        state.capabilities("mobile", true)
        assertFalse(state.lost("wifi"))
        assertFalse(state.blocked("wifi", true))
        assertFalse(state.capabilities("wifi", false))
        assertTrue(state.connected)
        assertEquals("mobile", state.network)
        state.lost("mobile")
        assertFalse(state.connected)
        state.available("wifi")
        state.capabilities("wifi", true)
        assertTrue(state.connected)
    }

    @Test
    fun internetCapabilityAllowsRequestsWithoutRequiringNetworkValidation() {
        val state = DefaultPlaybackNetworkState<String>()
        assertFalse(state.connected)
        state.available("wifi")
        assertFalse(state.connected)
        state.capabilities("wifi", hasInternet = true)
        assertTrue(state.connected)
        state.blocked("wifi", true)
        state.capabilities("wifi", hasInternet = true)
        assertFalse(state.connected)
        state.blocked("wifi", false)
        assertTrue(state.connected)
        state.capabilities("wifi", hasInternet = false)
        assertFalse(state.connected)
        state.capabilities("wifi", hasInternet = true)
        assertTrue(state.connected)
        state.lost("wifi")
        assertFalse(state.connected)
        assertFalse(state.capabilities("wifi", hasInternet = true))
        assertFalse(state.connected)
    }

    @Test
    fun cellularUsageFollowsDefaultNetworkCapabilitiesAndIgnoresStaleCallbacks() {
        val state = DefaultPlaybackNetworkState<String>()
        state.available("mobile")
        state.capabilities("mobile", true, isCellular = true)
        assertTrue(state.isCellular)
        state.blocked("mobile", true)
        assertTrue(state.isCellular)
        state.available("wifi")
        assertFalse(state.isCellular)
        state.capabilities("wifi", true)
        assertFalse(state.capabilities("mobile", true, isCellular = true))
        assertFalse(state.lost("mobile"))
        assertFalse(state.isCellular)
        state.capabilities("wifi", true, isCellular = true)
        assertTrue(state.isCellular)
        state.lost("wifi")
        assertFalse(state.isCellular)
    }

    @Test
    fun onlySystemServerDeathIsTolerated() {
        withLiveSystem { throw RuntimeException(DeadSystemException()) }
        assertTrue(runCatching { withLiveSystem { throw IllegalStateException() } }.exceptionOrNull() is IllegalStateException)
    }
}

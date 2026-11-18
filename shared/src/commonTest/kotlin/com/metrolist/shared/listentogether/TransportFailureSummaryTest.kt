package com.metrolist.shared.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals

class TransportFailureSummaryTest {
    @Test
    fun includesCauseChainAndRedactsAddresses() {
        val error = IllegalStateException("Handshake failed", RuntimeException("Connection reset by 203.0.113.7"))

        assertEquals(
            "IllegalStateException: Handshake failed <- RuntimeException: Connection reset by <redacted-ip>",
            transportFailureSummary(error),
        )
    }

    @Test
    fun usesTypeWhenMessageIsMissing() {
        assertEquals("IllegalStateException", transportFailureSummary(IllegalStateException()))
    }
}

package com.metrolist.shared.discord

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordShutdownTest {
    @Test
    fun stalledGatewayShutdownDoesNotBlockAppShutdown() =
        runTest {
            flushDiscordPresenceBeforeClose(
                shouldClear = true,
                timeoutMs = 1L,
                shutdownGateway = { awaitCancellation() },
            )
            assertEquals(1L, testScheduler.currentTime)
        }
}

package com.metrolist.music.discord

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscordConnectionIntentTest {

    @Test
    fun merge_forceRefreshCannotBeDowngradedByOrdinaryReconnect() {
        assertEquals(
            ConnectionIntent.ForceRefreshAndIdentify,
            mergeIntents(
                current = ConnectionIntent.ForceRefreshAndIdentify,
                incoming = ConnectionIntent.EnsureConnected,
            ),
        )
        assertEquals(
            ConnectionIntent.ForceRefreshAndIdentify,
            mergeIntents(
                current = ConnectionIntent.EnsureConnected,
                incoming = ConnectionIntent.ForceRefreshAndIdentify,
            ),
        )
    }

    @Test
    fun merge_preservesHighestPriorityIntentRegardlessOfArrivalOrder() {
        val intentsByPriority = listOf(
            ConnectionIntent.EnsureConnected,
            ConnectionIntent.Resume("session", 42),
            ConnectionIntent.Identify,
            ConnectionIntent.ForceRefreshAndIdentify,
        )

        intentsByPriority.forEachIndexed { lowerIndex, lower ->
            intentsByPriority.drop(lowerIndex + 1).forEach { higher ->
                assertEquals(higher, mergeIntents(lower, higher))
                assertEquals(higher, mergeIntents(higher, lower))
            }
        }
    }

    @Test
    fun merge_replacesResumeWithLatestSessionState() {
        val latest = ConnectionIntent.Resume("new-session", 84)

        assertEquals(
            latest,
            mergeIntents(
                current = ConnectionIntent.Resume("old-session", 42),
                incoming = latest,
            ),
        )
    }
}

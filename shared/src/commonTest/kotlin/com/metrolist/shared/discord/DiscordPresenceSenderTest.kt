package com.metrolist.shared.discord

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordPresenceSenderTest {
    @Test
    fun rapidSeeksAndTrackChangeSendOnlyTheLatestTimestampsAfterCooldown() =
        runTest {
            val firstPresence = presence("first", 10_000L)
            val sent = mutableListOf<Pair<Long, String>>()
            val sender =
                DiscordPresenceSender(backgroundScope, send = {
                    sent += testScheduler.currentTime to it
                    true
                }, nowMs = { testScheduler.currentTime })
            sender.setReady(true)
            sender.submit(firstPresence, {}, {})
            runCurrent()

            repeat(20) { seek ->
                advanceTimeBy(100)
                sender.submit(presence("first", 10_000L - seek * 1_000L), {}, {})
                runCurrent()
            }
            val nextTrack = presence("second", 2_000L)
            sender.submit(nextTrack, {}, {})
            runCurrent()
            assertEquals(listOf(0L to firstPresence), sent)

            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(listOf(0L to firstPresence, 5_000L to nextTrack), sent)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, sent.size)
        }

    @Test
    fun failedWriteRetriesWithoutAnotherPlaybackUpdate() =
        runTest {
            val sent = mutableListOf<Long>()
            var failures = 0
            var successes = 0
            val sender =
                DiscordPresenceSender(backgroundScope, send = {
                    sent += testScheduler.currentTime
                    sent.size > 1
                }, nowMs = { testScheduler.currentTime })
            sender.setReady(true)
            sender.submit(presence("song", 1_000L), { successes++ }, { failures++ })
            runCurrent()
            assertEquals(1, failures)
            assertEquals(0, successes)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(0L, 5_000L), sent)
            assertEquals(1, successes)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, sent.size)
        }

    @Test
    fun disconnectRetainsNewestUpdateAndDoesNotResetCooldown() =
        runTest {
            val secondPresence = presence("second", 1_000L)
            val thirdPresence = presence("third", 2_000L)
            val sent = mutableListOf<Pair<Long, String>>()
            val sender =
                DiscordPresenceSender(backgroundScope, send = {
                    sent += testScheduler.currentTime to it
                    sent.size > 1
                }, nowMs = { testScheduler.currentTime })
            sender.setReady(true)
            sender.submit(presence("first", 0L), {}, {})
            runCurrent()
            sender.setReady(false)
            sender.submit(secondPresence, {}, {})
            advanceTimeBy(1_000)
            sender.setReady(true)
            advanceTimeBy(3_999)
            runCurrent()
            assertEquals(1, sent.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(5_000L to secondPresence, sent.last())

            sender.setReady(false)
            sender.submit(thirdPresence, {}, {})
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(2, sent.size)
            sender.setReady(true)
            runCurrent()
            assertEquals(25_000L to thirdPresence, sent.last())
        }

    @Test
    fun clearReplacesQueuedTrackAndResetDiscardsQueuedPresence() =
        runTest {
            val firstPresence = presence("first", 0L)
            val sent = mutableListOf<String>()
            val sender =
                DiscordPresenceSender(backgroundScope, send = {
                    sent += it
                    true
                }, nowMs = { testScheduler.currentTime })
            sender.setReady(true)
            sender.submit(firstPresence, {}, {})
            runCurrent()
            sender.submit(presence("second", 1_000L), {}, {})
            val clear = DiscordPresence.buildPresenceUpdate(PresenceStatus.Online, activities = emptyList())
            sender.submit(clear, {}, {})
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(firstPresence, clear), sent)

            sender.submit(presence("third", 2_000L), {}, {})
            sender.reset()
            sender.setReady(true)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, sent.size)
        }

    @Test
    fun updateDuringSocketWriteSurvivesCompletionOfOlderWrite() =
        runTest {
            val firstPresence = presence("first", 0L)
            val secondPresence = presence("second", 1_000L)
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<String>()
            var staleSuccesses = 0
            var latestSuccesses = 0
            val sender =
                DiscordPresenceSender(backgroundScope, send = {
                    sent += it
                    release.await()
                    true
                }, nowMs = { testScheduler.currentTime })
            sender.setReady(true)
            sender.submit(firstPresence, { staleSuccesses++ }, {})
            runCurrent()
            sender.submit(secondPresence, { latestSuccesses++ }, {})
            release.complete(Unit)
            runCurrent()
            assertEquals(0, staleSuccesses)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(firstPresence, secondPresence), sent)
            assertEquals(1, latestSuccesses)
        }

    @Test
    fun failedWriteIsReplacedByLatestTrackAndStopCancelsDelayedSend() =
        runTest {
            val firstPresence = presence("first", 0L)
            val secondPresence = presence("second", 1_000L)
            val sent = mutableListOf<String>()
            val sender =
                DiscordPresenceSender(backgroundScope, send = {
                    sent += it
                    sent.size > 1
                }, nowMs = { testScheduler.currentTime })
            sender.setReady(true)
            sender.submit(firstPresence, {}, {})
            runCurrent()
            sender.submit(secondPresence, {}, {})
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(listOf(firstPresence, secondPresence), sent)

            sender.submit(presence("third", 2_000L), {}, {})
            runCurrent()
            sender.stop()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2, sent.size)
        }

    private fun presence(
        song: String,
        startMs: Long,
    ): String =
        DiscordPresence.buildPresenceUpdate(
            PresenceStatus.Online,
            activities =
                listOf(
                    DiscordPresence.buildActivity(
                        name = "Metrolist",
                        type = ActivityType.Listening,
                        details = song,
                        startMs = startMs,
                        endMs = startMs + 180_000L,
                    ),
                ),
        )
}

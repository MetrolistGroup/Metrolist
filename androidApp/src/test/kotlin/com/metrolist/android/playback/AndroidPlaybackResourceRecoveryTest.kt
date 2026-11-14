package com.metrolist.android.playback

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.exoplayer.ExoPlaybackException
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailure
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackHttpStatusCarrier
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.playback.PlaybackRecoveryBudget
import com.metrolist.shared.playback.PlaybackRecoveryPolicy
import com.metrolist.shared.playback.TokenlessStreamRenewal
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidPlaybackResourceRecoveryTest {
    @Test
    fun expiredUrlsRenewTheOnlyProfileWithoutClearingTheSelectedClient() =
        runBlocking {
            for (status in listOf(404, 410)) {
                val expired = current.copy(profileId = "WEB_REMIX__po", expiresAt = now)
                val resolver = RecordingResolver(onlyProfile = true).also { it.fresh = expired.copy(url = "https://example.com/renewed") }
                val hints = ContentHints(playbackClientOverrideId = "WEB_REMIX")
                val failure =
                    object : IOException(), PlaybackHttpStatusCarrier {
                        override val playbackHttpStatusCode = status
                    }
                val result =
                    recoverPlaybackResource(
                        resolver,
                        false,
                        now,
                        PlaybackRecoveryAction.REFRESH_URL,
                        expired,
                        song.id,
                        hints,
                        song,
                        budget,
                        failure = failure,
                    )
                assertSame(resolver.fresh, result.resource)
                assertEquals(emptySet<String>(), resolver.lastExclusions)
                assertEquals(hints, resolver.lastHints)
                assertEquals(budget.consumeRequest(), result.budget)
            }
        }

    private val now = Instant.fromEpochSeconds(1_000)
    private val current = PlaybackResource("song", "https://example.com/audio", emptyMap(), null, now + 6.hours)
    private val budget = PlaybackRecoveryBudget.starting(0).consumeAttempt()
    private val song = SongItem("song", "Song", null, emptyList(), null)

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun timedOutReplacementRefreshesAfterOneSameUrlRetry() =
        runBlocking {
            val resolver = RecordingResolver()
            val first = PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, PlaybackRecoveryBudget.starting(0), 0)
            val retried = recoverPlaybackResource(resolver, false, now, first.action, current, song.id, ContentHints(), song, first.budget)
            assertSame(current, retried.resource)
            assertTrue(resolver.calls.isEmpty())

            val second = PlaybackRecoveryPolicy.decide(PlaybackFailureCategory.TIMEOUT, retried.budget, 1_000)
            assertEquals(PlaybackRecoveryAction.RETRY_CLIENT, second.action)
            val hints = ContentHints(playbackClientOverrideId = "VISIONOS_0_1")
            val replaced =
                recoverPlaybackResource(resolver, false, now, second.action, current, song.id, hints, song, second.budget)
            assertSame(resolver.fresh, replaced.resource)
            assertEquals(listOf("refresh"), resolver.calls)
            assertEquals(1, replaced.budget.requestsRemaining)
            assertNull(resolver.lastHints?.playbackClientOverrideId)

            recoverPlaybackResource(resolver, false, now, PlaybackRecoveryAction.REFRESH_URL, current, song.id, hints, song, budget)
            assertEquals(hints, resolver.lastHints)
        }

    @Test
    fun streamRefreshPreservesSelectedAudioQuality() =
        runBlocking {
            for (quality in AudioQuality.entries) {
                Settings.shared.audioQuality = quality.name
                val resolver = RecordingResolver()
                recover(resolver, action = PlaybackRecoveryAction.REFRESH_URL)
                assertEquals(listOf(quality), resolver.qualities)
            }
        }

    @Test
    fun replacementOutputFailuresKeepTheirRendererClassification() {
        for (code in listOf(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)) {
            assertEquals(PlaybackFailureCategory.RENDERER, TestPlaybackException(code).media3PlaybackFailureCategory())
        }
        assertEquals(
            PlaybackFailureCategory.DECODER,
            TestPlaybackException(PlaybackException.ERROR_CODE_DECODING_FAILED).media3PlaybackFailureCategory(),
        )
    }

    @Test
    fun stuckPlaybackRebuildsThePlayerWithABoundedRetryBudget() {
        val error =
            TestPlaybackException(
                PlaybackException.ERROR_CODE_TIMEOUT,
                StuckPlayerException(StuckPlayerException.STUCK_PLAYING_NO_PROGRESS, 60_000),
            )
        val category = error.media3PlaybackFailureCategory()
        assertEquals(PlaybackFailureCategory.RENDERER, category)
        assertTrue(
            PlaybackRecoveryPolicy.canRecover(
                PlaybackFailure("audio_stream", "Media3 playback failed", cause = error, category = category, isMediaSourceFailure = false),
            ),
        )
        var remaining = PlaybackRecoveryBudget.starting(0)
        repeat(3) {
            val decision = PlaybackRecoveryPolicy.decide(category, remaining, 0)
            assertEquals(PlaybackRecoveryAction.REBUILD_RENDERER, decision.action)
            remaining = decision.budget
        }
        assertEquals(PlaybackRecoveryAction.NONE, PlaybackRecoveryPolicy.decide(category, remaining, 0).action)
        assertEquals(
            PlaybackFailureCategory.UNKNOWN,
            TestPlaybackException(PlaybackException.ERROR_CODE_TIMEOUT, IllegalStateException("unrelated")).media3PlaybackFailureCategory(),
        )
    }

    @Test
    fun bufferingStallsChangeClientInsteadOfRebuildingTheSameCutoff() =
        runBlocking {
            for (type in listOf(StuckPlayerException.STUCK_BUFFERING_NOT_LOADING, StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS)) {
                val error = TestPlaybackException(PlaybackException.ERROR_CODE_TIMEOUT, StuckPlayerException(type, 60_000))
                val category = error.media3PlaybackFailureCategory()
                assertEquals(PlaybackFailureCategory.TIMEOUT, category)
                val tokenless = current.copy(clientName = "VISIONOS", profileId = "VISIONOS_0_1__nopo")
                val decision = PlaybackRecoveryPolicy.decide(category, budget, 0L, tokenless, 69_000L)
                assertEquals(PlaybackRecoveryAction.RETRY_CLIENT, decision.action)
                val resolver = RecordingResolver()
                val result =
                    recoverPlaybackResource(
                        resolver,
                        false,
                        now,
                        decision.action,
                        tokenless,
                        song.id,
                        ContentHints(playbackClientOverrideId = "VISIONOS_0_1"),
                        song,
                        decision.budget,
                        positionMs = 69_000L,
                    )
                assertSame(resolver.fresh, result.resource)
                assertTrue("VISIONOS_0_1__nopo" in resolver.lastExclusions)
                assertTrue("VISIONOS_SABR__nopo" in resolver.lastExclusions)
                assertNull(resolver.lastHints?.playbackClientOverrideId)
                val exclusions = TokenlessStreamRenewal.recoveryExclusions(tokenless, 69_000L)
                recoverPlaybackResource(
                    resolver,
                    false,
                    now,
                    PlaybackRecoveryAction.RETRY_CLIENT,
                    current.copy(profileId = "WEB_REMIX__po"),
                    song.id,
                    ContentHints(),
                    song,
                    decision.budget,
                    positionMs = 69_000L,
                    excludedProfiles = exclusions,
                )
                assertEquals(exclusions, resolver.lastExclusions)
            }
        }

    @Test
    fun onlyConfirmedStuckEndingsCompleteTheCurrentPlaybackInstance() {
        for (type in listOf(
            StuckPlayerException.STUCK_BUFFERING_NOT_LOADING,
            StuckPlayerException.STUCK_BUFFERING_NO_PROGRESS,
            StuckPlayerException.STUCK_PLAYING_NO_PROGRESS,
            StuckPlayerException.STUCK_PLAYING_NOT_ENDING,
            StuckPlayerException.STUCK_SUPPRESSED,
        )) {
            val error = TestPlaybackException(PlaybackException.ERROR_CODE_TIMEOUT, StuckPlayerException(type, 60_000))
            assertEquals(type == StuckPlayerException.STUCK_PLAYING_NOT_ENDING, shouldCompleteStuckPlayback(error, 1L, 1L))
            assertFalse(shouldCompleteStuckPlayback(error, 0L, 0L))
            assertFalse(shouldCompleteStuckPlayback(error, 2L, 1L))
        }
        assertFalse(shouldCompleteStuckPlayback(TestPlaybackException(PlaybackException.ERROR_CODE_TIMEOUT), 1L, 1L))
    }

    @Test
    fun downloadedAudioSurvivesRendererFailureWithoutNetworkAccess() =
        runBlocking {
            for (url in listOf("content://downloads/song", "file:/downloads/song", current.url)) {
                val resolver = RecordingResolver(IOException("offline"))
                val resource = current.copy(url = url)
                val result = recover(resolver, resource = resource, action = PlaybackRecoveryAction.REBUILD_RENDERER)
                assertSame(resource, result.resource)
                assertEquals(budget, result.budget)
                assertEquals(emptyList<String>(), resolver.calls)
            }
        }

    @Test
    fun videoDecoderFailureRetriesAudioWithoutResolvingAnotherStream() =
        runBlocking {
            val audio =
                SabrBootstrap(
                    videoId = song.id,
                    serverAbrStreamingUrl = "https://example.com/sabr",
                    videoPlaybackUstreamerConfig = byteArrayOf(1),
                    clientName = 101,
                    clientVersion = "test",
                    audioFormat = SabrFormatId(251),
                    discardVideoFormat = SabrFormatId(394),
                    discardVideoHeight = 144,
                    durationMs = 30_000L,
                    contentLengthBytes = 1_000L,
                    mimeType = "audio/webm",
                )
            val video = audio.copy(selectedVideoFormat = SabrFormatId(136))
            for (resource in listOf(
                current.copy(videoUrl = "https://example.com/video"),
                current.copy(url = "sabr://song", sabrBootstrap = audio, sabrVideoBootstrap = video),
                current.copy(videoUrl = current.url),
            )) {
                for (mime in listOf(MimeTypes.VIDEO_H264, MimeTypes.AUDIO_OPUS)) {
                    val failure =
                        ExoPlaybackException.createForRenderer(
                            IllegalStateException("decoder failed"),
                            "renderer",
                            0,
                            Format.Builder().setSampleMimeType(mime).build(),
                            C.FORMAT_HANDLED,
                            null,
                            false,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                        )
                    val decision = PlaybackRecoveryPolicy.decide(failure.media3PlaybackFailureCategory(), budget, 0)
                    val resolver = RecordingResolver()
                    val recovered =
                        recoverPlaybackResource(
                            resolver,
                            false,
                            now,
                            decision.action,
                            resource,
                            song.id,
                            ContentHints(),
                            song,
                            decision.budget,
                            failure = failure,
                        )
                    val result = requireNotNull(recovered.resource)
                    assertTrue(resolver.calls.isEmpty())
                    assertEquals(decision.budget, recovered.budget)
                    if (mime == MimeTypes.VIDEO_H264) {
                        assertFalse(result.hasVideo)
                        assertEquals(resource.url, result.url)
                        assertEquals(resource.headers, result.headers)
                        assertEquals(resource.expiresAt, result.expiresAt)
                        assertSame(resource.sabrBootstrap, result.sabrBootstrap)
                        assertNull(result.sabrVideoBootstrap)
                        assertNull(result.videoUrl)
                    } else {
                        assertSame(resource, result)
                    }
                }
            }
        }

    @Test
    fun networkRecoveryRetriesValidStreamBeforeExtractionWithoutSpendingRequestBudget() =
        runBlocking {
            val resolver = RecordingResolver()
            val result = recover(resolver)
            assertSame(current, result.resource)
            assertEquals(budget, result.budget)
            assertEquals(listOf("invalidate"), resolver.calls)
        }

    @Test
    fun unsuccessfulNetworkRetryRefreshesStreamAndConsumesRequestBudget() =
        runBlocking {
            val resolver = RecordingResolver()
            val result = recover(resolver, retryNetworkResource = false)
            assertSame(resolver.fresh, result.resource)
            assertEquals(budget.consumeRequest(), result.budget)
            assertEquals(listOf("invalidate", "refresh"), resolver.calls)
        }

    @Test
    fun expiredUnknownAndMissingResourcesRequireExtraction() =
        runBlocking {
            for (resource in listOf(null, current.copy(expiresAt = null), current.copy(expiresAt = now))) {
                val resolver = RecordingResolver()
                val result = recover(resolver, resource = resource)
                assertSame(resolver.fresh, result.resource)
                assertEquals(budget.consumeRequest(), result.budget)
                assertEquals(listOf("invalidate", "refresh"), resolver.calls)
            }
        }

    @Test
    fun forbiddenStreamRefreshesEvenWhenItsExpiryIsInTheFuture() =
        runBlocking {
            val resolver = RecordingResolver()
            val result = recover(resolver, action = PlaybackRecoveryAction.REFRESH_URL, category = PlaybackFailureCategory.CDN_FORBIDDEN)
            assertSame(resolver.fresh, result.resource)
            assertEquals(listOf("refresh"), resolver.calls)
            assertEquals(setOf(current.profileId), resolver.lastExclusions)
            assertEquals(null, resolver.lastHints?.playbackClientOverrideId)
        }

    @Test
    fun failedNetworkRefreshKeepsResourceAndRecordsSpentRequestAndFailure() =
        runBlocking {
            val failure = IOException("network unavailable")
            val resolver = RecordingResolver(failure)
            val result = recover(resolver, retryNetworkResource = false)
            assertSame(current, result.resource)
            assertEquals(failure::class, result.failure!!::class)
            assertEquals(failure.message, result.failure.message)
            assertEquals(budget.consumeRequest(), result.budget)
        }

    @Test
    fun failedLocalFileIsReplacedByRemoteStreamEvenForSameUrlRetry() =
        runBlocking {
            val resolver = RecordingResolver()
            val result =
                recover(resolver, resource = current.copy(url = "content://downloads/song"), action = PlaybackRecoveryAction.RETRY_SAME_URL)
            assertSame(resolver.fresh, result.resource)
            assertEquals(budget.consumeRequest(), result.budget)
            assertEquals(listOf("refresh"), resolver.calls)
        }

    @Test
    fun genuineLocalSongKeepsItsResourceForRendererRetry() =
        runBlocking {
            val resolver = RecordingResolver()
            val localResource = current.copy(url = "content://media/audio/song")
            val result =
                recover(
                    resolver,
                    resource = localResource,
                    action = PlaybackRecoveryAction.REBUILD_RENDERER,
                    sourceSong = song.copy(isLocal = true),
                )
            assertSame(localResource, result.resource)
            assertEquals(budget, result.budget)
            assertEquals(emptyList<String>(), resolver.calls)
        }

    @Test
    fun unsuccessfulRemoteRecoveryDoesNotRetainTheFailedDownload() =
        runBlocking {
            val failure = IOException("network unavailable")
            val result =
                recover(
                    RecordingResolver(failure),
                    resource = current.copy(url = "file:/downloads/song"),
                )
            assertNull(result.resource)
            assertEquals(failure.message, result.failure?.message)
            assertEquals(budget.consumeRequest(), result.budget)
        }

    @Test
    fun cancellationPropagatesWithoutReturningARecoveryResource() {
        val failure = CancellationException("network changed")
        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { recover(RecordingResolver(failure), retryNetworkResource = false) }
            }
        assertEquals(failure.message, thrown.message)
    }

    private suspend fun recover(
        resolver: StreamResolver,
        resource: PlaybackResource? = current,
        retryNetworkResource: Boolean = true,
        action: PlaybackRecoveryAction = PlaybackRecoveryAction.INVALIDATE_NETWORK,
        sourceSong: SongItem = song,
        category: PlaybackFailureCategory = PlaybackFailureCategory.UNKNOWN,
    ): RecoveryResourceResult =
        recoverPlaybackResource(
            resolver,
            retryNetworkResource,
            now,
            action,
            resource,
            sourceSong.id,
            ContentHints(),
            sourceSong,
            budget,
            category = category,
        )

    private class TestPlaybackException(
        code: Int,
        cause: Throwable? = null,
    ) : PlaybackException("output failed", cause, code, Bundle.EMPTY, 0L)

    private inner class RecordingResolver(
        private val failure: Exception? = null,
        private val onlyProfile: Boolean = false,
    ) : StreamResolver {
        val calls = mutableListOf<String>()
        val qualities = mutableListOf<AudioQuality>()
        var lastHints: ContentHints? = null
        var lastExclusions: Set<String> = emptySet()
        var fresh = current.copy(url = "https://example.com/refreshed")

        override suspend fun resolve(
            videoId: String,
            hints: ContentHints,
            audioQuality: AudioQuality,
            sourceSong: SongItem?,
        ): PlaybackResource = error("Unexpected resolve")

        override suspend fun invalidateNetworkScope(state: PlaybackNetworkState) {
            calls += "invalidate"
        }

        override suspend fun resolveRemote(
            videoId: String,
            hints: ContentHints,
            audioQuality: AudioQuality,
            sourceSong: SongItem?,
            refresh: Boolean,
            excludedProfiles: Set<String>,
        ): PlaybackResource {
            lastExclusions = excludedProfiles
            check(!onlyProfile || fresh.profileId !in excludedProfiles) { "The only usable profile was excluded" }
            return refresh(videoId, hints, audioQuality, sourceSong)
        }

        override suspend fun refresh(
            videoId: String,
            hints: ContentHints,
            audioQuality: AudioQuality,
            sourceSong: SongItem?,
        ): PlaybackResource {
            calls += "refresh"
            qualities += audioQuality
            lastHints = hints
            assertEquals(song.id, videoId)
            assertSame(song, sourceSong)
            failure?.let { throw it }
            return fresh
        }
    }
}

package com.metrolist.android.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.metrolist.innertubex.extraction.StreamAttemptDiagnostic
import com.metrolist.innertubex.extraction.StreamDiagnostics
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.net.ConnectException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AutomotiveQueuePolicyTest {
    private val songs =
        listOf("missing", "download", "missing2", "cache", "download").map {
            SongItem(id = it, title = it, artists = emptyList(), album = null, thumbnail = null)
        }

    @Test
    fun transportOnlyRequestsSelectAnExistingOccurrenceWithoutLibraryAccess() {
        val entries = listOf(MusicQueueEntry(12, songs[1]), MusicQueueEntry(19, songs[1]))

        fun item(
            key: Long,
            songId: String = songs[1].id,
        ) = MediaItem.Builder().setMediaId(AutomotiveMediaIds.queueSong(key, songId)).build()
        val selected = item(19)
        assertTrue(canResolveMediaSessionItems(false, false, listOf(selected), entries))
        assertEquals(1, automotiveQueueIndex(selected.mediaId, entries))
        assertEquals(0, automotiveQueueIndex(selected.mediaId, entries.reversed()))
        for (items in listOf(
            emptyList(),
            listOf(item(20)),
            listOf(item(19, "different-song")),
            listOf(MediaItem.Builder().setMediaId(AutomotiveMediaIds.song(songs[1].id)).build()),
            listOf(item(12), selected),
            listOf(
                selected
                    .buildUpon()
                    .setRequestMetadata(
                        MediaItem.RequestMetadata
                            .Builder()
                            .setSearchQuery("search")
                            .build(),
                    ).build(),
            ),
        )) {
            assertFalse(canResolveMediaSessionItems(false, false, items, entries))
            assertTrue(canResolveMediaSessionItems(true, false, items, entries))
            assertTrue(canResolveMediaSessionItems(false, true, items, entries))
        }
    }

    @Test
    fun queueActionRejectsMissingMalformedAndCollectionIdsWithoutResolving() =
        runBlocking {
            for (id in listOf(null, "", "unknown", "auto:song:!", AutomotiveMediaIds.song(""), AutomotiveMediaIds.localPlaylist("local"))) {
                val result = addAutomotiveSongToQueue(id, { false }, { error("Must not resolve") }, { error("Must not append") })
                assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
            }
            val locked =
                addAutomotiveSongToQueue(
                    AutomotiveMediaIds.song("song"),
                    { true },
                    { error("Must not resolve") },
                    { error("Must not append") },
                )
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, locked.resultCode)
        }

    @Test
    fun queueActionRechecksPlaybackLockAfterSuspendedResolution() =
        runBlocking {
            var locked = false
            val started = CompletableDeferred<Unit>()
            val resolved = CompletableDeferred<List<MediaItem>>()
            val result =
                async {
                    addAutomotiveSongToQueue(
                        AutomotiveMediaIds.song("song"),
                        { locked },
                        {
                            started.complete(Unit)
                            resolved.await()
                        },
                        { error("Must not append while locked") },
                    )
                }
            started.await()
            locked = true
            resolved.complete(listOf(MediaItem.Builder().setMediaId("song").build()))
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.await().resultCode)
        }

    @Test
    fun queueActionHandlesUnavailableSongsErrorsAndCancellationWithoutAppending() =
        runBlocking {
            val id = AutomotiveMediaIds.song("song")
            val unavailable = addAutomotiveSongToQueue(id, { false }, { emptyList() }, { error("Must not append") })
            assertEquals(SessionError.ERROR_BAD_VALUE, unavailable.resultCode)
            val failure = addAutomotiveSongToQueue(id, { false }, { throw ConnectException() }, { error("Must not append") })
            assertEquals(SessionError.ERROR_IO, failure.resultCode)
            val cancellation = CancellationException("Cancelled request")
            try {
                addAutomotiveSongToQueue(id, { false }, { throw cancellation }, { error("Must not append") })
                error("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }

    @Test
    fun queueActionPassesTheSelectedBrowseIdAndAppendsResolvedItemsOnce() =
        runBlocking {
            val id = AutomotiveMediaIds.browseSong(AutomotiveMediaIds.HISTORY, 2, "song")
            val resolved = listOf(MediaItem.Builder().setMediaId("song").build())
            var appends = 0
            val result =
                addAutomotiveSongToQueue(
                    id,
                    { false },
                    {
                        assertEquals(id, it.mediaId)
                        assertTrue(it.requestMetadata.searchQuery == null)
                        resolved
                    },
                    {
                        assertSame(resolved, it)
                        appends++
                    },
                )
            assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
            assertEquals(1, appends)
        }

    @Test
    fun authenticationResolutionRequiresAnExplicitLoginFailure() {
        fun failure(outcome: String) =
            StreamResolveException(
                StreamResolveException.Reason.NO_PLAYABLE_STREAM,
                "failed",
                diagnostics = StreamDiagnostics(listOf(StreamAttemptDiagnostic("client", null, "agent", outcome)), false),
            )
        assertEquals(
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            automotivePlaybackErrorCode(failure("playability:LOGIN_REQUIRED")),
        )
        assertEquals(PlaybackException.ERROR_CODE_UNSPECIFIED, automotivePlaybackErrorCode(failure("playability:UNPLAYABLE")))
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, automotivePlaybackErrorCode(ConnectException()))
    }

    @Test
    fun offlineTransitionsSkipMissingEntriesWithoutLoopingOrReplacingTheQueue() =
        runBlocking {
            val available: suspend (SongItem) -> Boolean = { it.id == "download" || it.id == "cache" }
            assertEquals(3, nextOfflineQueueIndex(songs, 2, false, available))
            assertNull(nextOfflineQueueIndex(songs, 4, false, available))
            assertEquals(1, nextOfflineQueueIndex(songs, 4, true, available))
            var probes = 0
            assertNull(
                nextOfflineQueueIndex(songs, 2, true) {
                    probes++
                    false
                },
            )
            assertEquals(songs.size - 1, probes)
            assertNull(nextOfflineQueueIndex(listOf(songs[0]), 0, true) { error("Must not retry the failed item") })
            assertNull(nextOfflineQueueIndex(songs, -1, true, available))
        }

    @Test
    fun offlineSelectionSkipsUnavailableSongsAndKeepsOrderAndDuplicates() =
        runBlocking {
            val queue = offlineAutomotiveQueue(AutomotiveSongResolution(songs, 2), false) { it == "download" || it == "cache" }!!
            assertEquals(listOf("download", "cache", "download"), queue.songs.map { it.id })
            assertEquals(1, queue.startIndex)
            val duplicate = offlineAutomotiveQueue(AutomotiveSongResolution(songs, 4), false) { it == "download" }!!
            assertEquals(1, duplicate.startIndex)
            val wrap = offlineAutomotiveQueue(AutomotiveSongResolution(songs, 4), false) { it == "cache" }!!
            assertEquals(0, wrap.startIndex)
            assertNull(offlineAutomotiveQueue(AutomotiveSongResolution(songs, 0), false) { false })
        }

    @Test
    fun onlineQueuesDoNotProbeLocalStorage() =
        runBlocking {
            val queue = AutomotiveSongResolution(songs, 2)
            assertSame(queue, offlineAutomotiveQueue(queue, true) { error("Unexpected disk lookup") })
        }

    @Test
    fun repeatCyclesOffAllOneOff() {
        var mode = Player.REPEAT_MODE_OFF
        for (expected in listOf(Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_OFF)) {
            mode = nextMediaSessionRepeatMode(mode)
            assertEquals(expected, mode)
        }
    }

    @Test
    fun offlineCoordinatorDoesNotBootstrapOrExtendRadio() =
        runBlocking {
            val previousNetwork = PlaybackNetworkStateStore.current
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                Settings.shared.autoLoadMore = true
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("offline-test", false))
                val player =
                    Proxy.newProxyInstance(
                        AndroidPlaybackPlayer::class.java.classLoader,
                        arrayOf(AndroidPlaybackPlayer::class.java),
                    ) { _, method, _ -> error("Unexpected playback mutation: ${method.name}") } as AndroidPlaybackPlayer
                val coordinator =
                    AndroidQueueCoordinator(
                        RuntimeEnvironment.getApplication(),
                        player,
                        scope,
                        MutableStateFlow(Player.REPEAT_MODE_OFF),
                        MutableStateFlow(false),
                        {},
                        {},
                    )
                coordinator.bootstrapRecommendations("seed")
                assertNull(coordinator.recommendationBootstrapJob)
                assertFalse(coordinator.extendRadioQueue { true })
                coordinator.schedulePlaylistPage()
                coordinator.scheduleAutoLoadMore(AutoLoadMoreTrigger(0, 0, 1, "seed", true))
            } finally {
                scope.cancel()
                Settings.replaceSharedForTest(null)
                PlaybackNetworkStateStore.publish(previousNetwork)
            }
        }
}

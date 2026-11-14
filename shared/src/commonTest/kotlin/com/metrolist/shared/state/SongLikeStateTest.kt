package com.metrolist.shared.state

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.ContinuationBranch
import com.metrolist.shared.api.PlaylistPage
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.reconcileSongVotes
import com.metrolist.shared.constants.LikedSongsCustomOrdersJsonKey
import com.metrolist.shared.constants.LikedSongsOrderAccountKey
import com.metrolist.shared.constants.LikedSongsOrderCustomizedKey
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.sortedForLikedPlaylist
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LIBRARY_REFRESH_TTL_MILLIS
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class SongLikeStateTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun overlappingRefreshesShareOneRequestAndKeepRemoteOrder() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<List<SongItem>>()
            var duplicateCalls = 0
            val first =
                async {
                    SongLikeState.refreshLikesFromRemote {
                        started.complete(Unit)
                        response.await()
                    }
                }
            started.await()
            assertTrue(SongLikeState.isRefreshing)
            val second =
                async {
                    SongLikeState.refreshLikesFromRemote {
                        duplicateCalls++
                        emptyList()
                    }
                }
            runCurrent()
            val remote = (1..1500).map { testSong.copy(id = "song-$it") }
            response.complete(remote)
            assertTrue(first.await())
            assertTrue(second.await())
            assertEquals(0, duplicateCalls)
            assertEquals(remote, SongLikeState.likedSongs)
            assertFalse(SongLikeState.isRefreshing)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun activeLikeWaiterRetriesWhenOwnerIsCancelled() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val response = CompletableDeferred<List<SongItem>>()
            val retryStarted = CompletableDeferred<Unit>()
            var retries = 0
            val first =
                async {
                    SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                        onProgress(listOf(testSong))
                        started.complete(Unit)
                        response.await()
                    }
                }
            started.await()
            val cancelledWaiter =
                async(start = CoroutineStart.UNDISPATCHED) {
                    SongLikeState.refreshLikesFromRemote(force = false) { error("Cancelled waiter retried") }
                }
            cancelledWaiter.cancelAndJoin()
            assertFailsWith<CancellationException> { cancelledWaiter.await() }
            assertTrue(first.isActive)
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    SongLikeState.refreshLikesFromRemote(force = false) {
                        retries++
                        retryStarted.complete(Unit)
                        response.await()
                    }
                }
            runCurrent()
            assertTrue(SongLikeState.hasPartialLikes)
            val completeSongs = async(start = CoroutineStart.UNDISPATCHED) { SongLikeState.awaitCompleteLikes() }
            runCurrent()
            first.cancelAndJoin()
            assertEquals(null, completeSongs.await())
            retryStarted.await()
            assertEquals(1, retries)
            assertTrue(SongLikeState.hasPartialLikes)
            assertTrue(SongLikeState.likedSongsForDisplay.isEmpty())
            assertFalse(second.isCompleted)
            response.complete(listOf(testSong))
            assertTrue(second.await())
            assertEquals(listOf(testSong), SongLikeState.likedSongs)
            assertFalse(SongLikeState.isRefreshing)
            assertTrue(SongLikeState.refreshLikesFromRemote(force = false) { error("Successful retry is fresh") })
        }

    @Test
    fun cancelledLikeScanCanBeRetriedAutomatically() =
        runTest {
            assertFailsWith<CancellationException> {
                SongLikeState.refreshLikesFromRemote(force = false) { throw CancellationException("Screen closed") }
            }
            assertFalse(SongLikeState.isRefreshing)
            assertFalse(SongLikeState.hasPartialLikes)
            assertTrue(SongLikeState.refreshLikesFromRemote(force = false) { listOf(testSong) })
            assertTrue(SongLikeState.refreshLikesFromRemote(force = false) { error("Successful retry is fresh") })
        }

    @Test
    fun cancelledLikeOwnerDoesNotRetryWaiterAfterAccountOrSessionChanges() =
        runTest {
            for (changeSession in listOf(false, true)) {
                val started = CompletableDeferred<Unit>()
                val owner =
                    async {
                        SongLikeState.refreshLikesFromRemote(force = false) {
                            started.complete(Unit)
                            awaitCancellation()
                        }
                    }
                started.await()
                val waiter =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        SongLikeState.refreshLikesFromRemote(force = false) { error("Previous account retried") }
                    }
                val tube = GlobalInnerTube.instance
                val previousAuthUser = tube.authUser
                try {
                    if (changeSession) tube.authUser = "1" else Settings.shared.dataSyncId = "other-account"
                    owner.cancelAndJoin()
                    assertFalse(waiter.await())
                    assertFalse(SongLikeState.isRefreshing)
                } finally {
                    tube.authUser = previousAuthUser
                }
            }
        }

    @Test
    fun failedRefreshClearsLoadingAndPreservesSnapshot() =
        runTest {
            SongLikeState.replaceLikesLocally(listOf(testSong))
            assertFalse(SongLikeState.refreshLikesFromRemote(force = false) { error("Offline") })
            assertFalse(SongLikeState.isRefreshing)
            assertEquals(listOf(testSong), SongLikeState.likedSongs)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun uncachedLikesPublishPagesBeforeSavingCompleteMembership() =
        runTest {
            var displayedIds = emptySet<String>()
            SongLikeState.likedSongIdsForDisplay
                .onEach { displayedIds = it }
                .launchIn(backgroundScope)
            runCurrent()
            val remote = (1..1500).map { testSong.copy(id = "song-$it") }
            val firstPageLoaded = CompletableDeferred<Unit>()
            val continueLoading = CompletableDeferred<Unit>()
            val refresh =
                async {
                    SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                        loadLikedSongPages(
                            flow {
                                emit(likesPage(remote.take(100), hasMore = true))
                                firstPageLoaded.complete(Unit)
                                continueLoading.await()
                                emit(likesPage(remote.drop(99)))
                            },
                            onProgress,
                        )
                    }
                }
            firstPageLoaded.await()
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertTrue(SongLikeState.isRefreshing)
            assertTrue(SongLikeState.hasPartialLikes)
            assertEquals(remote.take(100), SongLikeState.likedSongsForDisplay)
            assertTrue(SongLikeState.isLiked(remote.first().id))
            assertTrue(SongLikeState.likedSongs.isEmpty())
            assertTrue(SongLikeState.likedSongsFlow.value.isEmpty())
            assertTrue(SongLikeState.likedSongIds.isEmpty())
            assertEquals(remote.take(100).mapTo(hashSetOf(), SongItem::id), displayedIds)
            SongLikeState.awaitPersistence()
            assertEquals("[]", Settings.shared.likedSongsJson)

            val completeSongs = async(start = CoroutineStart.UNDISPATCHED) { SongLikeState.awaitCompleteLikes() }
            assertFalse(completeSongs.isCompleted)
            continueLoading.complete(Unit)
            assertTrue(refresh.await())
            Snapshot.sendApplyNotifications()
            runCurrent()
            assertEquals(remote.mapTo(hashSetOf(), SongItem::id), displayedIds)
            assertEquals(remote, completeSongs.await())
            assertEquals(remote, SongLikeState.likedSongsForDisplay)
            assertEquals(remote, SongLikeState.likedSongsFlow.value)
            assertFalse(SongLikeState.hasPartialLikes)
            SongLikeState.awaitPersistence()
            assertEquals(remote, Json.decodeFromString<List<SongItem>>(Settings.shared.likedSongsJson))
        }

    @Test
    fun cachedLikesStayVisibleWhenAContinuationFails() =
        runTest {
            SongLikeState.replaceLikesLocally(listOf(testSong))
            assertFalse(
                SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                    loadLikedSongPages(
                        flow {
                            emit(likesPage(listOf(testSong.copy(id = "remote")), hasMore = true))
                            assertEquals(listOf(testSong), SongLikeState.likedSongsForDisplay)
                            assertFalse(SongLikeState.hasPartialLikes)
                            assertEquals(listOf(testSong), SongLikeState.awaitCompleteLikes())
                            error("Continuation failed")
                        },
                        onProgress,
                    )
                },
            )
            assertEquals(listOf(testSong), SongLikeState.likedSongsForDisplay)
            SongLikeState.awaitPersistence()
            assertEquals(listOf(testSong), Json.decodeFromString<List<SongItem>>(Settings.shared.likedSongsJson))
        }

    @Test
    fun incompleteRefreshDiscardsPreviewWithoutSavingIt() =
        runTest {
            assertFalse(
                SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                    loadLikedSongPages(flowOf(likesPage(listOf(testSong), hasMore = true)), onProgress)
                },
            )
            assertFalse(SongLikeState.hasPartialLikes)
            assertTrue(SongLikeState.likedSongsForDisplay.isEmpty())
            SongLikeState.awaitPersistence()
            assertEquals("[]", Settings.shared.likedSongsJson)
            assertTrue(
                SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                    loadLikedSongPages(flowOf(likesPage(listOf(testSong))), onProgress)
                },
            )
            assertEquals(listOf(testSong), SongLikeState.likedSongs)
        }

    @Test
    fun votesDuringPreviewSurviveLaterPagesAndAcknowledgement() =
        runTest {
            val remote = (1..4).map { testSong.copy(id = "song-$it") }
            val added = testSong.copy(id = "local-addition")
            assertTrue(
                SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                    onProgress(remote)
                    assertFalse(SongLikeState.toggleLike(remote[0]))
                    assertEquals(1, SongLikeState.removeLikes(listOf(remote[1].id)))
                    assertTrue(SongLikeState.dislike(remote[2]))
                    assertFalse(SongLikeState.toggleLike(remote[3]))
                    assertTrue(SongLikeState.toggleLike(remote[3]))
                    SongLikeState.addLike(added)
                    Settings.shared.pendingSongVotesJson = "{}"
                    onProgress(remote)
                    assertEquals(listOf(added, remote[3]), SongLikeState.likedSongsForDisplay)
                    remote
                },
            )
            assertEquals(listOf(added, remote[3]), SongLikeState.likedSongs)
        }

    @Test
    fun accountChangeRejectsFurtherPreviewPages() =
        runTest {
            Settings.shared.dataSyncId = "account-a"
            assertFalse(
                SongLikeState.refreshLikesFromRemote(force = false) { onProgress ->
                    onProgress(listOf(testSong))
                    assertTrue(SongLikeState.hasPartialLikes)
                    Settings.shared.dataSyncId = "account-b"
                    SongLikeState.activateCurrentAccount()
                    assertTrue(SongLikeState.likedSongsForDisplay.isEmpty())
                    onProgress(listOf(testSong, testSong.copy(id = "late-page")))
                    assertTrue(SongLikeState.likedSongsForDisplay.isEmpty())
                    listOf(testSong)
                },
            )
            assertTrue(SongLikeState.likedSongs.isEmpty())
            assertFalse(SongLikeState.hasPartialLikes)
        }

    @Test
    fun automaticLikesReuseSuccessfulScansUntilExpiryOrForcedRefresh() =
        runTest {
            var now = 1_000L
            var scans = 0
            val load: suspend ((List<SongItem>) -> Unit) -> List<SongItem>? = {
                scans++
                listOf(testSong)
            }

            suspend fun refresh(force: Boolean = false) =
                SongLikeState.refreshLikesFromRemote(force = force, nowMs = { now }, loadSongs = load)

            assertTrue(refresh())
            assertTrue(refresh())
            assertEquals(1, scans)
            now += LIBRARY_REFRESH_TTL_MILLIS - 1
            assertTrue(refresh())
            assertEquals(1, scans)
            now++
            assertTrue(refresh())
            assertEquals(2, scans)
            assertTrue(refresh(force = true))
            assertEquals(3, scans)
            assertFalse(SongLikeState.refreshLikesFromRemote(force = true) { error("Offline") })
            assertTrue(refresh())
            assertEquals(4, scans)
        }

    @Test
    fun accountAndSessionChangesInvalidateCompletedLikesScans() =
        runTest {
            var scans = 0

            suspend fun refresh() =
                SongLikeState.refreshLikesFromRemote(force = false) {
                    scans++
                    listOf(testSong)
                }
            Settings.shared.dataSyncId = "account-a"
            assertTrue(refresh())
            Settings.shared.dataSyncId = "account-b"
            assertTrue(refresh())
            Settings.shared.dataSyncId = "account-a"
            assertTrue(refresh())
            assertEquals(3, scans)
            val tube = GlobalInnerTube.instance
            val oldAuthUser = tube.authUser
            try {
                tube.authUser = if (oldAuthUser == "1") "2" else "1"
                assertTrue(refresh())
                assertEquals(4, scans)
                assertTrue(refresh())
                assertEquals(4, scans)
            } finally {
                tube.authUser = oldAuthUser
            }
        }

    @Test
    fun pendingUnlikesPreventFreshnessUntilCompletePlaylistConfirmation() =
        runTest {
            var scans = 0
            var remote = listOf(testSong)

            suspend fun refresh() =
                SongLikeState.refreshLikesFromRemote(force = false) {
                    scans++
                    remote
                }
            assertTrue(refresh())
            assertTrue(SongLikeState.removeLike(testSong.id))
            assertTrue(refresh())
            assertTrue(refresh())
            assertEquals(3, scans)
            assertFalse(SongLikeState.isLiked(testSong.id))
            assertEquals(mapOf(testSong.id to SongVote.INDIFFERENT), pendingVotes())
            remote = emptyList()
            assertTrue(refresh())
            assertTrue(pendingVotes().isEmpty())
            assertTrue(refresh())
            assertEquals(4, scans)
        }

    @Test
    fun localMembershipChangesDuringScansCannotMakeLikesFresh() =
        runTest {
            assertTrue(
                SongLikeState.refreshLikesFromRemote(force = false) {
                    SongLikeState.addLike(testSong)
                    Settings.shared.pendingSongVotesJson = "{}"
                    listOf(testSong)
                },
            )
            var scans = 0

            suspend fun refresh() =
                SongLikeState.refreshLikesFromRemote(force = false) {
                    scans++
                    listOf(testSong)
                }
            assertTrue(refresh())
            assertEquals(1, scans)
            SongLikeState.replaceLikesLocally(emptyList())
            assertTrue(refresh())
            assertEquals(2, scans)
            SongLikeState.appendLikes(listOf(testSong.copy(id = "local")))
            assertTrue(refresh())
            assertEquals(3, scans)
        }

    private fun likesPage(
        songs: List<SongItem>,
        hasMore: Boolean = false,
    ) = PlaylistPage(
        sections = mapOf("songs" to songs),
        pending = if (hasMore) listOf(ContinuationBranch("next")) else emptyList(),
        seen = emptySet(),
        session = GlobalInnerTube.instance.sessionSnapshot(),
    )

    private var previousSettings: Settings? = null
    private lateinit var backing: MapSettings
    private val testSong =
        SongItem(
            id = "test_song_123",
            title = "Test Song",
            thumbnail = null,
            artists = listOf(ArtistTiny(id = null, name = "Test Artist")),
            album = null,
        )

    @BeforeTest
    fun setUp() =
        runTest {
            previousSettings = Settings.sharedOverrideForTest()
            backing = MapSettings()
            Settings.replaceSharedForTest(Settings(backing))
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(emptyList())
            SongLikeState.awaitPersistence()
        }

    @AfterTest
    fun tearDown() =
        runTest {
            try {
                SongLikeState.awaitPersistence()
            } finally {
                Settings.replaceSharedForTest(previousSettings)
                if (previousSettings != null) SongLikeState.refreshFromSettings()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshFromSettingsReplacesSnapshotsAndInvalidatesPendingRefresh() =
        runTest {
            val previous = testSong.copy(id = "previous-account-song")
            SongLikeState.replaceLikesLocally(listOf(previous))
            SongLikeState.awaitPersistence()
            val (pendingGeneration, pendingSnapshot) = SongLikeState.beginRemoteRefresh()
            val restored = testSong.copy(id = "restored-account-song")
            Settings.shared.likedSongsJson = Json.encodeToString(listOf(restored))
            Settings.shared.dislikedSongIds = "restored-disliked-song"

            SongLikeState.refreshFromSettings()

            assertEquals(listOf(restored), SongLikeState.likedSongsFlow.value)
            assertEquals(setOf(restored.id), SongLikeState.likedSongIds)
            assertTrue(SongLikeState.isLiked(restored.id))
            assertFalse(SongLikeState.isLiked(previous.id))
            assertEquals(setOf("restored-disliked-song"), SongLikeState.dislikedSongIds)
            assertFalse(SongLikeState.applyRemoteRefresh(pendingGeneration, listOf(previous), pendingSnapshot))
            assertEquals(listOf(restored), SongLikeState.likedSongsFlow.value)

            Settings.shared.dislikedSongIds = ""
            SongLikeState.refreshFromSettings()
        }

    @Test
    fun cachedLikeIdsFollowReplacementRemovalAndEmptySnapshots() {
        val second = testSong.copy(id = "second")
        SongLikeState.replaceLikesLocally(listOf(testSong, second))
        assertEquals(setOf(testSong.id, second.id), SongLikeState.likedSongIds)
        SongLikeState.replaceLikesFromRemote(listOf(second))
        assertFalse(SongLikeState.isLiked(testSong.id))
        assertTrue(SongLikeState.isLiked(second.id))
        SongLikeState.replaceLikesFromRemote(emptyList())
        assertEquals(emptySet(), SongLikeState.likedSongIds)
    }

    @Test
    fun remoteRelikeUpdatesDateAddedWithoutChangingManualOrder() =
        runTest {
            val newer = testSong.copy(id = "newer")
            val older = testSong.copy(id = "older")
            SongLikeState.replaceLikesFromRemote(listOf(newer, testSong, older))
            SongLikeState.moveLike(testSong.id, newer.id)
            val manualOrder = SongLikeState.likedSongs

            val remote = listOf(older, newer, testSong)
            SongLikeState.replaceLikesFromRemote(remote)
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()

            assertEquals(manualOrder, SongLikeState.likedSongs)
            assertEquals(remote.map(SongItem::id), SongLikeState.likedSongRecency)
            assertEquals(
                remote,
                manualOrder.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING, SongLikeState.likedSongRecency),
            )
            assertEquals(
                remote.reversed(),
                manualOrder.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.ASCENDING, SongLikeState.likedSongRecency),
            )
        }

    @Test
    fun localRelikeMovesSongToNewestAfterRestart() =
        runTest {
            val newer = testSong.copy(id = "newer")
            SongLikeState.replaceLikesFromRemote(listOf(newer, testSong))

            assertFalse(SongLikeState.toggleLike(testSong))
            assertTrue(SongLikeState.toggleLike(testSong))
            SongLikeState.replaceLikesFromRemote(listOf(newer, testSong))
            assertTrue(pendingVotes().isEmpty())
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()

            assertEquals(listOf(testSong.id, newer.id), SongLikeState.likedSongRecency)
            assertEquals(
                listOf(testSong, newer),
                SongLikeState.likedSongs.sortedForLikedPlaylist(
                    PlaylistSongSort.DATE_ADDED,
                    SortDirection.DESCENDING,
                    SongLikeState.likedSongRecency,
                ),
            )
        }

    @Test
    fun localRelikeDuringRefreshPrecedesStaleRemoteOrder() =
        runTest {
            val newer = testSong.copy(id = "newer")
            val remote = listOf(newer, testSong)
            SongLikeState.replaceLikesFromRemote(remote)

            assertTrue(
                SongLikeState.refreshLikesFromRemote {
                    assertFalse(SongLikeState.toggleLike(testSong))
                    assertTrue(SongLikeState.toggleLike(testSong))
                    Settings.shared.pendingSongVotesJson = "{}"
                    remote
                },
            )

            assertEquals(listOf(testSong.id, newer.id), SongLikeState.likedSongRecency)
        }

    @Test
    fun remoteUnlikeAndRelikeMovesSongToNewest() {
        val newer = testSong.copy(id = "newer")
        SongLikeState.replaceLikesFromRemote(listOf(newer, testSong))
        SongLikeState.replaceLikesFromRemote(listOf(newer))
        assertEquals(listOf(newer.id), SongLikeState.likedSongRecency)

        SongLikeState.replaceLikesFromRemote(listOf(testSong, newer))

        assertEquals(listOf(testSong.id, newer.id), SongLikeState.likedSongRecency)
        assertEquals(listOf(testSong, newer), SongLikeState.likedSongs)
    }

    @Test
    fun remoteRecencySurvivesManualOrderRestartAndReliking() =
        runTest {
            val older = testSong.copy(id = "older")
            val newest = testSong.copy(id = "newest")
            SongLikeState.replaceLikesFromRemote(listOf(testSong, older))
            SongLikeState.addLike(newest)
            SongLikeState.replaceLikesFromRemote(listOf(older, testSong, newest))
            SongLikeState.moveLike(newest.id, older.id)
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(listOf(older, testSong, newest))

            val manualOrder = SongLikeState.likedSongs
            val expected = listOf(older, testSong, newest)
            assertEquals(
                expected,
                manualOrder.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING, SongLikeState.likedSongRecency),
            )
            assertEquals(
                expected.reversed(),
                manualOrder.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.ASCENDING, SongLikeState.likedSongRecency),
            )
            assertEquals(
                manualOrder,
                manualOrder.sortedForLikedPlaylist(PlaylistSongSort.ORIGINAL, SortDirection.DESCENDING, SongLikeState.likedSongRecency),
            )
            SongLikeState.removeLike(testSong.id)
            SongLikeState.addLike(testSong)
            assertEquals(listOf(testSong.id, older.id, newest.id), SongLikeState.likedSongRecency)
        }

    @Test
    fun likeDuringRefreshPrecedesNewlyDiscoveredRemoteRows() {
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        val (generation, snapshot) = SongLikeState.beginRemoteRefresh()
        val newest = testSong.copy(id = "newest")
        val discovered = testSong.copy(id = "discovered")
        SongLikeState.addLike(newest)
        assertTrue(SongLikeState.applyRemoteRefresh(generation, listOf(discovered, testSong, newest), snapshot))
        assertEquals(listOf(newest.id, discovered.id, testSong.id), SongLikeState.likedSongRecency)
    }

    @Test
    fun recencyRefreshesLegacySnapshotsAndResetsForAnotherAccount() =
        runTest {
            SongLikeState.awaitPersistence()
            val older = testSong.copy(id = "older")
            Settings.shared.likedSongsJson = Json.encodeToString(listOf(testSong, older))
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(listOf(older, testSong))
            assertEquals(listOf(older.id, testSong.id), SongLikeState.likedSongRecency)
            Settings.shared.dataSyncId = "other-account"
            SongLikeState.activateCurrentAccount()
            assertTrue(SongLikeState.likedSongRecency.isEmpty())
            SongLikeState.replaceLikesFromRemote(listOf(older, testSong))
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            assertEquals(listOf(older.id, testSong.id), SongLikeState.likedSongRecency)
        }

    @Test
    fun dateSortPreservesRemoteRecencyAfterRestartAndNewLocalLike() =
        runTest {
            val newestRemote = testSong.copy(id = "newest-remote")
            val oldestRemote = testSong.copy(id = "oldest-remote")
            SongLikeState.replaceLikesFromRemote(listOf(newestRemote, oldestRemote))
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            val localLike = testSong.copy(id = "new-local-like")
            SongLikeState.addLike(localLike)
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()

            assertEquals(
                listOf(localLike, newestRemote, oldestRemote),
                SongLikeState.likedSongsFlow.value.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING),
            )
            assertEquals(
                listOf(oldestRemote, newestRemote, localLike),
                SongLikeState.likedSongsFlow.value.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.ASCENDING),
            )
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun testIsLikedTogglesCorrectly() =
        runTest {
            val changes = mutableListOf<List<String>>()
            backgroundScope.launch {
                SongLikeState.likedSongsFlow.collect { songs -> changes.add(songs.map { it.id }) }
            }
            runCurrent()
            assertFalse(SongLikeState.isLiked(testSong.id))

            val liked = SongLikeState.toggleLike(testSong)
            runCurrent()
            assertTrue(liked)
            assertTrue(SongLikeState.isLiked(testSong.id))

            val unliked = SongLikeState.toggleLike(testSong)
            runCurrent()
            assertFalse(unliked)
            assertFalse(SongLikeState.isLiked(testSong.id))
            assertEquals(listOf(emptyList(), listOf(testSong.id), emptyList()), changes)
        }

    @Test
    fun addingMixedSelectionsKeepsExistingLikesAndIgnoresDuplicates() {
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        val added = testSong.copy(id = "new-song")
        val selected = listOf(testSong, added, added)
        assertEquals(1, SongLikeState.addLikes(selected))
        assertEquals(listOf(added, testSong), SongLikeState.likedSongsFlow.value)
        assertEquals(0, SongLikeState.addLikes(selected))
        assertEquals(listOf(added, testSong), SongLikeState.likedSongsFlow.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bulkLikesPublishAndPersistOnceAndDeliverEveryNewLike() =
        runTest {
            val existing = testSong.copy(id = "existing")
            SongLikeState.replaceLikesLocally(listOf(existing), orderCustomized = true)
            SongLikeState.awaitPersistence()
            val added = (1..100).map { testSong.copy(id = "added-$it") }
            Settings.shared.dislikedSongIds = added.first().id
            SongLikeState.refreshFromSettings()
            val writes = mutableMapOf<String, Int>()
            val writeLock = SynchronizedObject()
            Settings.replaceSharedForTest(
                Settings(
                    object : KmpSettings by backing {
                        override fun putString(
                            key: String,
                            value: String,
                        ) {
                            synchronized(writeLock) {
                                writes[key] = (writes[key] ?: 0) + 1
                                backing.putString(key, value)
                            }
                        }
                    },
                ),
            )
            val snapshots = mutableListOf<List<SongItem>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                SongLikeState.likedSongsFlow.collect { snapshots.add(it) }
            }
            val events = async(UnconfinedTestDispatcher(testScheduler)) { SongLikeState.newLikes.take(added.size).toList() }

            assertEquals(100, SongLikeState.addLikes(listOf(existing) + added + added))
            assertEquals(added, events.await())
            assertEquals(listOf(listOf(existing), added.asReversed() + existing), snapshots)
            SongLikeState.awaitPersistence()
            assertEquals(1, writes["likedSongsJson"])
            assertEquals(1, writes["pendingSongVotesJson"])
            assertEquals(1, writes[LikedSongsCustomOrdersJsonKey])
            assertFalse(SongLikeState.isDisliked(added.first().id))
            assertEquals(added.associate { it.id to SongVote.LIKE }, pendingVotes())
            SongLikeState.refreshFromSettings()
            assertEquals(added.asReversed() + existing, SongLikeState.likedSongs)
        }

    @Test
    fun bulkUnlikePersistsOnceAndKeepsOtherPendingVotes() =
        runTest {
            val songs = (1..100).map { testSong.copy(id = "liked-$it") }
            SongLikeState.replaceLikesLocally(songs, orderCustomized = true)
            SongLikeState.awaitPersistence()
            Settings.shared.pendingSongVotesJson = Json.encodeToString(mapOf("other" to SongVote.DISLIKE))
            val writes = mutableMapOf<String, Int>()
            val writeLock = SynchronizedObject()
            Settings.replaceSharedForTest(
                Settings(
                    object : KmpSettings by backing {
                        override fun putString(
                            key: String,
                            value: String,
                        ) {
                            synchronized(writeLock) {
                                writes[key] = (writes[key] ?: 0) + 1
                                backing.putString(key, value)
                            }
                        }
                    },
                ),
            )
            val removed = songs.dropLast(1)
            assertEquals(removed.size, SongLikeState.removeLikes(removed.map { it.id } + removed.first().id + "missing"))
            assertEquals(listOf(songs.last()), SongLikeState.likedSongs)
            SongLikeState.awaitPersistence()
            assertEquals(1, writes["likedSongsJson"])
            assertEquals(1, writes["pendingSongVotesJson"])
            assertEquals(1, writes[LikedSongsCustomOrdersJsonKey])
            assertEquals(removed.associate { it.id to SongVote.INDIFFERENT } + ("other" to SongVote.DISLIKE), pendingVotes())
            writes.clear()
            assertEquals(0, SongLikeState.removeLikes(removed.map { it.id }))
            assertTrue(writes.isEmpty())
        }

    @Test
    fun bulkMutationsPreserveRefreshAndAccountGuards() {
        val original = testSong.copy(id = "original")
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.replaceLikesFromRemote(listOf(original))
        val (generation, snapshot) = SongLikeState.beginRemoteRefresh()
        SongLikeState.addLikes(listOf(testSong))
        SongLikeState.removeLikes(listOf(original.id))
        assertTrue(SongLikeState.applyRemoteRefresh(generation, listOf(original), snapshot))
        assertEquals(listOf(testSong), SongLikeState.likedSongs)
        Settings.shared.dataSyncId = "account-b"
        SongLikeState.addLikes(listOf(original))
        assertEquals(listOf(original), SongLikeState.likedSongs)
        assertEquals(mapOf(original.id to SongVote.LIKE), pendingVotes())
    }

    private fun pendingVotes(): Map<String, SongVote> = Json.decodeFromString(Settings.shared.pendingSongVotesJson)

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pendingUnlikeCannotRunWhileAnImportRestoresTheLike() =
        runTest {
            SongLikeState.replaceLikesFromRemote(listOf(testSong))
            SongLikeState.removeLike(testSong.id)
            val requests = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    requests += request.url.encodedPath
                    respond("{}")
                },
            ) { install(ContentNegotiation) { json() } }.use { client ->
                val repository = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" })
                val release = CompletableDeferred<Unit>()
                val importing =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        SongLikeState.withImportVoteSync {
                            release.await()
                            SongLikeState.appendLikes(listOf(testSong))
                        }
                    }
                val syncing = async { SongLikeState.syncPendingVotes(repository) }
                runCurrent()
                assertTrue(requests.isEmpty())
                release.complete(Unit)
                importing.join()
                syncing.await()
                assertEquals(listOf("/youtubei/v1/like/like"), requests)
                assertEquals(SongVote.LIKE, pendingVotes()[testSong.id])
            }
        }

    @Test
    fun importedLikesReplacePendingUnlikeVotes() {
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        SongLikeState.removeLike(testSong.id)
        assertEquals(SongVote.INDIFFERENT, pendingVotes()[testSong.id])
        SongLikeState.appendLikes(listOf(testSong))
        assertTrue(SongLikeState.isLiked(testSong.id))
        assertEquals(SongVote.LIKE, pendingVotes()[testSong.id])
    }

    @Test
    fun importedLikesPreserveNewerUnlikeVotes() =
        runTest {
            SongLikeState.withImportVoteSync {
                SongLikeState.addLike(testSong)
                SongLikeState.removeLike(testSong.id)
                SongLikeState.appendLikes(listOf(testSong))
            }
            assertFalse(SongLikeState.isLiked(testSong.id))
            assertEquals(SongVote.INDIFFERENT, pendingVotes()[testSong.id])
        }

    @Test
    fun importedLikesCannotCrossAccounts() {
        SongLikeState.activateCurrentAccount()
        val account = Settings.shared.likedSongsOrderAccount
        Settings.shared.dataSyncId = "another-account"
        SongLikeState.appendLikes(listOf(testSong), account)
        assertTrue(SongLikeState.likedSongs.isEmpty())
        assertTrue(pendingVotes().isEmpty())
    }

    @Test
    fun importedLikesAppendWithoutReorderingExistingLikes() {
        val existing = testSong.copy(id = "existing")
        val first = testSong.copy(id = "first")
        val second = testSong.copy(id = "second")
        SongLikeState.replaceLikesFromRemote(listOf(existing))

        SongLikeState.appendLikes(listOf(first, second, first))

        assertEquals(listOf(existing, first, second), SongLikeState.likedSongsFlow.value)
    }

    @Test
    fun selectedLikedSongsKeepTheirOrderAfterPersistenceAndRemoteRefresh() =
        runTest {
            val songs = (0..4).map { testSong.copy(id = "batch-$it") }
            SongLikeState.replaceLikesFromRemote(songs)
            SongLikeState.moveLike(songs[1].id, songs[4].id, setOf(songs[1].id, songs[3].id))
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(songs)
            assertEquals(listOf(0, 2, 4, 1, 3).map(songs::get), SongLikeState.likedSongs)
            assertTrue(Settings.shared.likedSongsOrderCustomized)
        }

    @Test
    fun draggingAcrossMultiplePositionsPersistsTheFinalLikedOrder() =
        runTest {
            val songs = (0..7).map { testSong.copy(id = "song-$it") }
            SongLikeState.replaceLikesFromRemote(songs)
            val moving = songs[1]

            for (target in songs.drop(2)) {
                SongLikeState.moveLike(moving.id, target.id)
                assertEquals(moving, SongLikeState.likedSongs[SongLikeState.likedSongs.indexOf(target) + 1])
            }
            assertEquals(songs.filterNot { it == moving } + moving, SongLikeState.likedSongs)

            SongLikeState.moveLike(moving.id, songs.first().id)
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(songs)

            assertEquals(listOf(moving) + songs.filterNot { it == moving }, SongLikeState.likedSongs)
        }

    @Test
    fun consecutiveDragMovesPersistAndSurviveRemoteRefresh() =
        runTest {
            val first = testSong.copy(id = "first")
            val second = testSong.copy(id = "second")
            val third = testSong.copy(id = "third")
            val original = listOf(first, second, third)
            SongLikeState.replaceLikesFromRemote(original)

            SongLikeState.moveLike(first.id, second.id)
            assertEquals(listOf(second, first, third), SongLikeState.likedSongsFlow.value)
            SongLikeState.moveLike(first.id, third.id)
            assertEquals(listOf(second, third, first), SongLikeState.likedSongsFlow.value)

            SongLikeState.awaitPersistence()
            Settings.replaceSharedForTest(Settings(backing))
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(original)

            assertEquals(listOf(second, third, first), SongLikeState.likedSongsFlow.value)
            assertTrue(Settings.shared.likedSongsOrderCustomized)
        }

    @Test
    fun remoteRefreshPreservesPersistedManualOrder() {
        val first = testSong.copy(id = "first")
        val second = testSong.copy(id = "second")
        val added = testSong.copy(id = "added")
        SongLikeState.replaceLikesLocally(listOf(second, first), orderCustomized = true)

        SongLikeState.replaceLikesFromRemote(listOf(added, first, second))

        assertEquals(listOf("added", "second", "first"), SongLikeState.likedSongs.map(SongItem::id))
        assertTrue(Settings.shared.likedSongsOrderCustomized)
    }

    @Test
    fun firstRefreshUsesServerOrderForLegacySnapshots() =
        runTest {
            val first = testSong.copy(id = "first")
            val second = testSong.copy(id = "second")
            val added = testSong.copy(id = "added")
            SongLikeState.replaceLikesLocally(listOf(second, first), orderCustomized = false)
            SongLikeState.awaitPersistence()
            listOf(
                LikedSongsOrderCustomizedKey,
                LikedSongsOrderAccountKey,
                LikedSongsCustomOrdersJsonKey,
            ).forEach(backing::remove)
            Settings.replaceSharedForTest(Settings(backing))

            SongLikeState.replaceLikesFromRemote(listOf(added, first, second))

            assertEquals(listOf("added", "first", "second"), SongLikeState.likedSongs.map(SongItem::id))
            assertFalse(Settings.shared.likedSongsOrderCustomized)
        }

    @Test
    fun firstRefreshOnANewInstallUsesServerOrder() {
        backing = MapSettings()
        Settings.replaceSharedForTest(Settings(backing))
        val second = testSong.copy(id = "second")
        val first = testSong.copy(id = "first")

        SongLikeState.replaceLikesFromRemote(listOf(second, first))

        assertEquals(listOf("second", "first"), SongLikeState.likedSongs.map(SongItem::id))
        assertFalse(Settings.shared.likedSongsOrderCustomized)
    }

    @Test
    fun manualOrderIsScopedAndRestoredAcrossAccountSwitches() =
        runTest {
            val first = testSong.copy(id = "first")
            val second = testSong.copy(id = "second")
            val added = testSong.copy(id = "added")
            Settings.shared.dataSyncId = "account-a"
            SongLikeState.replaceLikesFromRemote(listOf(first, second))
            SongLikeState.replaceLikesLocally(listOf(second, first), orderCustomized = true)

            Settings.shared.dataSyncId = "account-b"
            SongLikeState.replaceLikesFromRemote(listOf(first, second))
            assertEquals(listOf("first", "second"), SongLikeState.likedSongs.map(SongItem::id))
            assertFalse(Settings.shared.likedSongsOrderCustomized)
            SongLikeState.replaceLikesLocally(listOf(first, second), orderCustomized = true)

            Settings.shared.dataSyncId = "account-a"
            SongLikeState.replaceLikesFromRemote(listOf(added, first, second))
            assertEquals(listOf("added", "second", "first"), SongLikeState.likedSongs.map(SongItem::id))
            assertEquals(listOf("added", "first", "second"), SongLikeState.likedSongRecency)
            assertTrue(Settings.shared.likedSongsOrderCustomized)
            SongLikeState.awaitPersistence()
            assertEquals(listOf(added, second, first), Json.decodeFromString<List<SongItem>>(Settings.shared.likedSongsJson))
            assertEquals("added,second,first", Settings.shared.likedSongIds)
            assertEquals(
                mapOf("account-a|0" to listOf("added", "second", "first"), "account-b|0" to listOf("first", "second")),
                Json.decodeFromString<Map<String, List<String>>>(Settings.shared.likedSongsCustomOrdersJson),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun snapshotWriterFinishesAnActiveWriteBeforeWritingTheLatestQueuedSnapshot() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Long>()
            val writer =
                SongLikeSnapshotWriter(backgroundScope) { snapshot ->
                    if (snapshot.revision == 1L) {
                        started.complete(Unit)
                        release.await()
                    }
                    writes.add(snapshot.revision)
                }
            val snapshot = SongLikeSnapshot(Settings.shared, "account", 1L, listOf(testSong), emptyMap(), emptyMap())
            val first = writer.submit(snapshot)
            runCurrent()
            started.await()
            val second = writer.submit(snapshot.copy(revision = 2L))
            val latest = writer.submit(snapshot.copy(revision = 3L, songs = emptyList()))
            runCurrent()

            assertTrue(writes.isEmpty())
            assertFalse(first.isCompleted)
            assertTrue(second.isCompleted)
            assertFalse(latest.isCompleted)
            release.complete(Unit)
            runCurrent()
            latest.await()

            assertEquals(listOf(1L, 3L), writes)
            assertTrue(first.isCompleted)
        }

    @Test
    fun restoredAndChangedAccountOrdersInvalidateTheDecodedCache() =
        runTest {
            val second = testSong.copy(id = "second")
            val songs = listOf(testSong, second)
            Settings.shared.dataSyncId = "account-a"
            SongLikeState.replaceLikesLocally(songs, orderCustomized = true)
            SongLikeState.awaitPersistence()
            val accountA = Settings.shared.likedSongsOrderAccount
            val restoredOrders = mapOf(accountA to listOf(second.id, testSong.id))
            Settings.shared.likedSongsJson = "[]"
            Settings.shared.likedSongsCustomOrdersJson = Json.encodeToString(restoredOrders)

            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(songs)
            assertEquals(songs.asReversed(), SongLikeState.likedSongs)
            SongLikeState.awaitPersistence()

            val accountBOrders = restoredOrders + ("account-b|0" to listOf(second.id, testSong.id))
            Settings.shared.likedSongsCustomOrdersJson = Json.encodeToString(accountBOrders)
            Settings.shared.dataSyncId = "account-b"
            SongLikeState.replaceLikesFromRemote(songs)
            assertEquals(songs.asReversed(), SongLikeState.likedSongs)
            SongLikeState.awaitPersistence()
            assertEquals(
                accountBOrders,
                Json.decodeFromString<Map<String, List<String>>>(Settings.shared.likedSongsCustomOrdersJson),
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun overlappingCallsKeepOnlyTheLatestGenerationCurrent() =
        runTest {
            val gate = LatestCallGate()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val firstResult = CompletableDeferred<Boolean>()
            backgroundScope.launch {
                val generation = gate.begin()
                firstStarted.complete(Unit)
                releaseFirst.await()
                firstResult.complete(gate.isCurrent(generation))
            }
            runCurrent()
            firstStarted.await()

            val latest = gate.begin()
            releaseFirst.complete(Unit)
            runCurrent()

            assertFalse(firstResult.await())
            assertTrue(gate.isCurrent(latest))
        }

    @Test
    fun asyncAccountMetadataDoesNotChangeTheOrderAccount() {
        Settings.shared.innerTubeCookie = "SAPISID=stable-account"
        val liked = testSong.copy(id = "liked")
        SongLikeState.replaceLikesFromRemote(listOf(liked))
        val stableAccount = Settings.shared.likedSongsOrderAccount

        Settings.shared.accountEmail = "updated@example.test"
        Settings.shared.accountChannelHandle = "@updated"
        SongLikeState.activateCurrentAccount()

        assertEquals(stableAccount, Settings.shared.likedSongsOrderAccount)
        assertEquals(listOf(liked), SongLikeState.likedSongs)
    }

    @Test
    fun savedOrderSurvivesActivationBeforeRemoteRefresh() {
        val first = testSong.copy(id = "first")
        val second = testSong.copy(id = "second")
        val added = testSong.copy(id = "added")
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.replaceLikesFromRemote(listOf(first, second))
        SongLikeState.replaceLikesLocally(listOf(second, first), orderCustomized = true)

        Settings.shared.dataSyncId = "account-b"
        SongLikeState.activateCurrentAccount()
        SongLikeState.replaceLikesFromRemote(listOf(first, second))
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.activateCurrentAccount()
        assertTrue(SongLikeState.likedSongs.isEmpty())

        SongLikeState.replaceLikesFromRemote(listOf(added, first, second))

        assertEquals(listOf("added", "second", "first"), SongLikeState.likedSongsFlow.value.map(SongItem::id))
    }

    @Test
    fun switchingAgainBeforeRefreshPreservesPendingCustomOrder() {
        val first = testSong.copy(id = "first")
        val second = testSong.copy(id = "second")
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.replaceLikesLocally(listOf(second, first), orderCustomized = true)
        Settings.shared.dataSyncId = "account-b"
        SongLikeState.activateCurrentAccount()
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.activateCurrentAccount()
        Settings.shared.dataSyncId = "account-b"
        SongLikeState.activateCurrentAccount()
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.activateCurrentAccount()

        SongLikeState.replaceLikesFromRemote(listOf(first, second))

        assertEquals(listOf(second, first), SongLikeState.likedSongs)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun screenRefreshCannotPersistThePreviousAccountsRemoteOnlyRows() =
        runTest {
            Settings.shared.dataSyncId = "account-a"
            SongLikeState.replaceLikesLocally(listOf(testSong.copy(id = "cached-account-a")))
            val snapshots = mutableListOf<List<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                SongLikeState.likedSongsFlow.collect { snapshots += it.map(SongItem::id) }
            }
            val loading = CompletableDeferred<Unit>()
            val response = CompletableDeferred<List<SongItem>>()
            val refresh =
                async {
                    SongLikeState.refreshLikesFromRemote {
                        loading.complete(Unit)
                        response.await()
                    }
                }
            loading.await()
            Settings.shared.dataSyncId = "account-b"
            SongLikeState.activateCurrentAccount()
            assertEquals(listOf(listOf("cached-account-a"), emptyList()), snapshots)
            response.complete(listOf(testSong.copy(id = "remote-only-account-a")))

            assertFalse(refresh.await())
            assertTrue(SongLikeState.likedSongs.isEmpty())
            assertEquals("[]", Settings.shared.likedSongsJson)
            assertTrue(SongLikeState.refreshLikesFromRemote { listOf(testSong) })
            assertEquals(listOf(testSong), SongLikeState.likedSongs)
            assertEquals(listOf(listOf("cached-account-a"), emptyList(), listOf(testSong.id)), snapshots)
        }

    @Test
    fun accountTransitionRejectsResponseAfterItsSessionWasChecked() {
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.activateCurrentAccount()
        val (generation, snapshot) = SongLikeState.beginRemoteRefresh()
        Settings.shared.dataSyncId = "account-b"
        SongLikeState.activateCurrentAccount()
        assertFalse(SongLikeState.applyRemoteRefresh(generation, listOf(testSong), snapshot))
        assertTrue(SongLikeState.likedSongs.isEmpty())
        assertEquals("[]", Settings.shared.likedSongsJson)

        val (currentGeneration, currentSnapshot) = SongLikeState.beginRemoteRefresh()
        assertTrue(SongLikeState.applyRemoteRefresh(currentGeneration, listOf(testSong), currentSnapshot))
        assertEquals(listOf(testSong), SongLikeState.likedSongs)
    }

    @Test
    fun activatingChangedAccountClearsPreviousLikesBeforeMutation() {
        val previous = testSong.copy(id = "previous")
        val current = testSong.copy(id = "current")
        Settings.shared.dataSyncId = "account-a"
        SongLikeState.replaceLikesFromRemote(listOf(previous))

        Settings.shared.dataSyncId = "account-b"
        SongLikeState.activateCurrentAccount()
        assertTrue(SongLikeState.likedSongsFlow.value.isEmpty())
        assertEquals("[]", Settings.shared.likedSongsJson)
        assertEquals("", Settings.shared.likedSongIds)

        SongLikeState.toggleLike(current)
        assertEquals(listOf(current), SongLikeState.likedSongsFlow.value)
        val blocked = testSong.copy(id = "blocked")
        SongLikeState.replaceLikesLocally(listOf(blocked, current))
        Settings.shared.blockedSongIds = blocked.id
        assertEquals(listOf(current), BlockedContent.filterSongs(SongLikeState.likedSongsFlow.value))
    }

    @Test
    fun emptyRemoteResetClearsTheAccountsManualOrder() {
        val first = testSong.copy(id = "first")
        val second = testSong.copy(id = "second")
        SongLikeState.replaceLikesLocally(listOf(second, first), orderCustomized = true)

        SongLikeState.replaceLikesFromRemote(emptyList())
        SongLikeState.replaceLikesFromRemote(listOf(first, second))

        assertEquals(listOf("first", "second"), SongLikeState.likedSongs.map(SongItem::id))
        assertFalse(Settings.shared.likedSongsOrderCustomized)
    }

    @Test
    fun queueDislikeTogglePersistsMetadataAndFiltersEveryOccurrence() =
        runTest {
            val other = testSong.copy(id = "other")
            val queue = listOf(testSong, other, testSong)
            SongLikeState.replaceLikesLocally(listOf(testSong))
            val previousVersion = BlockedContent.version.value

            assertTrue(SongLikeState.toggleDislike(testSong))
            assertFalse(SongLikeState.isLiked(testSong.id))
            assertEquals(listOf(other), BlockedContent.filterSongs(queue))
            assertTrue(BlockedContent.version.value > previousVersion)
            assertEquals(testSong.title, BlockedContent.songs().single { it.id == testSong.id }.title)

            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            assertTrue(SongLikeState.isDisliked(testSong.id))
            assertFalse(SongLikeState.toggleDislike(testSong))
            assertEquals(queue, BlockedContent.filterSongs(queue))
            assertFalse(SongLikeState.isLiked(testSong.id))
        }

    @Test
    fun remoteLikeClearsAnOldDislikeButPreservesAnUnsentLocalDislike() {
        Settings.shared.dislikedSongIds = testSong.id
        SongLikeState.refreshFromSettings()
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        assertFalse(SongLikeState.isDisliked(testSong.id))
        SongLikeState.dislike(testSong)
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        assertTrue(SongLikeState.isDisliked(testSong.id))
        assertFalse(SongLikeState.isLiked(testSong.id))
    }

    @Test
    fun pendingLikeSurvivesRestartAndEmptyRefreshUntilRemoteAcknowledgesIt() =
        runTest {
            SongLikeState.addLike(testSong)
            SongLikeState.awaitPersistence()
            SongLikeState.refreshFromSettings()
            SongLikeState.replaceLikesFromRemote(emptyList())
            assertEquals(listOf(testSong), SongLikeState.likedSongs)
            SongLikeState.replaceLikesFromRemote(listOf(testSong))
            SongLikeState.replaceLikesFromRemote(emptyList())
            assertTrue(SongLikeState.likedSongs.isEmpty())
        }

    @Test
    fun emptyRefreshPreservesLikeAddedDuringRequest() {
        val (generation, snapshot) = SongLikeState.beginRemoteRefresh()
        SongLikeState.addLike(testSong)
        assertTrue(SongLikeState.applyRemoteRefresh(generation, emptyList(), snapshot))
        assertEquals(listOf(testSong), SongLikeState.likedSongs)
    }

    @Test
    fun unchangedRemoteVotesDoNotNotifyContentFilters() {
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        val version = BlockedContent.version.value
        val votes = mapOf(testSong.id to SongVote.LIKE, "neutral-song" to SongVote.INDIFFERENT)

        repeat(3) {
            SongLikeState.reconcileRemoteVotes(votes, SongLikeState.voteObservationRevision())
        }

        assertEquals(version, BlockedContent.version.value)
        assertTrue(SongLikeState.isLiked(testSong.id))
    }

    @Test
    fun repeatedRemoteDislikeDoesNotNotifyContentFiltersAgain() {
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        val version = BlockedContent.version.value
        val votes = mapOf(testSong.id to SongVote.DISLIKE)
        SongLikeState.reconcileRemoteVotes(votes, SongLikeState.voteObservationRevision())
        assertTrue(BlockedContent.version.value > version)
        val dislikedVersion = BlockedContent.version.value

        repeat(3) {
            SongLikeState.reconcileRemoteVotes(votes, SongLikeState.voteObservationRevision())
        }

        assertEquals(dislikedVersion, BlockedContent.version.value)
        assertTrue(BlockedContent.isSongBlocked(testSong.id))
        SongLikeState.reconcileRemoteVotes(mapOf(testSong.id to SongVote.LIKE), SongLikeState.voteObservationRevision())
        assertTrue(BlockedContent.version.value > dislikedVersion)
        assertFalse(BlockedContent.isSongBlocked(testSong.id))
    }

    @Test
    fun observedRemoteDislikeStoresRowMetadataForSettingsAndFiltering() {
        val response =
            Json
                .parseToJsonElement(
                    """{"contents":[{"musicResponsiveListItemRenderer":{
                    "playlistItemData":{"videoId":"remote-disliked"},
                    "flexColumns":[
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Remote Song"}]}}},
                        {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Remote Artist"}]}}}
                    ],
                    "menu":{"likeButtonRenderer":{"likeStatus":"DISLIKE"}}
                }}]}""",
                ).jsonObject

        reconcileSongVotes(response, SongLikeState.voteObservationRevision())

        assertTrue(SongLikeState.isDisliked("remote-disliked"))
        assertEquals("remote-disliked", Settings.shared.dislikedSongIds)
        assertEquals("Remote Song", BlockedContent.songs().single().title)
        assertEquals(listOf("Remote Artist"), BlockedContent.songs().single().artistNames)
        assertTrue(BlockedContent.isSongBlocked("remote-disliked"))
        val version = BlockedContent.version.value
        reconcileSongVotes(response, SongLikeState.voteObservationRevision())
        assertEquals(version, BlockedContent.version.value)
    }

    @Test
    fun explicitRemoteVotesReconcileDislikesAndIgnoreMissingOrStaleState() {
        Settings.shared.dislikedSongIds = ""
        SongLikeState.refreshFromSettings()
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        SongLikeState.reconcileRemoteVotes(mapOf(testSong.id to SongVote.DISLIKE), SongLikeState.voteObservationRevision())
        assertTrue(SongLikeState.isDisliked(testSong.id))
        assertFalse(SongLikeState.isLiked(testSong.id))
        SongLikeState.reconcileRemoteVotes(emptyMap(), SongLikeState.voteObservationRevision())
        assertTrue(SongLikeState.isDisliked(testSong.id))
        SongLikeState.reconcileRemoteVotes(mapOf(testSong.id to SongVote.INDIFFERENT), SongLikeState.voteObservationRevision())
        assertFalse(SongLikeState.isDisliked(testSong.id))
        val revision = SongLikeState.voteObservationRevision()
        SongLikeState.addLike(testSong)
        SongLikeState.reconcileRemoteVotes(mapOf(testSong.id to SongVote.DISLIKE), revision)
        assertTrue(SongLikeState.isLiked(testSong.id))
        SongLikeState.reconcileRemoteVotes(mapOf(testSong.id to SongVote.INDIFFERENT), SongLikeState.voteObservationRevision())
        assertTrue(SongLikeState.isLiked(testSong.id))
    }

    @Test
    fun testReplaceLikesFromRemote() {
        assertFalse(SongLikeState.isLiked(testSong.id))
        SongLikeState.replaceLikesFromRemote(listOf(testSong))
        assertTrue(SongLikeState.isLiked(testSong.id))
    }
}

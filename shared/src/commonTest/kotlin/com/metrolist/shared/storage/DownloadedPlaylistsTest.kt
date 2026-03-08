package com.metrolist.shared.storage

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadedPlaylistsTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun saveSettings() {
        previousSettings = Settings.sharedOverrideForTest()
    }

    @AfterTest
    fun resetSettings() {
        Settings.replaceSharedForTest(previousSettings)
    }

    @Test
    fun collectionIntentSurvivesRestartAndNormalizesLikedAndRemoteAliases() {
        val backing = MapSettings()
        Settings.replaceSharedForTest(Settings(backing))
        DownloadedPlaylists.track("VLPLremote")
        DownloadedPlaylists.track("LPlocal")
        DownloadedPlaylists.track("auto:liked")
        DownloadedPlaylists.track("LP_LIKED")
        DownloadedPlaylists.track("auto:downloaded")
        DownloadedPlaylists.track("LP_DOWNLOADED")
        Settings.replaceSharedForTest(Settings(backing))
        assertEquals(setOf("PLremote", "LPlocal", "LM"), DownloadedPlaylists.ids())
        assertTrue(DownloadedPlaylists.contains("VLLM"))
        assertFalse(Settings.shared.autoDownloadOnLike)
        DownloadedPlaylists.stop("auto:liked")
        DownloadedPlaylists.stop("VLPLremote")
        assertEquals(setOf("LPlocal"), DownloadedPlaylists.ids())
    }

    @Test
    fun corruptedIntentCanBeReplacedByANewDownload() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.keepDownloadedPlaylistsJson = "invalid"
        DownloadedPlaylists.track("LPlocal")
        assertEquals(setOf("LPlocal"), DownloadedPlaylists.ids())
    }

    @Test
    fun observesInitialMembersAndAdditionsUntilTrackingStops() =
        runTest {
            val ids = MutableStateFlow(setOf("LPlocal", "LM"))
            val local = MutableStateFlow(listOf(song("existing")))
            val liked = MutableStateFlow(emptyList<SongItem>())
            val enqueued = mutableMapOf<String, List<String>>()
            val job =
                backgroundScope.launch {
                    observeDownloadedPlaylists(ids, { if (it == "LM") liked else local }) { id, songs ->
                        enqueued[id] = songs.map(SongItem::id)
                    }
                }
            runCurrent()
            assertEquals(listOf("existing"), enqueued["LPlocal"])
            local.value += listOf(song("added"), song("added"), song("device").copy(isLocal = true))
            liked.value = listOf(song("liked"))
            runCurrent()
            assertEquals(listOf("existing", "added"), enqueued["LPlocal"])
            assertEquals(listOf("liked"), enqueued["LM"])
            ids.value = setOf("LM")
            runCurrent()
            local.value += song("untracked")
            runCurrent()
            assertEquals(listOf("existing", "added"), enqueued["LPlocal"])
            job.cancelAndJoin()
        }

    @Test
    fun metadataChangesDoNotRetryButLaterMembershipChangesSurviveAnEnqueueFailure() =
        runTest {
            val songs = MutableStateFlow(listOf(song("first")))
            var attempts = 0
            val job =
                backgroundScope.launch {
                    observeDownloadedPlaylists(MutableStateFlow(setOf("LM")), { songs }) { _, _ ->
                        attempts++
                        if (attempts == 1) error("temporary enqueue failure")
                    }
                }
            runCurrent()
            songs.value = listOf(song("first").copy(title = "Updated"))
            runCurrent()
            assertEquals(1, attempts)
            songs.value += song("second")
            runCurrent()
            assertEquals(2, attempts)
            job.cancelAndJoin()
        }

    @Test
    fun stoppingTrackingCancelsAnInFlightReconciliation() =
        runTest {
            val ids = MutableStateFlow(emptySet<String>())
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var published = false
            val job =
                backgroundScope.launch {
                    observeDownloadedPlaylists(ids, { MutableStateFlow(listOf(song("pending"))) }) { _, _ ->
                        started.complete(Unit)
                        release.await()
                        published = true
                    }
                }
            runCurrent()
            ids.value = setOf("LPlocal")
            runCurrent()
            assertTrue(started.isCompleted)
            ids.value = emptySet()
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertFalse(published)
            job.cancelAndJoin()
        }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}

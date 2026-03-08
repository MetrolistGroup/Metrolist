package com.metrolist.shared.ui.mobile

import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.discord.DiscordAuthResult
import com.metrolist.shared.discord.DiscordAuthSession
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlist.PlaylistAddBatchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobileWorkflowsTest {
    @Test
    fun localBatchPrefixSurvivesDuplicatePromptAndRetryAtEachDestination() =
        runTest {
            val local = PlaylistEntity(id = "LPbatch", name = "Batch", isLocal = true)
            val songs = List(5) { SongItem("song$it", "Song", null, emptyList(), null) }
            var attempt = 0
            val workflow =
                MobilePlaylistWorkflow(
                    backgroundScope,
                    create = { _, _ -> Result.success(local) },
                    addLocalBatch = { _, remaining, allowDuplicate ->
                        when (attempt++) {
                            0 -> {
                                assertEquals(songs, remaining)
                                PlaylistAddBatchResult(2, PlaylistSongAlreadyPresentException())
                            }

                            1 -> {
                                assertEquals(songs.drop(2), remaining)
                                assertTrue(allowDuplicate)
                                PlaylistAddBatchResult(1, IllegalStateException())
                            }

                            else -> {
                                assertEquals(songs.drop(3), remaining)
                                assertFalse(allowDuplicate)
                                PlaylistAddBatchResult(2)
                            }
                        }
                    },
                    add = { _, _, _ -> error("Local songs must use the batch mutation") },
                )
            workflow.select(songs)
            workflow.togglePlaylist(local)
            workflow.addSelected()
            runCurrent()
            assertEquals(songs[2], workflow.song)
            assertEquals(local, workflow.duplicate)
            workflow.confirmDuplicate()
            runCurrent()
            assertTrue(workflow.failed)
            assertEquals(songs[3], workflow.song)
            workflow.addSelected()
            runCurrent()
            assertEquals(PlaylistAddCompletion("Batch", 5), workflow.completion)
            assertTrue(workflow.songs.isEmpty())
        }

    @Test
    fun batchRetainsProgressThroughFailureAndDuplicateApproval() =
        runTest {
            val playlist = PlaylistEntity(id = "large", name = "Large")
            val songs = List(8) { SongItem("song$it", "Song $it", null, emptyList(), null) }
            val saved = mutableListOf<SongItem>()
            var fail = true
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(playlist) }) { _, song, allowDuplicate ->
                    when {
                        song == songs[3] && fail -> {
                            Result.failure(IllegalStateException())
                        }

                        song == songs[6] && !allowDuplicate -> {
                            Result.failure(PlaylistSongAlreadyPresentException())
                        }

                        else -> {
                            saved += song
                            Result.success(Unit)
                        }
                    }
                }
            workflow.select(songs)
            workflow.togglePlaylist(playlist)
            workflow.addSelected()
            runCurrent()
            assertEquals(songs.take(3), saved)
            assertEquals(songs[3], workflow.song)
            assertEquals(5, workflow.songs.size)
            fail = false
            workflow.addSelected()
            runCurrent()
            assertEquals(playlist, workflow.duplicate)
            assertEquals(songs[6], workflow.song)
            workflow.confirmDuplicate()
            runCurrent()
            assertEquals(songs, saved)
            assertEquals(PlaylistAddCompletion("Large", 8), workflow.completion)
            assertTrue(workflow.songs.isEmpty())
            assertNull(workflow.song)
        }

    @Test
    fun multipleDestinationsReceiveTheFullBatchAndQueueEveryCompletion() =
        runTest {
            val first = PlaylistEntity(id = "local", name = "Same name", isLocal = true)
            val second = PlaylistEntity(id = "remote", name = "Same name", browseId = "PLremote")
            val songs = listOf(SongItem("a", "A", null, emptyList(), null), SongItem("b", "B", null, emptyList(), null))
            val pending = CompletableDeferred<Unit>()
            val saved = mutableListOf<Pair<String, String>>()
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(first) }) { playlist, song, _ ->
                    pending.await()
                    saved += playlist.id to song.id
                    Result.success(Unit)
                }
            workflow.select(songs)
            workflow.togglePlaylist(first)
            workflow.togglePlaylist(second)
            assertTrue(saved.isEmpty())
            workflow.addSelected()
            runCurrent()
            assertTrue(workflow.busy)
            workflow.togglePlaylist(second)
            workflow.select(songs.take(1))
            workflow.addSelected()
            assertEquals(listOf(first, second), workflow.selectedPlaylists)
            pending.complete(Unit)
            runCurrent()
            assertEquals(listOf("local" to "a", "local" to "b", "remote" to "a", "remote" to "b"), saved)
            assertEquals(setOf(first.id, second.id), workflow.completedIds)
            assertEquals(PlaylistAddCompletion("Same name", 2), workflow.completion)
            val completionIndex = workflow.completionIndex
            workflow.consumeCompletion()
            assertEquals(completionIndex + 1, workflow.completionIndex)
            assertEquals(PlaylistAddCompletion("Same name", 2), workflow.completion)
            workflow.consumeCompletion()
            assertNull(workflow.completion)
            assertNull(workflow.song)
        }

    @Test
    fun failedDestinationResumesItsOwnPrefixWithoutRepeatingSuccessfulDestinations() =
        runTest {
            val first = PlaylistEntity(id = "first", name = "First")
            val second = first.copy(id = "second", name = "Second")
            val third = first.copy(id = "third", name = "Third")
            val songs = listOf(SongItem("a", "A", null, emptyList(), null), SongItem("b", "B", null, emptyList(), null))
            val saved = mutableListOf<Pair<String, String>>()
            var fail = true
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(first) }) { playlist, song, _ ->
                    if (playlist == first && song == songs[1] && fail) {
                        Result.failure(IllegalStateException())
                    } else {
                        saved += playlist.id to song.id
                        Result.success(Unit)
                    }
                }
            workflow.select(songs)
            workflow.togglePlaylist(first)
            workflow.togglePlaylist(second)
            workflow.addSelected()
            runCurrent()
            assertEquals(setOf(first.id), workflow.failedIds)
            assertEquals(setOf(second.id), workflow.completedIds)
            assertEquals(listOf(songs[1]), workflow.songs)
            workflow.togglePlaylist(first)
            workflow.togglePlaylist(first)
            workflow.togglePlaylist(third)
            workflow.togglePlaylist(second)
            fail = false
            workflow.addSelected()
            runCurrent()
            assertEquals(
                listOf("first" to "a", "second" to "a", "second" to "b", "first" to "b", "third" to "a", "third" to "b"),
                saved,
            )
            assertTrue(workflow.failedIds.isEmpty())
            assertFalse(workflow.failed)
            assertNull(workflow.song)
            assertEquals(PlaylistAddCompletion("Second", 2), workflow.completion)
            workflow.consumeCompletion()
            assertEquals(PlaylistAddCompletion("First", 2), workflow.completion)
        }

    @Test
    fun duplicateApprovalAndSkippingApplyOnlyToTheirDestinationAndOccurrence() =
        runTest {
            val first = PlaylistEntity(id = "first", name = "First")
            val second = first.copy(id = "second", name = "Second")
            val song = SongItem("song", "Song", null, emptyList(), null)
            val saved = mutableListOf<Pair<String, Boolean>>()
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(first) }) { playlist, _, allowDuplicate ->
                    if (!allowDuplicate) {
                        Result.failure(PlaylistSongAlreadyPresentException())
                    } else {
                        saved += playlist.id to allowDuplicate
                        Result.success(Unit)
                    }
                }
            workflow.select(listOf(song, song))
            workflow.togglePlaylist(first)
            workflow.togglePlaylist(second)
            workflow.addSelected()
            runCurrent()
            assertEquals(first, workflow.duplicate)
            workflow.confirmDuplicate()
            runCurrent()
            assertEquals(first, workflow.duplicate)
            workflow.skipDuplicate()
            runCurrent()
            assertEquals(second, workflow.duplicate)
            assertEquals(listOf(first.id to true), saved)
            workflow.skipDuplicate()
            runCurrent()
            assertEquals(second, workflow.duplicate)
            workflow.confirmDuplicate()
            runCurrent()
            assertEquals(listOf(first.id to true, second.id to true), saved)
            assertEquals(PlaylistAddCompletion("First", 1), workflow.completion)
            workflow.consumeCompletion()
            assertEquals(PlaylistAddCompletion("Second", 1), workflow.completion)
            assertNull(workflow.song)
        }

    @Test
    fun duplicateDecisionsDoNotRetryOtherFailedDestinations() =
        runTest {
            val failed = PlaylistEntity(id = "failed", name = "Failed")
            val duplicate = failed.copy(id = "duplicate", name = "Duplicate")
            val song = SongItem("song", "Song", null, emptyList(), null)
            var failures = 0
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(failed) }) { playlist, _, allowDuplicate ->
                    when {
                        playlist == failed -> {
                            failures++
                            Result.failure(IllegalStateException())
                        }

                        !allowDuplicate -> {
                            Result.failure(PlaylistSongAlreadyPresentException())
                        }

                        else -> {
                            Result.success(Unit)
                        }
                    }
                }
            workflow.select(listOf(song, song))
            workflow.togglePlaylist(failed)
            workflow.togglePlaylist(duplicate)
            workflow.addSelected()
            runCurrent()
            workflow.skipDuplicate()
            runCurrent()
            workflow.confirmDuplicate()
            runCurrent()
            assertEquals(1, failures)
            assertEquals(setOf(failed.id), workflow.failedIds)
            assertEquals(setOf(duplicate.id), workflow.completedIds)
            assertTrue(workflow.failed)
            workflow.addSelected()
            runCurrent()
            assertEquals(2, failures)
        }

    @Test
    fun creatingDestinationRetainsTheOtherSelectionsAndDeselectingDoesNotSubmit() =
        runTest {
            val existing = PlaylistEntity(id = "existing", name = "Existing")
            val removed = existing.copy(id = "removed")
            val created = existing.copy(id = "created", name = "Created")
            val song = SongItem("song", "Song", null, emptyList(), null)
            val saved = mutableListOf<String>()
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(created) }) { playlist, _, _ ->
                    saved += playlist.id
                    Result.success(Unit)
                }
            workflow.select(song)
            workflow.addSelected()
            assertFalse(workflow.busy)
            workflow.togglePlaylist(existing)
            workflow.togglePlaylist(removed)
            workflow.togglePlaylist(removed)
            workflow.showCreate()
            workflow.name = "Created"
            workflow.createPlaylist()
            runCurrent()
            assertEquals(listOf(existing.id, created.id), saved)
            assertNull(workflow.song)
            workflow.select(song)
            assertTrue(workflow.selectedPlaylists.isEmpty())
            assertTrue(workflow.completedIds.isEmpty())
            workflow.togglePlaylist(existing)
            workflow.addSelected()
            runCurrent()
            assertEquals(listOf(existing.id, created.id, existing.id), saved)
        }

    @Test
    fun failedBrowserLaunchClearsOnlyTheSessionThatAttemptedIt() {
        val errors = mutableListOf<String>()
        val workflow = MobileAccountWorkflow(error = { errors += it })
        val first = DiscordAuthSession("https://discord.com/oauth2/authorize", "metrolist-auth://discord/callback", "verifier", "first")
        val second = first.copy(state = "second")
        workflow.startDiscordLogin(first)
        workflow.startDiscordLogin(second)
        workflow.failDiscordLogin(first)
        assertEquals(second, workflow.pendingSession)
        assertTrue(workflow.loginInProgress)
        assertTrue(errors.isEmpty())
        workflow.failDiscordLogin(second)
        assertNull(workflow.pendingSession)
        assertFalse(workflow.loginInProgress)
        assertEquals(listOf("discord_error_no_browser"), errors)
    }

    @Test
    fun discordRejectsWrongStateAndStaleExchange() =
        runTest {
            val pending = CompletableDeferred<DiscordAuthResult>()
            val connected = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val login =
                MobileAccountWorkflow(
                    exchange = { _, _ -> pending.await() },
                    connect = { connected += it.accessToken },
                    error = { errors += it },
                )
            val first = DiscordAuthSession("", "metrolist://discord", "verifier", "first")
            val second = first.copy(state = "second")
            login.startDiscordLogin(first)
            assertTrue(login.handleDiscordCallback("metrolist://discord?code=secret&state=wrong"))
            assertEquals(listOf("discord_error_token_exchange_failed"), errors)
            assertEquals(first, login.pendingSession)
            login.startDiscordLogin(first)
            val callback = backgroundScope.launch { login.handleDiscordCallback("metrolist://discord?code=secret&state=first") }
            runCurrent()
            login.startDiscordLogin(second)
            assertTrue(login.handleDiscordCallback("metrolist://discord?code=old&state=first"))
            assertEquals(second, login.pendingSession)
            pending.complete(DiscordAuthResult("token", "refresh", 1L, ""))
            callback.join()
            assertTrue(connected.isEmpty())
            assertTrue(login.loginInProgress)
            login.expire(first)
            assertTrue(login.loginInProgress)
            login.expire(second)
            assertFalse(login.loginInProgress)
            assertEquals("discord_error_timeout", errors.last())
        }

    @Test
    fun discordOAuthCallbackExchangesOnceAndRejectsForeignRedirects() =
        runTest {
            val session =
                DiscordAuthSession("https://discord.com/oauth2/authorize", "metrolist-auth://discord/callback", "verifier", "state")
            val exchanged = mutableListOf<Pair<String, DiscordAuthSession>>()
            val connected = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val login =
                MobileAccountWorkflow(
                    exchange = { code, pending ->
                        exchanged += code to pending
                        DiscordAuthResult("access", "refresh", 3600, "")
                    },
                    connect = { connected += it.accessToken },
                    error = { errors += it },
                )
            login.startDiscordLogin(session)
            assertFalse(login.handleDiscordCallback("metrolist-auth://discord/callback/other?code=wrong&state=state"))
            assertTrue(login.loginInProgress)
            val callback = "metrolist-auth://discord/callback?code=authorization%20code&state=state"
            assertTrue(login.handleDiscordCallback(callback))
            assertEquals(listOf("authorization code" to session), exchanged)
            assertEquals(listOf("access"), connected)
            assertFalse(login.handleDiscordCallback(callback))
            assertNull(login.pendingSession)
            assertFalse(login.loginInProgress)
            assertTrue(errors.isEmpty())

            login.startDiscordLogin(session)
            assertTrue(login.handleDiscordCallback("metrolist-auth://discord/callback?error=access_denied&state=state"))
            assertEquals(listOf("discord_error_denied"), errors)
            assertFalse(login.loginInProgress)
            assertEquals(1, exchanged.size)
        }

    @Test
    fun cancelledVideoLookupCannotOpenPlayerOrShowFailure() =
        runTest {
            val result = CompletableDeferred<Boolean>()
            var opened = false
            val lookup = MobileVideoLookup(backgroundScope, { withContext(NonCancellable) { result.await() } }, { opened = true })
            lookup.play("video")
            runCurrent()
            lookup.cancel()
            result.complete(true)
            runCurrent()
            assertFalse(opened)
            assertNull(lookup.failedVideoId)
        }

    @Test
    fun playlistCreationUsesSelectedDestinationAndRetainsFailureForRetry() =
        runTest {
            val playlist = PlaylistEntity(id = "stored", name = "Mine", browseId = "PLremote")
            val destinations = mutableListOf<Pair<String, Boolean>>()
            var fail = true
            val workflow =
                MobilePlaylistWorkflow(
                    scope = backgroundScope,
                    canCreateRemote = { true },
                    create = { name, local ->
                        destinations += name to local
                        if (fail) Result.failure(IllegalStateException()) else Result.success(playlist)
                    },
                    add = { _, _, _ -> Result.success(Unit) },
                )
            workflow.showCreate()
            assertFalse(workflow.createLocally)
            workflow.name = " Mine "
            workflow.createPlaylist()
            runCurrent()
            assertTrue(workflow.failed)
            assertTrue(workflow.creating)
            assertEquals(" Mine ", workflow.name)
            workflow.createLocally = true
            fail = false
            workflow.createPlaylist()
            runCurrent()
            assertEquals(listOf("Mine" to false, "Mine" to true), destinations)
            assertFalse(workflow.creating)
            assertFalse(workflow.busy)
        }

    @Test
    fun playlistDuplicateFailureAndRetryStayWithSelectedSong() =
        runTest {
            val playlist = PlaylistEntity(id = "LPtest", name = "Mine")
            val song = SongItem("song", "Song", null, emptyList(), null)
            val attempts = mutableListOf<Boolean>()
            val workflow =
                MobilePlaylistWorkflow(
                    backgroundScope,
                    create = { _, _ -> Result.success(playlist) },
                    add = { _, _, allowDuplicate ->
                        attempts += allowDuplicate
                        if (!allowDuplicate) {
                            Result.failure(
                                PlaylistSongAlreadyPresentException(),
                            )
                        } else {
                            Result.failure(IllegalStateException())
                        }
                    },
                )
            workflow.select(song)
            workflow.togglePlaylist(playlist)
            workflow.addSelected()
            assertTrue(workflow.busy)
            workflow.dismissSong()
            assertEquals(song, workflow.song)
            runCurrent()
            assertEquals(playlist, workflow.duplicate)
            workflow.confirmDuplicate()
            runCurrent()
            assertNull(workflow.duplicate)
            assertTrue(workflow.failed)
            assertEquals(song, workflow.song)
            assertEquals(listOf(false, true), attempts)
        }

    @Test
    fun batchSaveRetainsQueueOrderAndDuplicateOccurrences() =
        runTest {
            val playlist = PlaylistEntity(id = "LPqueue", name = "Queue")
            val first = SongItem("first", "First", null, emptyList(), null)
            val second = first.copy(id = "second")
            val saved = mutableListOf<SongItem>()
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(playlist) }) { _, song, _ ->
                    saved += song
                    Result.success(Unit)
                }
            workflow.select(listOf(first, second, first))
            workflow.name = "Queue"
            workflow.createPlaylist()
            runCurrent()
            assertEquals(listOf(first, second, first), saved)
            assertTrue(workflow.songs.isEmpty())
            assertFalse(workflow.busy)
        }

    @Test
    fun batchRetryDoesNotRepeatAlreadySavedPrefix() =
        runTest {
            val playlist = PlaylistEntity(id = "LPqueue", name = "Queue")
            val first = SongItem("first", "First", null, emptyList(), null)
            val second = first.copy(id = "second")
            val saved = mutableListOf<String>()
            var fail = true
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(playlist) }) { _, song, _ ->
                    if (song.id == "second" && fail) {
                        Result.failure(IllegalStateException())
                    } else {
                        saved += song.id
                        Result.success(Unit)
                    }
                }
            workflow.select(listOf(first, second, first))
            workflow.togglePlaylist(playlist)
            workflow.addSelected()
            runCurrent()
            assertEquals(listOf(second, first), workflow.songs)
            assertEquals(listOf("first"), saved)
            assertTrue(workflow.failed)
            fail = false
            workflow.addSelected()
            runCurrent()
            assertEquals(listOf("first", "second", "first"), saved)
            assertEquals(PlaylistAddCompletion("Queue", 3), workflow.completion)
            assertNull(workflow.song)
            assertFalse(workflow.failed)
        }

    @Test
    fun batchDuplicateConfirmationResumesAtBlockedOccurrence() =
        runTest {
            val playlist = PlaylistEntity(id = "LPqueue", name = "Queue")
            val song = SongItem("song", "Song", null, emptyList(), null)
            val saved = mutableListOf<SongItem>()
            val workflow =
                MobilePlaylistWorkflow(backgroundScope, { _, _ -> Result.success(playlist) }) { _, target, allowDuplicate ->
                    if (target in saved && !allowDuplicate) {
                        Result.failure(PlaylistSongAlreadyPresentException())
                    } else {
                        saved += target
                        Result.success(Unit)
                    }
                }
            workflow.select(listOf(song, song, song))
            workflow.togglePlaylist(playlist)
            workflow.addSelected()
            runCurrent()
            assertEquals(listOf(song), saved)
            assertEquals(playlist, workflow.duplicate)
            workflow.confirmDuplicate()
            runCurrent()
            assertEquals(listOf(song, song), saved)
            assertEquals(playlist, workflow.duplicate)
            assertEquals(listOf(song), workflow.songs)
            assertNull(workflow.completion)
            workflow.confirmDuplicate()
            runCurrent()
            assertEquals(listOf(song, song, song), saved)
            assertEquals(PlaylistAddCompletion("Queue", 3), workflow.completion)
            assertTrue(workflow.songs.isEmpty())
        }
}

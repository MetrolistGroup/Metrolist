package com.metrolist.shared.ui.mobile

import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobilePlaylistEditorTest {
    private val playlist = PlaylistItem(id = "LPtest", title = "Local", author = null, songCount = null, thumbnail = null, isLocal = true)

    @Test
    fun renameTrimsNameAndCannotBeDismissedOrSubmittedTwiceWhileSaving() =
        runTest {
            val pending = CompletableDeferred<Result<Unit>>()
            val calls = mutableListOf<Pair<String, String>>()
            val editor =
                MobilePlaylistEditor(backgroundScope, { selected, name, _ ->
                    calls += selected.id to name
                    pending.await()
                }, { error("Unexpected delete") })
            editor.show(playlist, deleting = false)
            editor.name = "  "
            editor.confirm()
            assertFalse(editor.busy)
            editor.name = " Updated "
            editor.confirm()
            editor.confirm()
            editor.dismiss()
            editor.show(playlist.copy(id = "LPother"), deleting = true)
            runCurrent()
            assertEquals(listOf("LPtest" to "Updated"), calls)
            assertEquals(playlist, editor.playlist)
            pending.complete(Result.success(Unit))
            runCurrent()
            assertNull(editor.playlist)
            assertFalse(editor.busy)
        }

    @Test
    fun failedDeleteRetainsPlaylistAndCanBeRetried() =
        runTest {
            var attempts = 0
            val editor =
                MobilePlaylistEditor(backgroundScope, { _, _, _ -> error("Unexpected rename") }, { id ->
                    assertEquals(playlist.id, id)
                    attempts++
                    if (attempts == 1) Result.failure(IllegalStateException()) else Result.success(Unit)
                })
            editor.show(playlist, deleting = true)
            editor.confirm()
            runCurrent()
            assertTrue(editor.failed)
            assertEquals(playlist, editor.playlist)
            assertFalse(editor.busy)
            editor.confirm()
            runCurrent()
            assertNull(editor.playlist)
            assertFalse(editor.failed)
        }

    @Test
    fun ownedRemotePlaylistCanBeRenamedAndDeletedWhileAuthenticated() =
        runTest {
            var authenticated = true
            val renamed = mutableListOf<Pair<String, String>>()
            val deleted = mutableListOf<String>()
            val remote = playlist.copy(id = "PLremote", isLocal = false, isEditable = true)
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { selected, name, _ ->
                        renamed += selected.id to name
                        Result.success(Unit)
                    },
                    { id ->
                        deleted += id
                        Result.success(Unit)
                    },
                    canEditRemote = { authenticated },
                )
            editor.show(remote.copy(isEditable = false), deleting = true)
            assertNull(editor.playlist)
            editor.show(remote, deleting = false)
            editor.name = " Updated "
            editor.confirm()
            runCurrent()
            assertEquals(listOf("PLremote" to "Updated"), renamed)
            editor.show(remote, deleting = true)
            authenticated = false
            editor.confirm()
            runCurrent()
            assertTrue(editor.failed)
            assertTrue(deleted.isEmpty())
            authenticated = true
            editor.confirm()
            runCurrent()
            assertEquals(listOf("PLremote"), deleted)
            assertNull(editor.playlist)
        }

    @Test
    fun remoteMetadataEditKeepsDescriptionAndRetriesAfterAPartialRename() =
        runTest {
            val remote = playlist.copy(id = "PLremote", isLocal = false, isEditable = true, description = "Original description")
            val calls = mutableListOf<Triple<PlaylistItem, String, String>>()
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { selected, name, description ->
                        calls += Triple(selected, name, description)
                        if (calls.size == 1) {
                            Result.failure(PlaylistPartiallyUpdatedException(name, IllegalStateException()))
                        } else {
                            Result.success(Unit)
                        }
                    },
                    { error("Unexpected delete") },
                    canEditRemote = { true },
                )
            editor.show(remote, deleting = false)
            assertEquals("Original description", editor.description)
            editor.name = " Updated "
            editor.description = " New description "
            editor.confirm()
            runCurrent()
            assertTrue(editor.failed)
            assertEquals("Updated", editor.playlist?.title)
            assertEquals("Original description", editor.playlist?.description)
            assertEquals(" New description ", editor.description)
            editor.confirm()
            runCurrent()
            assertEquals(Triple(remote, "Updated", "New description"), calls.first())
            assertEquals(Triple(remote.copy(title = "Updated"), "Updated", "New description"), calls.last())
            assertNull(editor.playlist)
            assertFalse(editor.failed)
        }

    @Test
    fun remoteDescriptionCanBeClearedWithoutChangingTheName() =
        runTest {
            val remote = playlist.copy(id = "PLremote", isLocal = false, isEditable = true, description = "Original description")
            val calls = mutableListOf<Triple<PlaylistItem, String, String>>()
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { selected, name, description ->
                        calls += Triple(selected, name, description)
                        Result.success(Unit)
                    },
                    { error("Unexpected delete") },
                    canEditRemote = { true },
                )
            editor.show(remote, deleting = false)
            editor.description = "  "
            editor.confirm()
            runCurrent()
            assertEquals(listOf(Triple(remote, remote.title, "")), calls)
            assertNull(editor.playlist)
        }

    @Test
    fun remotePlaylistsAreNotTreatedAsLocalEdits() =
        runTest {
            val editor = MobilePlaylistEditor(backgroundScope, { _, _, _ -> error("Unexpected rename") }, { error("Unexpected delete") })
            editor.show(playlist.copy(isLocal = false), deleting = true)
            editor.confirm()
            assertNull(editor.playlist)
            assertFalse(editor.busy)
        }

    @Test
    fun chosenCoverIsStagedUntilSaveAndDiscardedOnDismiss() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3)
            val saved = mutableListOf<Pair<String, ByteArray?>>()
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { _, _, _ -> Result.success(Unit) },
                    { error("Unexpected delete") },
                    pickCover = { bytes },
                    saveCover = { selected, image ->
                        saved += selected.id to image
                        Result.success(Unit)
                    },
                )
            editor.show(playlist, deleting = false)
            editor.chooseCover()
            runCurrent()
            assertContentEquals(bytes, editor.coverBytes)
            assertTrue(saved.isEmpty())
            editor.dismiss()
            assertNull(editor.coverBytes)
            editor.show(playlist, deleting = false)
            editor.confirm()
            runCurrent()
            assertTrue(saved.isEmpty())
            editor.show(playlist, deleting = false)
            editor.chooseCover()
            runCurrent()
            editor.confirm()
            runCurrent()
            assertEquals(playlist.id, saved.single().first)
            assertContentEquals(bytes, saved.single().second)
            assertNull(editor.playlist)
        }

    @Test
    fun pickerCancellationRetainsStagedCoverAndBlocksDuplicateRequests() =
        runTest {
            val bytes = byteArrayOf(1)
            val pending = CompletableDeferred<ByteArray?>()
            var picks = 0
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { _, _, _ -> error("Unexpected rename") },
                    { error("Unexpected delete") },
                    pickCover = { if (++picks == 1) bytes else pending.await() },
                    saveCover = { _, _ -> error("Unexpected save") },
                )
            editor.show(playlist, deleting = false)
            editor.chooseCover()
            runCurrent()
            editor.chooseCover()
            editor.chooseCover()
            editor.confirm()
            editor.dismiss()
            editor.show(playlist.copy(id = "LPother"), deleting = false)
            runCurrent()
            assertEquals(2, picks)
            assertEquals(playlist, editor.playlist)
            assertTrue(editor.busy)
            pending.complete(null)
            runCurrent()
            assertContentEquals(bytes, editor.coverBytes)
            assertFalse(editor.busy)
            assertFalse(editor.coverFailed)
        }

    @Test
    fun failedCoverSaveRetainsSelectionForRetry() =
        runTest {
            var attempts = 0
            val bytes = byteArrayOf(1)
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { _, _, _ -> Result.success(Unit) },
                    { error("Unexpected delete") },
                    pickCover = { bytes },
                    saveCover = { selected, image ->
                        assertEquals(playlist.id, selected.id)
                        assertContentEquals(bytes, image)
                        if (++attempts == 1) Result.failure(IllegalStateException()) else Result.success(Unit)
                    },
                )
            editor.show(playlist, deleting = false)
            editor.chooseCover()
            runCurrent()
            editor.confirm()
            runCurrent()
            assertTrue(editor.coverFailed)
            assertEquals(playlist, editor.playlist)
            assertContentEquals(bytes, editor.coverBytes)
            editor.confirm()
            runCurrent()
            assertEquals(2, attempts)
            assertNull(editor.playlist)
            assertFalse(editor.coverFailed)
        }

    @Test
    fun removalIsStagedAndInvalidImageDoesNotClearIt() =
        runTest {
            val saved = mutableListOf<ByteArray?>()
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { _, _, _ -> Result.success(Unit) },
                    { error("Unexpected delete") },
                    pickCover = { byteArrayOf() },
                    saveCover = { _, bytes ->
                        saved += bytes
                        Result.success(Unit)
                    },
                )
            editor.show(playlist.copy(customThumbnail = "/old.jpg"), deleting = false)
            editor.removeCustomCover()
            assertTrue(saved.isEmpty())
            editor.chooseCover()
            runCurrent()
            assertTrue(editor.coverFailed)
            assertTrue(editor.removeCover)
            editor.confirm()
            runCurrent()
            assertEquals(1, saved.size)
            assertNull(saved.single())
            assertNull(editor.playlist)
        }

    @Test
    fun ownedRemoteCoversUsePlaylistMetadataAndRetainFailedUploadsForRetry() =
        runTest {
            val remote = PlaylistItem("VLPLowned", "Remote", null, isEditable = true)
            val bytes = byteArrayOf(1, 2)
            val saved = mutableListOf<Pair<PlaylistItem, ByteArray?>>()
            var signedIn = true
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { _, _, _ -> Result.success(Unit) },
                    { error("Unexpected delete") },
                    canEditRemote = { signedIn },
                    pickCover = { bytes },
                    saveCover = { selected, image ->
                        saved += selected to image
                        if (saved.size == 1) Result.failure(IllegalStateException()) else Result.success(Unit)
                    },
                )
            editor.show(remote, deleting = false)
            assertTrue(editor.canEditCover)
            editor.name = "Renamed"
            editor.chooseCover()
            runCurrent()
            editor.confirm()
            runCurrent()
            assertTrue(editor.coverFailed)
            assertEquals("Renamed", editor.playlist?.title)
            assertContentEquals(bytes, editor.coverBytes)
            editor.confirm()
            runCurrent()
            assertNull(editor.playlist)
            assertEquals(
                listOf(
                    remote.copy(title = "Renamed", description = ""),
                    remote.copy(title = "Renamed", description = ""),
                ),
                saved.map {
                    it.first
                },
            )
            assertTrue(saved.all { it.second.contentEquals(bytes) })
            editor.show(remote.copy(customThumbnail = "https://example.test/cover"), deleting = false)
            editor.removeCustomCover()
            editor.confirm()
            runCurrent()
            assertNull(saved.last().second)
            assertNull(editor.playlist)
            signedIn = false
            editor.show(remote, deleting = false)
            assertFalse(editor.canEditCover)
            assertNull(editor.playlist)
            signedIn = true
            editor.show(remote.copy(isEditable = false), deleting = false)
            assertFalse(editor.canEditCover)
            assertNull(editor.playlist)
        }

    @Test
    fun failedRenameDoesNotApplyCover() =
        runTest {
            var picks = 0
            val editor =
                MobilePlaylistEditor(
                    backgroundScope,
                    { _, _, _ -> Result.failure(IllegalStateException()) },
                    { error("Unexpected delete") },
                    canEditRemote = { true },
                    pickCover = {
                        picks++
                        byteArrayOf(1)
                    },
                    saveCover = { _, _ -> error("Unexpected save") },
                )
            editor.show(playlist, deleting = false)
            editor.chooseCover()
            runCurrent()
            editor.confirm()
            runCurrent()
            assertTrue(editor.failed)
            assertContentEquals(byteArrayOf(1), editor.coverBytes)
            assertEquals(1, picks)
        }
}

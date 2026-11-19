package com.metrolist.desktop.ui

import com.metrolist.desktop.ui.screens.PlaylistRenameDialogState
import com.metrolist.shared.model.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistRenameDialogStateTest {
    private val playlist = PlaylistItem("PLrename", "Original", null, isEditable = true)

    @Test
    fun waitsForSaveAndKeepsTheEditedNameAfterFailure() {
        val state = PlaylistRenameDialogState()
        state.open(playlist)
        state.name = "Edited"
        var update: ((Result<Unit>) -> Unit)? = null
        state.submit { target, name, callback ->
            assertEquals(playlist, target)
            assertEquals("Edited", name)
            update = callback
        }
        assertTrue(state.isSaving)
        assertEquals(playlist, state.playlist)
        state.submit { _, _, _ -> error("Do not submit a rename twice") }

        requireNotNull(update)(Result.failure(IllegalStateException("offline")))
        assertFalse(state.isSaving)
        assertTrue(state.failed)
        assertEquals(playlist, state.playlist)
        assertEquals("Edited", state.name)

        state.submit { target, name, callback ->
            assertEquals(playlist, target)
            assertEquals("Edited", name)
            assertFalse(state.failed)
            callback(Result.success(Unit))
        }
        assertFalse(state.isSaving)
        assertNull(state.playlist)
    }

    @Test
    fun openingAnotherPlaylistResetsThePreviousErrorAndDraft() {
        val state = PlaylistRenameDialogState()
        state.open(playlist)
        state.submit { _, _, callback -> callback(Result.failure(IllegalStateException("offline"))) }
        state.dismiss()
        val next = playlist.copy(id = "PLnext", title = "Next")
        state.open(next)
        assertEquals(next, state.playlist)
        assertEquals("Next", state.name)
        assertFalse(state.failed)
    }

    @Test
    fun dismissingPendingSaveDoesNotLetItsLateCompletionCloseAReopenedDialog() {
        val state = PlaylistRenameDialogState()
        state.open(playlist)
        var oldResult: ((Result<Unit>) -> Unit)? = null
        state.submit { _, _, callback -> oldResult = callback }
        state.dismiss()
        assertFalse(state.isSaving)
        assertNull(state.playlist)
        state.open(playlist)
        state.name = "New draft"
        requireNotNull(oldResult)(Result.success(Unit))
        assertEquals(playlist, state.playlist)
        assertEquals("New draft", state.name)
    }
}

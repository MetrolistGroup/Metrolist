package com.metrolist.shared.settings

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ContentUndoPage {
    HIDDEN_LIBRARY_ITEMS,
    HIDDEN_HOME_ITEMS,
    BLOCKED_ARTISTS,
    BLOCKED_SONGS,
    BLOCKED_USERS,
}

object ContentUndoHints {
    private val lock = SynchronizedObject()
    private val _pending = MutableStateFlow<List<ContentUndoPage>>(emptyList())
    val pending = _pending.asStateFlow()

    fun request(page: ContentUndoPage) {
        synchronized(lock) {
            val settings = Settings.shared
            val shown =
                when (page) {
                    ContentUndoPage.HIDDEN_LIBRARY_ITEMS -> settings.hiddenLibraryUndoHintShown
                    ContentUndoPage.HIDDEN_HOME_ITEMS -> settings.hiddenHomeUndoHintShown
                    ContentUndoPage.BLOCKED_ARTISTS -> settings.blockedArtistsUndoHintShown
                    ContentUndoPage.BLOCKED_SONGS -> settings.blockedSongsUndoHintShown
                    ContentUndoPage.BLOCKED_USERS -> settings.blockedUsersUndoHintShown
                }
            if (!shown && page !in _pending.value) _pending.value += page
        }
    }

    fun markShown(page: ContentUndoPage) {
        synchronized(lock) {
            val settings = Settings.shared
            when (page) {
                ContentUndoPage.HIDDEN_LIBRARY_ITEMS -> settings.hiddenLibraryUndoHintShown = true
                ContentUndoPage.HIDDEN_HOME_ITEMS -> settings.hiddenHomeUndoHintShown = true
                ContentUndoPage.BLOCKED_ARTISTS -> settings.blockedArtistsUndoHintShown = true
                ContentUndoPage.BLOCKED_SONGS -> settings.blockedSongsUndoHintShown = true
                ContentUndoPage.BLOCKED_USERS -> settings.blockedUsersUndoHintShown = true
            }
            _pending.value -= page
        }
    }
}

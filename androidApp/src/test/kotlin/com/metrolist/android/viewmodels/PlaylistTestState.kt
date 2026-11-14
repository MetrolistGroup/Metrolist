package com.metrolist.android.viewmodels

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

internal suspend fun TestScope.awaitPlaylistState(condition: () -> Boolean) {
    val observer =
        Snapshot.registerGlobalWriteObserver {
            backgroundScope.launch { Snapshot.sendApplyNotifications() }
        }
    try {
        Snapshot.sendApplyNotifications()
        snapshotFlow(condition).first { it }
    } finally {
        observer.dispose()
    }
}

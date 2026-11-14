package com.metrolist.shared.ui.menu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.SpeedDialItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.speedDialItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeedDialToggle(
    val isPinned: Boolean,
    scope: CoroutineScope,
    write: suspend () -> Unit,
) {
    val toggle: () -> Unit = {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { write() }
        }
    }
}

@Composable
fun rememberSpeedDialToggle(
    item: YTItem,
    database: MusicDatabase,
): SpeedDialToggle {
    val dao = remember(database) { database.speedDialDao() }
    val pinnedItems by remember(dao) { dao.getAll() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val isPinned = pinnedItems.any { speedDialItemId(it.id) == speedDialItemId(item.id) }
    return remember(item, isPinned, dao, scope) {
        SpeedDialToggle(isPinned, scope) {
            if (isPinned) dao.unpin(item.id) else dao.pin(SpeedDialItem.fromYTItem(item))
        }
    }
}

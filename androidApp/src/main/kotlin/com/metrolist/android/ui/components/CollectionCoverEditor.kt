package com.metrolist.android.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.utils.readPlaylistCover
import com.metrolist.shared.playlist.storeCollectionCover
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.CollectionCoverDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CollectionCoverEditor(
    type: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(type) { mutableStateOf(false) }
    var failed by remember(type) { mutableStateOf(false) }
    val directory = remember { File(context.filesDir, "playlist_covers") }

    suspend fun save(read: (() -> ByteArray)?) {
        busy = true
        failed = false
        try {
            storeCollectionCover(type, directory, read)
            onDismiss()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            busy = false
        }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                scope.launch { save { requireNotNull(context.contentResolver.openInputStream(uri)).use(::readPlaylistCover) } }
            }
        }
    CollectionCoverDialog(
        hasCover = Settings.shared.collectionCovers[type] != null,
        busy = busy,
        failed = failed,
        onChoose = { picker.launch("image/*") },
        onRemove = { scope.launch { save(null) } },
        onDismiss = onDismiss,
    )
}

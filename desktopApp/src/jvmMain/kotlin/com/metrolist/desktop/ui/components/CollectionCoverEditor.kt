package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.desktop.utils.playlistCoverFilenameFilter
import com.metrolist.desktop.utils.readPlaylistCover
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.edit_playlist_cover
import com.metrolist.shared.playlist.storeCollectionCover
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.CollectionCoverDialog
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun CollectionCoverEditor(
    type: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(type) { mutableStateOf(false) }
    var failed by remember(type) { mutableStateOf(false) }
    val directory = remember { File(XdgUtils.getXdgDataHome(), "metrolist/playlist_covers") }
    val title = stringResource(Res.string.edit_playlist_cover)

    fun save(source: File?) {
        scope.launch {
            busy = true
            failed = false
            try {
                storeCollectionCover(type, directory, source?.let { { readPlaylistCover(it) } })
                onDismiss()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
            }
        }
    }

    CollectionCoverDialog(
        hasCover = Settings.shared.collectionCovers[type] != null,
        busy = busy,
        failed = failed,
        onChoose = {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { filenameFilter = playlistCoverFilenameFilter }
            dialog.isVisible = true
            val chosen = dialog.directory?.let { dir -> dialog.file?.let { File(dir, it) } }
            dialog.dispose()
            if (chosen != null) save(chosen)
        },
        onRemove = { save(null) },
        onDismiss = onDismiss,
    )
}

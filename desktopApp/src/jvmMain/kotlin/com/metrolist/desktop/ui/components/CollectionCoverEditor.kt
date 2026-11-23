package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.desktop.utils.pickDesktopFile
import com.metrolist.desktop.utils.playlistCoverExtensions
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
            scope.launch {
                val file =
                    try {
                        pickDesktopFile(title, playlistCoverExtensions.toList())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed = true
                        return@launch
                    }
                file?.let(::save)
            }
        },
        onRemove = { save(null) },
        onDismiss = onDismiss,
    )
}

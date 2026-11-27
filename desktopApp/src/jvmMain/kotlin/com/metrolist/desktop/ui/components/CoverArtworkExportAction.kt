package com.metrolist.desktop.ui.components

import androidx.compose.runtime.Composable
import coil3.compose.LocalPlatformContext
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.utils.DesktopImageLoader
import com.metrolist.desktop.utils.saveDesktopFile
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cover_artwork_save_failed
import com.metrolist.shared.generated.resources.cover_artwork_saved
import com.metrolist.shared.generated.resources.save_cover_artwork
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.coverArtworkFilename
import com.metrolist.shared.utils.loadCoverArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Composable
internal fun coverArtworkExportAction(
    url: String?,
    title: String,
    onDismiss: () -> Unit,
): PlayerMenuAction? {
    if (url.isNullOrBlank()) return null
    val context = LocalPlatformContext.current
    val label = stringResource(Res.string.save_cover_artwork)
    val saved = stringResource(Res.string.cover_artwork_saved)
    val failed = stringResource(Res.string.cover_artwork_save_failed)
    return PlayerMenuAction(MetrolistIcons.Outlined.Image, label) {
        onDismiss()
        AppState.scope.launch {
            try {
                val artwork =
                    withContext(Dispatchers.IO) {
                        checkNotNull(loadCoverArtwork(context, DesktopImageLoader.create(context), url))
                    }
                val name = coverArtworkFilename(title, label)
                val destination = saveDesktopFile(label, name, artwork.format.extension)?.toPath() ?: return@launch
                withContext(Dispatchers.IO) {
                    val temporary = Files.createTempFile(destination.toAbsolutePath().parent, ".metrolist-artwork-", ".tmp")
                    try {
                        Files.write(temporary, artwork.bytes)
                        ensureActive()
                        try {
                            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(temporary, destination, REPLACE_EXISTING)
                        }
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
                AppState.snackbarHostState.showSnackbar(saved)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AppState.snackbarHostState.showSnackbar(failed)
            }
        }
    }
}

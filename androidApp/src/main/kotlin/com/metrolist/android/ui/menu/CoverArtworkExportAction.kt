package com.metrolist.android.ui.menu

import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cover_artwork_save_failed
import com.metrolist.shared.generated.resources.cover_artwork_saved
import com.metrolist.shared.generated.resources.save_cover_artwork
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.CoverArtwork
import com.metrolist.shared.utils.CoverArtworkFormat
import com.metrolist.shared.utils.coverArtworkFilename
import com.metrolist.shared.utils.loadCoverArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberCoverArtworkExportAction(
    url: String?,
    title: String,
    onDismiss: () -> Unit,
): PlayerMenuAction? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val label = stringResource(Res.string.save_cover_artwork)
    val saved = stringResource(Res.string.cover_artwork_saved)
    val failed = stringResource(Res.string.cover_artwork_save_failed)
    var exporting by remember { mutableStateOf(false) }
    var pendingArtwork by remember { mutableStateOf<CoverArtwork?>(null) }
    val launchers =
        CoverArtworkFormat.entries.associateWith { format ->
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
                val artwork = pendingArtwork
                pendingArtwork = null
                if (uri == null || artwork == null) {
                    exporting = false
                } else {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val resolver = context.contentResolver
                                try {
                                    checkNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(artwork.bytes) }
                                    ensureActive()
                                } catch (error: Exception) {
                                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                                    throw error
                                }
                            }
                            Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            exporting = false
                        }
                    }
                }
            }
        }
    if (url.isNullOrBlank()) return null
    return PlayerMenuAction(MetrolistIcons.Outlined.Image, label, enabled = !exporting, isLoading = exporting) {
        if (!exporting) {
            exporting = true
            scope.launch {
                try {
                    val artwork = checkNotNull(loadCoverArtwork(context, context.imageLoader, url))
                    val name = coverArtworkFilename(title, label)
                    pendingArtwork = artwork
                    launchers.getValue(artwork.format).launch("$name.${artwork.format.extension}")
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    pendingArtwork = null
                    exporting = false
                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

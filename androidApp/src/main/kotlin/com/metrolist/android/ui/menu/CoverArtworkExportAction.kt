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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cover_artwork_save_failed
import com.metrolist.shared.generated.resources.cover_artwork_saved
import com.metrolist.shared.generated.resources.save_cover_artwork
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.utils.CoverArtworkFormat
import com.metrolist.shared.utils.coverArtworkFilename
import com.metrolist.shared.utils.loadCoverArtwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.io.File

internal class CoverArtworkExporter(
    val exporting: Boolean,
    val start: (String, String) -> Unit,
)

internal val LocalCoverArtworkExporter =
    staticCompositionLocalOf<CoverArtworkExporter> {
        error("Cover artwork exporter was not provided")
    }

@Composable
internal fun rememberCoverArtworkExporter(): CoverArtworkExporter {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val label = stringResource(Res.string.save_cover_artwork)
    val saved = stringResource(Res.string.cover_artwork_saved)
    val failed = stringResource(Res.string.cover_artwork_save_failed)
    var exporting by remember { mutableStateOf(false) }
    // Only the private cache filename goes into saved instance state.
    var pendingFilename by rememberSaveable { mutableStateOf<String?>(null) }
    val launchers =
        CoverArtworkFormat.entries.associateWith { format ->
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri ->
                val stagedFile = pendingFilename?.let { File(context.cacheDir, it) }
                exporting = true
                pendingFilename = null
                scope.launch {
                    try {
                        if (uri == null) return@launch
                        withContext(Dispatchers.IO) {
                            val resolver = context.contentResolver
                            try {
                                checkNotNull(stagedFile).inputStream().use { input ->
                                    checkNotNull(resolver.openOutputStream(uri, "wt")).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                ensureActive()
                            } catch (error: Exception) {
                                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                                throw error
                            }
                        }
                        Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                    } finally {
                        try {
                            withContext(NonCancellable + Dispatchers.IO) { stagedFile?.delete() }
                        } finally {
                            exporting = false
                        }
                    }
                }
            }
        }
    return CoverArtworkExporter(exporting || pendingFilename != null) { url, title ->
        if (!exporting && pendingFilename == null) {
            exporting = true
            scope.launch {
                var stagedFile: File? = null
                var pickerLaunched = false
                try {
                    val artwork = checkNotNull(loadCoverArtwork(context, context.imageLoader, url))
                    val name = coverArtworkFilename(title, label)
                    withContext(Dispatchers.IO) {
                        val file = File.createTempFile("cover-artwork-", ".tmp", context.cacheDir)
                        stagedFile = file
                        file.writeBytes(artwork.bytes)
                    }
                    pendingFilename = checkNotNull(stagedFile).name
                    launchers.getValue(artwork.format).launch("$name.${artwork.format.extension}")
                    pickerLaunched = true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                } finally {
                    if (!pickerLaunched) {
                        pendingFilename = null
                        withContext(NonCancellable + Dispatchers.IO) { stagedFile?.delete() }
                    }
                    exporting = false
                }
            }
        }
    }
}

@Composable
internal fun rememberCoverArtworkExportAction(
    url: String?,
    title: String,
    onDismiss: () -> Unit,
): PlayerMenuAction? {
    if (url.isNullOrBlank()) return null
    val exporter = LocalCoverArtworkExporter.current
    return PlayerMenuAction(
        MetrolistIcons.Outlined.Image,
        stringResource(Res.string.save_cover_artwork),
        enabled = !exporter.exporting,
    ) {
        exporter.start(url, title)
        onDismiss()
    }
}

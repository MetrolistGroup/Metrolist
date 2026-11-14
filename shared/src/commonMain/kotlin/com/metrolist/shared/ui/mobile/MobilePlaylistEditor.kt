@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.choose_from_library
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.delete_playlist_question
import com.metrolist.shared.generated.resources.description
import com.metrolist.shared.generated.resources.edit_playlist
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.playlist_cover_update_failed
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.playlist_update_failed
import com.metrolist.shared.generated.resources.remove_custom_image
import com.metrolist.shared.generated.resources.rename_playlist
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.playlist.PlaylistPartiallyUpdatedException
import com.metrolist.shared.ui.components.AlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal class MobilePlaylistEditor(
    private val scope: CoroutineScope,
    private val update: suspend (PlaylistItem, String, String) -> Result<Unit>,
    private val delete: suspend (String) -> Result<Unit>,
    private val canEditRemote: () -> Boolean = { false },
    private val pickCover: (suspend () -> ByteArray?)? = null,
    private val saveCover: (suspend (PlaylistItem, ByteArray?) -> Result<Unit>)? = null,
) {
    var playlist by mutableStateOf<PlaylistItem?>(null)
        private set
    var deleting by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
    var description by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    var coverBytes by mutableStateOf<ByteArray?>(null)
        private set
    var removeCover by mutableStateOf(false)
        private set
    var coverFailed by mutableStateOf(false)
        private set
    val canEditCover: Boolean
        get() = playlist?.let { it.isLocal || (it.canEdit && canEditRemote()) } == true && pickCover != null && saveCover != null

    fun chooseCover() {
        if (busy || deleting || !canEditCover) return
        busy = true
        coverFailed = false
        scope.launch {
            try {
                pickCover?.invoke()?.let {
                    require(it.isNotEmpty())
                    coverBytes = it
                    removeCover = false
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                coverFailed = true
            } finally {
                busy = false
            }
        }
    }

    fun removeCustomCover() {
        if (busy || deleting || !canEditCover) return
        coverBytes = null
        removeCover = true
        coverFailed = false
    }

    fun show(
        playlist: PlaylistItem,
        deleting: Boolean,
    ) {
        if (busy || (!playlist.isLocal && (!playlist.isEditable || !canEditRemote()))) return
        this.playlist = playlist
        this.deleting = deleting
        name = playlist.title
        description = playlist.description.orEmpty()
        failed = false
        coverFailed = false
        coverBytes = null
        removeCover = false
    }

    fun dismiss() {
        if (!busy) {
            playlist = null
            coverBytes = null
            removeCover = false
        }
    }

    fun confirm() {
        val selected = playlist ?: return
        if (!selected.isLocal && !canEditRemote()) {
            failed = true
            return
        }
        val requestedName = name.trim()
        val requestedDescription = description.trim()
        if (busy || (!deleting && requestedName.isEmpty())) return
        val deleteRequested = deleting
        busy = true
        failed = false
        coverFailed = false
        scope.launch {
            try {
                val result = if (deleteRequested) delete(selected.id) else update(selected, requestedName, requestedDescription)
                if (result.isFailure) {
                    (result.exceptionOrNull() as? PlaylistPartiallyUpdatedException)?.let { error ->
                        playlist = selected.copy(title = error.updatedName)
                    }
                    failed = true
                } else if (!deleteRequested && canEditCover && (coverBytes != null || removeCover)) {
                    val updated =
                        selected.copy(
                            title = requestedName,
                            description = if (selected.isLocal) selected.description else requestedDescription,
                        )
                    playlist = updated
                    val coverResult = checkNotNull(saveCover).invoke(updated, coverBytes)
                    if (coverResult.isSuccess) {
                        playlist = null
                        coverBytes = null
                    } else {
                        coverFailed = true
                    }
                } else {
                    playlist = null
                }
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun MobilePlaylistEditDialog(editor: MobilePlaylistEditor) {
    val playlist = editor.playlist ?: return
    AlertDialog(
        onDismissRequest = editor::dismiss,
        title = {
            Text(
                stringResource(
                    when {
                        editor.deleting -> Res.string.delete_playlist_question
                        editor.canEditCover || !playlist.isLocal -> Res.string.edit_playlist
                        else -> Res.string.rename_playlist
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (editor.deleting) {
                    Text(playlist.title)
                } else {
                    if (editor.canEditCover) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AsyncImage(
                                model =
                                    editor.coverBytes ?: if (editor.removeCover) null else playlist.customThumbnail ?: playlist.thumbnail,
                                contentDescription = null,
                                fallback = painterResource(Res.drawable.logo),
                                error = painterResource(Res.drawable.logo),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
                            )
                            Column {
                                TextButton(onClick = editor::chooseCover, enabled = !editor.busy, shapes = ButtonDefaults.shapes()) {
                                    Text(stringResource(Res.string.choose_from_library))
                                }
                                if (editor.coverBytes != null || (!editor.removeCover && !playlist.customThumbnail.isNullOrBlank())) {
                                    TextButton(
                                        onClick = editor::removeCustomCover,
                                        enabled = !editor.busy,
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Text(stringResource(Res.string.remove_custom_image))
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = editor.name,
                        onValueChange = { editor.name = it },
                        label = { Text(stringResource(Res.string.playlist_name)) },
                        singleLine = true,
                        enabled = !editor.busy,
                    )
                    if (!playlist.isLocal) {
                        OutlinedTextField(
                            value = editor.description,
                            onValueChange = { editor.description = it },
                            label = { Text(stringResource(Res.string.description)) },
                            enabled = !editor.busy,
                        )
                    }
                }
                if (editor.coverFailed) {
                    Text(stringResource(Res.string.playlist_cover_update_failed), color = MaterialTheme.colorScheme.error)
                }
                if (editor.failed) {
                    Text(stringResource(Res.string.playlist_update_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !editor.busy && (editor.deleting || editor.name.isNotBlank()),
                onClick = editor::confirm,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(if (editor.deleting) Res.string.delete else Res.string.save)) }
        },
        dismissButton = {
            TextButton(
                enabled = !editor.busy,
                onClick = editor::dismiss,
                shapes = ButtonDefaults.shapes(),
            ) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

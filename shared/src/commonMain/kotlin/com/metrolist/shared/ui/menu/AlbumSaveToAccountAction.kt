package com.metrolist.shared.ui.menu

import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album_save_to_account
import com.metrolist.shared.generated.resources.album_saved_to_account
import com.metrolist.shared.generated.resources.error_occurred
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.state.AlbumLikeState
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.saveBookmarkedAlbum
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.icons.MetrolistIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberAlbumSaveToAccountAction(albumId: String): PlayerMenuAction? {
    val bookmarked by remember(albumId) { AlbumLikeState.albumLiked(albumId) }.collectAsState(false)
    val repository = GlobalYouTubeRepository.instance
    val scope = rememberCoroutineScope()
    var saving by remember(albumId) { mutableStateOf(false) }
    var succeeded by remember(albumId) { mutableStateOf<Boolean?>(null) }
    val label = stringResource(Res.string.album_save_to_account)
    PopupContent(succeeded) { success ->
        AlertDialog(
            onDismissRequest = { succeeded = null },
            title = { Text(label) },
            text = { Text(stringResource(if (success) Res.string.album_saved_to_account else Res.string.error_occurred)) },
            confirmButton = {
                TextButton(onClick = { succeeded = null }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.ok))
                }
            },
        )
    }
    if (!bookmarked || !repository.canManagePlaylists()) return null
    return PlayerMenuAction(
        icon = MetrolistIcons.Outlined.CloudUpload,
        title = label,
        isLoading = saving,
        enabled = !saving,
        onClick = {
            if (!saving) {
                saving = true
                scope.launch {
                    try {
                        KoinPlatform.getKoin().get<MusicDatabase>().saveBookmarkedAlbum(albumId, repository)
                        succeeded = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        succeeded = false
                    } finally {
                        saving = false
                    }
                }
            }
        },
    )
}

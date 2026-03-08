package com.metrolist.android.ui.menu

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.blocked_artists_count
import com.metrolist.shared.generated.resources.unblock
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.ui.menu.MediaMenuSheetContent
import com.metrolist.shared.ui.menu.artistMediaMenuModel
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun ArtistMenu(
    artist: ArtistItem,
    onDismiss: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    onAddToQueue: (() -> Unit)? = null,
    onHideFromLibrary: (() -> Unit)? = null,
) {
    val unblockText = stringResource(Res.string.unblock)

    val blockedArtistText = pluralStringResource(Res.plurals.blocked_artists_count, 1, 1)
    val context = LocalContext.current
    var details by remember(artist.id) { mutableStateOf(artist) }
    LaunchedEffect(artist.id) {
        if (artist.thumbnail != null && !artist.description.isNullOrBlank()) return@LaunchedEffect
        val page =
            try {
                GlobalYouTubeRepository.instance.getArtist(artist.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@LaunchedEffect
            }
        page
            .sections["header"]
            ?.filterIsInstance<ArtistItem>()
            ?.firstOrNull()
            ?.takeIf { it.id == artist.id }
            ?.let {
                details =
                    it.copy(
                        title = it.title.ifBlank { artist.title },
                        thumbnail = artist.thumbnail ?: it.thumbnail,
                        subscribers = it.subscribers ?: artist.subscribers,
                        description = it.description ?: artist.description,
                    )
            }
    }
    val pin = rememberSpeedDialToggle(artist)
    val isBlocked = BlockedContent.isArtistBlocked(artist.id)
    val model =
        artistMediaMenuModel(
            artist = details,
            onDismiss = onDismiss,
            isPinned = pin.isPinned,
            isBlocked = isBlocked,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onShare = onShare,
            onPlayNext = onPlayNext,
            onAddToQueue = onAddToQueue,
            onTogglePin = pin.toggle,
            onBlock = {
                if (isBlocked) {
                    BlockedContent.unblockArtist(artist.id)
                    Toast.makeText(context, unblockText, Toast.LENGTH_SHORT).show()
                } else {
                    BlockedContent.blockArtist(id = artist.id, name = artist.title, thumbnail = artist.thumbnail)
                    Toast
                        .makeText(
                            context,
                            blockedArtistText,
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            },
            onHideFromLibrary = onHideFromLibrary,
        )
    MediaMenuBottomSheet(model = model, onDismissRequest = onDismiss) {
        MediaMenuSheetContent(model)
    }
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.amazonmusicimport

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.amazon_music_marketplace
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.playlistimport.AmazonMusicPlaylistImporter
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.stringResource

@Composable
fun AmazonMusicMarketplacePicker(
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.amazon_music_marketplace)) },
        text = {
            DragScrollableColumn {
                items(AmazonMusicPlaylistImporter.marketplaceHosts.toList()) { host ->
                    TextButton(
                        onClick = { onSelect(host) },
                        modifier = Modifier.fillMaxWidth(),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(host) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) } },
    )
}

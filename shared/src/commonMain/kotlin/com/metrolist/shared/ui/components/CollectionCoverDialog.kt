package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.choose_cover_image
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.edit_playlist_cover
import com.metrolist.shared.generated.resources.playlist_cover_update_failed
import com.metrolist.shared.generated.resources.remove_custom_image
import com.metrolist.shared.ui.theme.MdSpacing
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionCoverDialog(
    hasCover: Boolean,
    busy: Boolean,
    failed: Boolean,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(Res.string.edit_playlist_cover)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdSpacing.xs)) {
                TextButton(
                    onClick = onChoose,
                    enabled = !busy,
                    modifier = Modifier.tvFocusHighlight(CircleShape),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.choose_cover_image))
                }
                if (hasCover) {
                    TextButton(
                        onClick = onRemove,
                        enabled = !busy,
                        modifier = Modifier.tvFocusHighlight(CircleShape),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(Res.string.remove_custom_image))
                    }
                }
                if (failed) {
                    Text(
                        stringResource(Res.string.playlist_cover_update_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

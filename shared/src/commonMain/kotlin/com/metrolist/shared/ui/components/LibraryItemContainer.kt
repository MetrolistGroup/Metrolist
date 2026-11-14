@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.ThumbnailCornerRadius
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.theme.gridCellInset
import com.metrolist.shared.ui.theme.gridCellShape
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryItemContainer(
    isGridView: Boolean,
    onMenuClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = if (isGridView) gridCellShape() else RoundedCornerShape(ThumbnailCornerRadius)
    Row(
        Modifier
            .clip(shape)
            .onSecondaryClick(onMenuClick)
            .then(modifier)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).then(if (isGridView) Modifier.padding(gridCellInset()) else Modifier)) { content() }
        if (!isGridView && onMenuClick != null) {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.width(60.dp).height(48.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_options))
            }
        }
    }
}

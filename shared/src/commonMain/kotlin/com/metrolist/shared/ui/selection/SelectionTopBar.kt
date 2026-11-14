@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.selection

import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.deselect_all
import com.metrolist.shared.generated.resources.menu
import com.metrolist.shared.generated.resources.n_selected
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The one selection top bar used by every screen: close (X), "N selected", a select-all
 * checkbox and the three-dots button that opens the screen's selection menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    moreEnabled: Boolean = count > 0,
    showSelectAll: Boolean = true,
    showClose: Boolean = true,
) {
    val selectedLabel = pluralStringResource(Res.plurals.n_selected, count, count)
    val selectAllChecked = allSelected && count > 0
    val selectAllLabel = stringResource(if (selectAllChecked) Res.string.deselect_all else Res.string.select_all)
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = selectedLabel,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        navigationIcon =
            if (showClose) {
                {
                    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Filled.Close, contentDescription = stringResource(Res.string.cancel))
                    }
                }
            } else {
                {}
            },
        actions = {
            if (showSelectAll) {
                Checkbox(
                    modifier =
                        Modifier.semantics(mergeDescendants = true) {
                            contentDescription = selectAllLabel
                        },
                    checked = selectAllChecked,
                    onCheckedChange = { onSelectAll() },
                )
            }
            IconButton(
                modifier = Modifier.navigationPopupSource(),
                onClick = onMore,
                enabled = moreEnabled,
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.menu))
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                // Same colour as the screen background so the bar blends in instead of standing out.
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

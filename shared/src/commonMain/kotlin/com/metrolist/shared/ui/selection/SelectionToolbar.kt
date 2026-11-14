@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.selection

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.deselect_all
import com.metrolist.shared.generated.resources.more_actions
import com.metrolist.shared.generated.resources.n_selected
import com.metrolist.shared.generated.resources.select_all
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.navigation.navigationPopupSource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun SelectionToolbarLayout(
    visible: Boolean,
    toolbar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth(), content = content)
        SelectionToolbarVisibility(visible, bottomPadding, toolbar)
    }
}

@Composable
fun SelectionToolbarVisibility(
    visible: Boolean,
    bottomPadding: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec(), expandFrom = Alignment.Bottom) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit =
            shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec(), shrinkTowards = Alignment.Bottom) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionToolbar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    primaryActions: List<PlayerMenuAction>,
    overflowActions: List<PlayerMenuAction> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    val toggleLabel = stringResource(if (allSelected) Res.string.deselect_all else Res.string.select_all)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Filled.Close, stringResource(Res.string.close))
                }
                Text(
                    pluralStringResource(Res.plurals.n_selected, count, count),
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Checkbox(
                    checked = allSelected && count > 0,
                    onCheckedChange = { onSelectAll() },
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                )
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val actions = splitSelectionActions(primaryActions, overflowActions, ((maxWidth - 16.dp) / 48.dp).toInt())
                FlexibleBottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                    windowInsets = WindowInsets(0),
                ) {
                    actions.primary.forEach { action ->
                        IconButton(
                            onClick = action.onClick,
                            enabled = count > 0 && action.enabled,
                            modifier = Modifier.size(48.dp).navigationPopupSource(),
                            shapes = IconButtonDefaults.shapes(),
                        ) { Icon(action.icon, action.title) }
                    }
                    if (actions.overflow.isNotEmpty()) {
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                enabled = count > 0,
                                modifier = Modifier.size(48.dp).navigationPopupSource(),
                                shapes = IconButtonDefaults.shapes(),
                            ) { Icon(MetrolistIcons.Default.MoreVert, stringResource(Res.string.more_actions)) }
                            ExpressiveSelectionMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                options = actions.overflow,
                                selectedOption = null,
                                onOptionSelected = { it.onClick() },
                                optionLabel = { it.title },
                                optionIcon = { Icon(it.icon, null) },
                                optionEnabled = { count > 0 && it.enabled },
                            )
                        }
                    }
                }
            }
        }
    }
}

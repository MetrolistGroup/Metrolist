/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.constants.BottomPlayerHeight
import com.metrolist.desktop.ui.layouts.AccountMenu
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cd_account_menu
import com.metrolist.shared.generated.resources.refresh_home
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_ellipsis
import com.metrolist.shared.generated.resources.search_music
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import org.jetbrains.compose.resources.stringResource

@Composable
fun TopBarWithPlayer(
    colorScheme: ColorScheme,
    searchText: String,
    onSearchChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    onClose: () -> Unit,
    onMinimize: () -> Unit,
    onMaximize: () -> Unit,
    toggleWindowFullscreen: () -> Unit,
    showSearchInTopBar: Boolean = false,
    showAccountPanel: Boolean = false,
    onShowAccountPanel: () -> Unit = {},
    onDismissAccountPanel: () -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(BottomPlayerHeight + 4.dp),
        color = colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onRefresh?.let { refresh ->
                    IconButton(onClick = refresh, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            MetrolistIcons.Outlined.Refresh,
                            contentDescription = stringResource(Res.string.refresh_home),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (showSearchInTopBar) {
                    TopBarSearchField(
                        searchText = searchText,
                        onSearchChange = onSearchChange,
                        colorScheme = colorScheme,
                        modifier = Modifier.width(320.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Box {
                    IconButton(
                        onClick = onShowAccountPanel,
                        modifier = Modifier.size(48.dp).navigationPopupSource(),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            MetrolistIcons.Outlined.MoreVert,
                            contentDescription = stringResource(Res.string.cd_account_menu),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    if (showAccountPanel) {
                        AccountMenu(
                            onDismiss = onDismissAccountPanel,
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
            }
            StandardBottomPlayer(colorScheme, toggleWindowFullscreen)
        }
    }
}

@Composable
private fun TopBarSearchField(
    searchText: String,
    onSearchChange: (String) -> Unit,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
) {
    val textFieldState = rememberTextFieldState(initialText = searchText)
    val latestSearchText by rememberUpdatedState(searchText)
    val latestOnSearchChange by rememberUpdatedState(onSearchChange)
    LaunchedEffect(searchText) {
        if (textFieldState.text.toString() != searchText) {
            textFieldState.setTextAndPlaceCursorAtEnd(searchText)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != latestSearchText) {
                latestOnSearchChange(text)
            }
        }
    }
    Surface(
        modifier = modifier.width(280.dp).height(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                MetrolistIcons.Outlined.Search,
                contentDescription = stringResource(Res.string.search),
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            val searchMusicDesc = stringResource(Res.string.search_music)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (searchText.isEmpty()) {
                    Text(
                        stringResource(Res.string.search_ellipsis),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    state = textFieldState,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colorScheme.onSurface),
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = searchMusicDesc },
                    lineLimits = TextFieldLineLimits.SingleLine,
                    cursorBrush = SolidColor(colorScheme.primary),
                    decorator = { innerTextField -> innerTextField() },
                )
            }
        }
    }
}

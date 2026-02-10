/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.utils.isScrollingUp

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    onRecognitionClick: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible && lazyListState.isScrollingUp(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier =
        Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            if (onRecognitionClick != null) {
                SmallFloatingActionButton(
                    onClick = onRecognitionClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = stringResource(R.string.recognize_music),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            FloatingActionButton(
                onClick = onClick,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyGridState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    onRecognitionClick: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible && lazyListState.isScrollingUp(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier =
        Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            if (onRecognitionClick != null) {
                SmallFloatingActionButton(
                    onClick = onRecognitionClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = stringResource(R.string.recognize_music),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            FloatingActionButton(
                onClick = onClick,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                )
            }
        }
    }
}

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    scrollState: ScrollState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    onRecognitionClick: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible && scrollState.isScrollingUp(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier =
        Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(16.dp)
        ) {
            if (onRecognitionClick != null) {
                SmallFloatingActionButton(
                    onClick = onRecognitionClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.mic),
                        contentDescription = stringResource(R.string.recognize_music),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            FloatingActionButton(
                onClick = onClick,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                )
            }
        }
    }
}

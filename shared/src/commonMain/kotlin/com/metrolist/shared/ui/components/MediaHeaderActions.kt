@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.clear_search
import com.metrolist.shared.generated.resources.search_songs
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.retainPlaybackKeyFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

@Composable
fun InlineSearchRow(
    isSearchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onSearchToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    aboveOrderControls: (@Composable () -> Unit)? = null,
    orderControls: (@Composable () -> Unit)? = null,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = isSearchOpen
    val searchButtonFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val isTv = LocalIsTv.current
    var wasSearchOpen by remember { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isSearchOpen) {
        if (wasSearchOpen && !isSearchOpen) {
            restoreFocus = isTv || inputModeManager.inputMode == InputMode.Keyboard
            focusManager.clearFocus()
        }
        wasSearchOpen = isSearchOpen
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState, restoreFocus) {
        if (visibility.isIdle && !visibility.currentState && restoreFocus) {
            if (onSearchToggle != null) searchButtonFocus.requestFocus()
            restoreFocus = false
        }
    }
    Box(modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            visible = !isSearchOpen,
            enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Column {
                aboveOrderControls?.invoke()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) { orderControls?.invoke() }
                    if (onSearchToggle != null) {
                        FilledTonalIconButton(
                            onClick = onSearchToggle,
                            modifier = Modifier.focusRequester(searchButtonFocus).tvFocusHighlight(CircleShape),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(MetrolistIcons.Default.Search, stringResource(Res.string.search_songs))
                        }
                    }
                }
            }
        }
        val buttonWidth = with(LocalDensity.current) { 40.dp.roundToPx() }
        AnimatedVisibility(
            visibleState = visibility,
            enter =
                expandHorizontally(
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                    expandFrom = Alignment.End,
                    initialWidth = { buttonWidth },
                ),
            exit =
                shrinkHorizontally(
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                    shrinkTowards = Alignment.End,
                    targetWidth = { buttonWidth },
                ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.clip(RoundedCornerShape(100.dp)),
        ) {
            val focusRequester = remember { FocusRequester() }
            val bringIntoViewRequester = remember { BringIntoViewRequester() }
            val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            LaunchedEffect(isSearchOpen) {
                if (isSearchOpen) focusRequester.requestFocus()
            }
            LaunchedEffect(visibility.isIdle, visibility.currentState, imeBottom) {
                if (visibility.isIdle && visibility.currentState) bringIntoViewRequester.bringIntoView()
            }
            CompactSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(Res.string.search_songs),
                focusRequester = focusRequester,
                onClose = onClose,
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoViewRequester),
            )
        }
    }
}

@Composable
fun ExpandableSearchBar(
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(Res.string.search_songs),
    maxWidth: Dp? = null,
) {
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(animationSpec = tween(200)) + expandVertically(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        val focusRequester = remember { FocusRequester() }
        val bringIntoViewRequester = remember { BringIntoViewRequester() }
        val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(visible) {
            if (visible) {
                focusRequester.requestFocus()
            }
        }
        LaunchedEffect(visibility.isIdle, visibility.currentState, imeBottom) {
            if (visibility.isIdle && visibility.currentState) {
                bringIntoViewRequester.bringIntoView()
            }
        }
        CompactSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = placeholder,
            focusRequester = focusRequester,
            onClose = onClose,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
                    .bringIntoViewRequester(bringIntoViewRequester),
        )
    }
}

@Composable
fun CompactSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onClose: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val textFieldState = rememberTextFieldState(initialText = query)
    val focusManager = LocalFocusManager.current
    val latestValue by rememberUpdatedState(query)
    val latestOnValueChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != latestValue) latestOnValueChange(text)
        }
    }

    Surface(
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MetrolistIcons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        modifier = Modifier.clearAndSetSemantics {},
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    state = textFieldState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = placeholder }
                            .retainPlaybackKeyFocus()
                            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    onKeyboardAction = {
                        onSearch?.invoke()
                        focusManager.clearFocus()
                    },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Default.Clear,
                        contentDescription = stringResource(Res.string.clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            } else if (onClose != null) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp).pointerHoverIcon(PointerIcon.Hand),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Default.Close,
                        contentDescription = stringResource(Res.string.clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

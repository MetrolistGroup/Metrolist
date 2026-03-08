package com.metrolist.shared.ui.menu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.ui.components.AutoPlaylistThumbnail
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.PlayerMenuActionIcon
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.components.isAutoPlaylistIcon
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.isDesktop
import org.jetbrains.compose.resources.stringResource

@Stable
class CursorContextMenuState {
    var expanded by mutableStateOf(false)
        private set
    var offset by mutableStateOf(DpOffset.Zero)
        private set

    var isMouseInput by mutableStateOf(false)
        private set

    fun open() {
        expanded = true
    }

    fun openAnchored() {
        offset = DpOffset.Zero
        expanded = true
    }

    fun dismiss() {
        expanded = false
    }

    internal fun updateCursor(
        position: Offset,
        density: Density,
        pointerType: PointerType,
        secondary: Boolean = true,
    ) {
        isMouseInput = pointerType == PointerType.Mouse
        offset = if (isDesktop && !secondary) DpOffset.Zero else with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
    }
}

@Composable
fun rememberCursorContextMenuState(): CursorContextMenuState = remember { CursorContextMenuState() }

val LocalContextMenuAnchor = staticCompositionLocalOf { CursorContextMenuState() }

fun Modifier.contextMenuAnchor(state: CursorContextMenuState): Modifier =
    composed {
        val density = LocalDensity.current
        var origin by remember { mutableStateOf(Offset.Zero) }
        onGloballyPositioned { origin = it.localToWindow(Offset.Zero) }
            .onPreviewKeyEvent { event ->
                if (isDesktop && event.type == KeyEventType.KeyDown) {
                    state.updateCursor(Offset.Zero, density, PointerType.Unknown)
                }
                false
            }.pointerInput(state, density, origin) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            event.changes.firstOrNull()?.let {
                                state.updateCursor(origin + it.position, density, it.type, event.buttons.isSecondaryPressed)
                            }
                        }
                    }
                }
            }
    }

fun Modifier.cursorContextMenu(
    state: CursorContextMenuState,
    openOnSecondaryClick: Boolean = true,
): Modifier =
    // Let focused cards select their menu item before the container handles the key.
    onKeyEvent { event ->
        val opensMenu = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
        if (event.type == KeyEventType.KeyDown && opensMenu) {
            state.openAnchored()
            true
        } else {
            false
        }
    }.contextMenuAnchor(state)
        .onSecondaryClick(if (openOnSecondaryClick) state::open else null)

@Composable
fun Modifier.artworkMenuActions(
    onShowMenu: (() -> Unit)?,
    label: String = stringResource(Res.string.more_options),
): Modifier {
    if (onShowMenu == null) return this
    return onPreviewKeyEvent { event ->
        val opensMenu = event.key == Key.Menu || (event.key == Key.F10 && event.isShiftPressed)
        if (event.type == KeyEventType.KeyDown && opensMenu) {
            onShowMenu()
            true
        } else {
            false
        }
    }.semantics {
        customActions =
            listOf(
                CustomAccessibilityAction(label) {
                    onShowMenu()
                    true
                },
            )
    }.onSecondaryClick(onShowMenu)
}

fun Modifier.onSecondaryClick(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) {
        this
    } else {
        pointerInput(onClick) {
            awaitEachGesture {
                val down = awaitSecondaryDown()
                down.consume()
                onClick()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
    }

internal suspend fun AwaitPointerEventScope.awaitSecondaryDown(): PointerInputChange {
    // Compose's awaitFirstDown accepts only the primary mouse button on desktop.
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.buttons.isSecondaryPressed) {
            event.changes.firstOrNull { it.changedToDown() }?.let { return it }
        }
    }
}

@Composable
fun CursorDropdownMenu(
    expanded: Boolean,
    offset: DpOffset,
    onDismissRequest: () -> Unit,
    anchorOffset: DpOffset = DpOffset.Zero,
    animated: Boolean = true,
    modifier: Modifier = Modifier,
    sidePlacementWidth: Dp? = null,
    tonalElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (isDesktop) {
        DesktopDropdownMenu(expanded, offset, onDismissRequest, modifier, sidePlacementWidth, tonalElevation, content)
        return
    }
    if (offset == DpOffset.Zero && animated) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            offset = anchorOffset,
            modifier = modifier.then(Modifier.heightIn(max = 720.dp)),
            shape = ComponentShapes.MenuExteriorShape,
            content = content,
        )
        return
    }
    val transition = updateTransition(targetState = expanded, label = "MenuTransition")
    if (!transition.currentState && !transition.targetState) return

    val density = LocalDensity.current
    val cursor = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
    val anchorDelta = with(density) { IntOffset(anchorOffset.x.roundToPx(), anchorOffset.y.roundToPx()) }
    val positionProvider =
        remember(cursor, anchorDelta, offset) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    return if (offset != DpOffset.Zero) {
                        IntOffset(cursor.x.coerceIn(0, maxX), cursor.y.coerceIn(0, maxY))
                    } else {
                        val x =
                            if (layoutDirection == LayoutDirection.Ltr) {
                                anchorBounds.right - popupContentSize.width + anchorDelta.x
                            } else {
                                anchorBounds.left - anchorDelta.x
                            }
                        val y = anchorBounds.top - popupContentSize.height + anchorDelta.y
                        IntOffset(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
                    }
                }
            }
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        transition.AnimatedVisibility(
            visible = { it },
            enter =
                fadeIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)) +
                    scaleIn(initialScale = 0.85f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)) +
                    expandVertically(expandFrom = Alignment.Top, animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f)),
            exit =
                fadeOut(animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)) +
                    scaleOut(targetScale = 0.85f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)) +
                    shrinkVertically(shrinkTowards = Alignment.Top, animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f)),
        ) {
            Surface(
                shape = ComponentShapes.MenuExteriorShape,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
            ) {
                Column(
                    modifier =
                        modifier
                            .widthIn(min = 200.dp, max = 400.dp)
                            .heightIn(max = 720.dp)
                            .verticalScrollWithDrag(rememberScrollState())
                            .padding(vertical = 8.dp),
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MediaContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    anchorOffset: DpOffset = DpOffset.Zero,
    animated: Boolean = true,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String?,
    thumbnail: String?,
    fallbackIcon: ImageVector? = null,
    thumbnailModifier: Modifier = Modifier.size(52.dp),
    thumbnailAspectRatio: Float = 1f,
    isExplicit: Boolean = false,
    primaryActions: List<PlayerMenuAction> = emptyList(),
    actionGroups: List<List<PlayerMenuAction>>,
    subtitleLinks: AnnotatedString? = null,
    preferSidePlacement: Boolean = false,
    tonalElevation: Dp = 0.dp,
) {
    val menuWidth = 360.dp
    CursorDropdownMenu(
        expanded,
        offset,
        onDismiss,
        anchorOffset,
        animated,
        sidePlacementWidth = menuWidth.takeIf { preferSidePlacement },
        tonalElevation = tonalElevation,
    ) {
        Column(
            modifier = Modifier.width(menuWidth).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MediaContextMenuHeader(
                title,
                subtitle,
                thumbnail,
                fallbackIcon,
                thumbnailModifier,
                isExplicit,
                subtitleLinks,
                thumbnailAspectRatio,
            )
            ExpressiveMenuPrimaryActions(primaryActions)

            val groups = actionGroups.filter { it.isNotEmpty() }
            groups.forEachIndexed { groupIndex, actions ->
                ExpressiveMenuActionGroup(actions, groupIndex, groups.size)
            }
        }
    }
}

@Composable
fun MediaContextMenu(
    model: MediaMenuModel,
    expanded: Boolean,
    offset: DpOffset,
    onDismiss: () -> Unit,
    thumbnailModifier: Modifier = Modifier.size(52.dp),
) {
    val topActions = model.headerActions + model.primaryActions
    MediaContextMenu(
        expanded = expanded,
        offset = offset,
        onDismiss = onDismiss,
        title = model.title,
        subtitle = model.subtitle,
        thumbnail = model.thumbnail,
        fallbackIcon = model.fallbackIcon,
        thumbnailModifier = thumbnailModifier,
        thumbnailAspectRatio = model.thumbnailAspectRatio,
        isExplicit = model.isExplicit,
        primaryActions = if (topActions.size <= 3) topActions else model.primaryActions,
        actionGroups = if (topActions.size <= 3) model.actionGroups else listOf(model.headerActions) + model.actionGroups,
        subtitleLinks = model.subtitleLinks,
    )
}

@Composable
fun MediaContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    onDismiss: () -> Unit,
    model: @Composable () -> MediaMenuModel,
) {
    CursorDropdownMenu(expanded, offset, onDismiss) {
        val menu = model()
        val topActions = menu.headerActions + menu.primaryActions
        val groups =
            (
                if (topActions.size <=
                    3
                ) {
                    menu.actionGroups
                } else {
                    listOf(menu.headerActions) + menu.actionGroups
                }
            ).filter { it.isNotEmpty() }
        Column(Modifier.width(360.dp).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MediaContextMenuHeader(
                menu.title,
                menu.subtitle,
                menu.thumbnail,
                menu.fallbackIcon,
                Modifier.size(52.dp),
                menu.isExplicit,
                menu.subtitleLinks,
                menu.thumbnailAspectRatio,
            )
            ExpressiveMenuPrimaryActions(if (topActions.size <= 3) topActions else menu.primaryActions)
            groups.forEachIndexed { index, actions -> ExpressiveMenuActionGroup(actions, index, groups.size) }
        }
    }
}

@Composable
private fun MediaContextMenuHeader(
    title: String,
    subtitle: String?,
    thumbnail: String?,
    fallbackIcon: ImageVector?,
    thumbnailModifier: Modifier,
    isExplicit: Boolean,
    subtitleLinks: AnnotatedString?,
    thumbnailAspectRatio: Float = 1f,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = thumbnailModifier, contentAlignment = Alignment.Center) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(thumbnailAspectRatio)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbnail.isAutoPlaylistIcon()) {
                    AutoPlaylistThumbnail(thumbnail, Modifier.fillMaxSize())
                } else if (thumbnail != null) {
                    AsyncImage(
                        url = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else if (fallbackIcon != null) {
                    Icon(fallbackIcon, contentDescription = null, modifier = Modifier.size(28.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).basicMarquee(),
                )
                if (isExplicit && subtitle.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    MediaMenuExplicitBadge()
                }
            }
            subtitle?.takeIf(String::isNotBlank)?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subtitleLinks ?: AnnotatedString(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier =
                            Modifier.weight(1f, fill = false).then(
                                if (subtitleLinks == null) Modifier.basicMarquee() else Modifier,
                            ),
                    )
                    if (isExplicit) {
                        Spacer(Modifier.width(6.dp))
                        MediaMenuExplicitBadge()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpressiveMenuPrimaryActions(actions: List<PlayerMenuAction>) {
    val visibleActions = actions.take(3)
    if (visibleActions.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visibleActions.forEachIndexed { index, action ->
            val shape =
                when {
                    visibleActions.size == 1 -> {
                        ComponentShapes.MenuGroupShape
                    }

                    index == 0 -> {
                        RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 5.dp, bottomEnd = 5.dp)
                    }

                    index == visibleActions.lastIndex -> {
                        RoundedCornerShape(
                            topStart = 5.dp,
                            bottomStart = 5.dp,
                            topEnd = 20.dp,
                            bottomEnd = 20.dp,
                        )
                    }

                    else -> {
                        RoundedCornerShape(5.dp)
                    }
                }
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                    Modifier
                        .weight(1f)
                        .heightIn(min = 76.dp)
                        .semantics { role = Role.Button }
                        .clip(shape)
                        .then(
                            if (action.enabled) {
                                if (action.onLongClick != null) {
                                    Modifier.combinedClickable(
                                        onClick = action.onClick,
                                        onLongClick = {
                                            action.onLongClick.invoke()
                                        },
                                    )
                                } else {
                                    Modifier.clickable(onClick = action.onClick)
                                }
                            } else {
                                Modifier
                            },
                        ),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PlayerMenuActionIcon(action, Modifier.size(24.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        action.title,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveMenuActionGroup(
    actions: List<PlayerMenuAction>,
    groupIndex: Int,
    groupCount: Int,
) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(if (isDesktop) 0 else groupIndex, if (isDesktop) 1 else groupCount),
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        actions.forEachIndexed { index, action ->
            DropdownMenuItem(
                onClick = action.onClick,
                modifier = Modifier.fillMaxWidth().navigationPopupSource(),
                text = {
                    Text(action.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingText =
                    action.description?.let { description ->
                        {
                            Text(
                                description,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                shape = MenuDefaults.itemShape(index, actions.size).shape,
                leadingIcon = {
                    PlayerMenuActionIcon(action, Modifier.size(22.dp))
                },
                enabled = action.enabled,
            )
        }
    }
}

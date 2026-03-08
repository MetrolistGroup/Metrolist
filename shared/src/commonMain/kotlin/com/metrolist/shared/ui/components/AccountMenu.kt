@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account_login_with_token
import com.metrolist.shared.generated.resources.account_login_with_token_hint
import com.metrolist.shared.generated.resources.account_logout_confirm
import com.metrolist.shared.generated.resources.account_menu_license
import com.metrolist.shared.generated.resources.account_menu_title
import com.metrolist.shared.generated.resources.account_sign_in
import com.metrolist.shared.generated.resources.account_user_icon
import com.metrolist.shared.generated.resources.action_logout
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.integrations
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.menu_history
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.generated.resources.switch_youtube_channel
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.settings.SettingsNavigationRow
import com.metrolist.shared.ui.utils.segmentedGroupShape
import com.metrolist.shared.ui.utils.toPolygonShape
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.window.Popup as PlatformPopup

@Composable
fun AccountMenu(
    isSignedIn: Boolean,
    accountName: String?,
    accountSubtitle: String?,
    accountImageUrl: String?,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onTokenLogin: () -> Unit,
    onSwitchChannel: () -> Unit,
    onNavigateToStatistics: (() -> Unit)? = null,
    onNavigateToIntegrations: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: (onSuccess: () -> Unit) -> Unit,
    onNavigateToHistory: (() -> Unit)? = null,
    isLoggingOut: Boolean = false,
    showStatistics: Boolean = true,
    showIntegrations: Boolean = true,
    alignToAnchorEnd: Boolean = false,
    menuMaxWidth: Dp = 360.dp,
    initialFocusRequester: FocusRequester? = null,
    focusModifier: @Composable (Shape) -> Modifier = { Modifier },
    afterMenuContent: (@Composable ColumnScope.((() -> Unit) -> Unit) -> Unit)? = null,
) {
    val dismissal = remember { AccountMenuDismissal() }
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val popupSpacing = with(density) { 4.dp.roundToPx() }
    val windowWidth = with(density) { windowSize.width.toDp() }
    val windowHeight = with(density) { windowSize.height.toDp() }
    val menuWidth = (windowWidth - 16.dp).coerceAtMost(menuMaxWidth)
    val maxMenuHeight = (windowHeight - 64.dp).coerceIn(240.dp, 640.dp)
    val popupPositionProvider =
        remember(popupSpacing, alignToAnchorEnd) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val anchorToRight =
                        if (alignToAnchorEnd) {
                            layoutDirection == LayoutDirection.Ltr
                        } else {
                            layoutDirection == LayoutDirection.Rtl
                        }
                    val desiredX =
                        if (anchorToRight) {
                            anchorBounds.right - popupContentSize.width
                        } else {
                            anchorBounds.left
                        }
                    val belowAnchor = anchorBounds.bottom + popupSpacing
                    val aboveAnchor = anchorBounds.top - popupSpacing - popupContentSize.height
                    val desiredY =
                        if (belowAnchor + popupContentSize.height <= windowSize.height) {
                            belowAnchor
                        } else {
                            aboveAnchor
                        }
                    return IntOffset(
                        desiredX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                        desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
                    )
                }
            }
        }

    val animateDismiss = { dismissal.dismiss() }
    val menuContent: @Composable () -> Unit = {
        AccountMenuContainer(
            modifier =
                Modifier
                    .width(menuWidth)
                    .heightIn(max = maxMenuHeight),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            AccountMenuContent(
                isSignedIn = isSignedIn,
                accountName = accountName,
                accountSubtitle = accountSubtitle,
                accountImageUrl = accountImageUrl,
                onDismiss = dismissal::dismiss,
                onSignIn = onSignIn,
                onTokenLogin = onTokenLogin,
                onSwitchChannel = onSwitchChannel,
                onNavigateToStatistics = onNavigateToStatistics,
                onNavigateToIntegrations = onNavigateToIntegrations,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToHistory = onNavigateToHistory,
                onLogout = { onLogout(animateDismiss) },
                isLoggingOut = isLoggingOut,
                showStatistics = showStatistics,
                showIntegrations = showIntegrations,
                modifier = Modifier.padding(14.dp),
                initialFocusRequester = initialFocusRequester,
                focusModifier = focusModifier,
                afterMenuContent = afterMenuContent,
            )
        }
    }
    PopupVisibility(dismissal.visible, onHidden = { dismissal.finish(onDismiss) }) {
        val interactive = checkNotNull(LocalPopupSession.current).state.acceptsInput
        val dismissRequest = { if (interactive && !isLoggingOut) animateDismiss() }
        AccountMenuScrim(visible = dismissal.visible, onClick = dismissRequest)
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = dismissRequest,
            properties = PopupProperties(focusable = true),
            content = menuContent,
        )
    }
}

@Composable
internal fun AccountMenuContent(
    isSignedIn: Boolean,
    accountName: String?,
    accountSubtitle: String?,
    accountImageUrl: String?,
    onDismiss: (() -> Unit) -> Unit,
    onSignIn: () -> Unit,
    onTokenLogin: () -> Unit,
    onSwitchChannel: () -> Unit,
    onNavigateToStatistics: (() -> Unit)? = null,
    onNavigateToIntegrations: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToHistory: (() -> Unit)? = null,
    isLoggingOut: Boolean = false,
    showStatistics: Boolean = true,
    showIntegrations: Boolean = true,
    modifier: Modifier = Modifier,
    initialFocusRequester: FocusRequester? = null,
    focusModifier: @Composable (Shape) -> Modifier = { Modifier },
    afterMenuContent: (@Composable ColumnScope.((() -> Unit) -> Unit) -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    var showLogoutDialog by remember { mutableStateOf(false) }
    val showHistory = onNavigateToHistory != null
    val statisticsVisible = showStatistics && onNavigateToStatistics != null

    Column(
        modifier = Modifier.verticalScrollWithDrag(rememberScrollState()).then(modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountMenuGroup(Modifier.expressiveEntrance(0)) {
            AccountMenuHero(
                isSignedIn = isSignedIn,
                accountName = accountName,
                accountSubtitle = accountSubtitle,
                accountImageUrl = accountImageUrl,
                onClick = {
                    onDismiss(if (isSignedIn) onNavigateToSettings else onSignIn)
                },
                onAvatarClick = {
                    onDismiss(if (isSignedIn) onSwitchChannel else onSignIn)
                },
                onLogout = { showLogoutDialog = true },
                isLoggingOut = isLoggingOut,
                initialFocusRequester = initialFocusRequester,
                focusModifier = focusModifier,
            )
            val tokenInteraction = remember { MutableInteractionSource() }
            val tokenPressed by tokenInteraction.collectIsPressedAsState()
            val tokenShape = segmentedGroupShape(isFirst = false, isLast = true, pressed = tokenPressed)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .then(focusModifier(tokenShape))
                        .clip(tokenShape)
                        .background(colorScheme.surfaceContainer)
                        .clickable(interactionSource = tokenInteraction, indication = ripple(), role = Role.Button) {
                            onDismiss(onTokenLogin)
                        }.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MetrolistIcons.Outlined.Token,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(
                        if (isSignedIn) Res.string.account_login_with_token_hint else Res.string.account_login_with_token,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showHistory || statisticsVisible) {
            AccountMenuGroup(Modifier.expressiveEntrance(2)) {
                if (showHistory) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.menu_history),
                        icon = MetrolistIcons.Filled.MusicHistory,
                        iconSize = 28.dp,
                        iconTint = colorScheme.primary,
                        trailingContent = null,
                        first = true,
                        last = !statisticsVisible,
                        onClick = {
                            onDismiss(onNavigateToHistory)
                        },
                        focusModifier = focusModifier,
                    )
                }
                if (statisticsVisible) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.statistics),
                        icon = MetrolistIcons.Filled.SsidChart,
                        iconSize = 28.dp,
                        iconTint = colorScheme.primary,
                        trailingContent = null,
                        first = !showHistory,
                        last = true,
                        onClick = {
                            onDismiss(onNavigateToStatistics)
                        },
                        focusModifier = focusModifier,
                    )
                }
            }
        }

        AccountMenuGroup(Modifier.expressiveEntrance(3)) {
            if (showIntegrations) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.integrations),
                    icon = MetrolistIcons.Outlined.Api,
                    iconSize = 28.dp,
                    iconTint = colorScheme.primary,
                    trailingContent = null,
                    first = true,
                    last = false,
                    onClick = {
                        onDismiss(onNavigateToIntegrations)
                    },
                    focusModifier = focusModifier,
                )
            }
            SettingsNavigationRow(
                title = stringResource(Res.string.settings),
                icon = MetrolistIcons.Outlined.Settings,
                iconSize = 28.dp,
                iconTint = colorScheme.primary,
                trailingContent = null,
                first = !showIntegrations,
                last = true,
                onClick = {
                    onDismiss(onNavigateToSettings)
                },
                focusModifier = focusModifier,
            )
        }

        afterMenuContent?.invoke(this, onDismiss)

        Column(
            modifier = Modifier.fillMaxWidth().expressiveEntrance(4).padding(top = 4.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(Res.string.account_menu_title, BuildConfig.APP_VERSION),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.account_menu_license),
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    PopupVisibility(visible = showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(Res.string.action_logout)) },
            text = { Text(stringResource(Res.string.account_logout_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.action_logout), color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun AccountMenuScrim(
    visible: Boolean,
    onClick: () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { shown = visible }
    val alpha by animateFloatAsState(
        if (shown) 0.32f else 0f,
        MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "accountMenuScrim",
    )
    val color = MaterialTheme.colorScheme.scrim
    PlatformPopup(popupPositionProvider = FullWindowPosition, properties = PopupProperties(focusable = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(color.copy(alpha = alpha)) }
                .focusProperties { canFocus = false }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        )
    }
}

private object FullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset.Zero
}

@Composable
private fun AccountMenuGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun AccountMenuHero(
    isSignedIn: Boolean,
    accountName: String?,
    accountSubtitle: String?,
    accountImageUrl: String?,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onLogout: () -> Unit,
    isLoggingOut: Boolean,
    initialFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    focusModifier: @Composable (Shape) -> Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val heroShape = segmentedGroupShape(isFirst = true, isLast = false, pressed = false)
    val avatarShape = MaterialShapes.Cookie9Sided.toPolygonShape()
    val reveal = remember { Animatable(0f) }
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { reveal.animateTo(1f, spatial) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "accountHeroPress",
    )
    val title =
        if (isSignedIn) {
            accountName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.account_user_icon)
        } else {
            stringResource(Res.string.account_sign_in)
        }
    val subtitle = accountSubtitle?.takeIf { isSignedIn && it.isNotBlank() }
    val avatarAction = stringResource(if (isSignedIn) Res.string.switch_youtube_channel else Res.string.account_sign_in)
    val avatarDescription = stringResource(Res.string.account_user_icon)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .then(focusModifier(heroShape))
                .clip(heroShape)
                .background(colorScheme.surfaceContainer)
                .clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (isSignedIn) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .then(initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                    .then(focusModifier(avatarShape))
                    .clip(avatarShape)
                    .clickable(role = Role.Button, onClickLabel = avatarAction, onClick = onAvatarClick)
                    .semantics { contentDescription = avatarDescription }
                    .graphicsLayer {
                        val value = reveal.value
                        val scale = (0.6f + 0.4f * value) * pressScale
                        scaleX = scale
                        scaleY = scale
                        rotationZ = (1f - value) * -45f
                    },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().clip(avatarShape).background(colorScheme.primary))
            if (isSignedIn && !accountImageUrl.isNullOrBlank()) {
                AsyncImage(
                    url = accountImageUrl,
                    modifier = Modifier.size(56.dp).clip(avatarShape),
                    shape = avatarShape,
                )
            } else {
                Icon(
                    MetrolistIcons.Outlined.AccountCircle,
                    contentDescription = null,
                    tint = colorScheme.onPrimary,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSignedIn) {
            IconButton(
                onClick = onLogout,
                enabled = !isLoggingOut,
                modifier = Modifier.size(48.dp).then(focusModifier(CircleShape)).navigationPopupSource(),
                colors = IconButtonDefaults.iconButtonColors(contentColor = colorScheme.error),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    MetrolistIcons.AutoMirrored.Outlined.Logout,
                    contentDescription = stringResource(Res.string.action_logout),
                )
            }
        }
    }
}

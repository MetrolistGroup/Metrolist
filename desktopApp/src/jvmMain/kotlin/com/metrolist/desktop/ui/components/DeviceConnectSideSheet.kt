package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.metrolist.desktop.deviceconnect.DesktopDeviceConnect
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.device_connect_picker_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ConnectedDevicesContent
import com.metrolist.shared.ui.navigation.acceptsInput
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceConnectSideSheet(
    panelWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    AnimatedVisibility(
        visible = UiState.showDeviceConnectPanel,
        modifier = modifier.fillMaxHeight(),
        enter = expandHorizontally(expandFrom = Alignment.End, animationSpec = spatialSpec) + fadeIn(effectsSpec),
        exit = shrinkHorizontally(shrinkTowards = Alignment.End, animationSpec = spatialSpec) + fadeOut(effectsSpec),
    ) {
        DeviceConnectPanelSurface(panelWidth)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceConnectModalSideSheet(
    panelWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val visible = UiState.showDeviceConnectPanel
    val deviceConnectState by DesktopDeviceConnect.manager.state.collectAsState()
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val windowWidth = with(density) { windowSize.width.toDp() }
    val windowHeight = with(density) { windowSize.height.toDp() }
    val motion = rememberNavigationMotion()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val closeLabel = stringResource(Res.string.close)
    val popupPositionProvider =
        remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset = IntOffset.Zero
            }
        }

    LaunchedEffect(visible) {
        if (visible) focusRequester.requestFocus()
    }

    if (visibility.currentState || visibility.targetState) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = {
                if (deviceConnectState.pairingRequests.none { it.requiresApproval }) {
                    UiState.showDeviceConnectPanel = false
                }
            },
            properties = PopupProperties(focusable = true),
        ) {
            Box(
                modifier =
                    modifier
                        .size(windowWidth, windowHeight)
                        .semantics { isTraversalGroup = true },
            ) {
                AnimatedVisibility(
                    visibleState = visibility,
                    modifier = Modifier.fillMaxSize(),
                    enter = fadeIn(effectsSpec),
                    exit = fadeOut(effectsSpec),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = closeLabel,
                                    onClick = {
                                        if (deviceConnectState.pairingRequests.none { it.requiresApproval }) {
                                            UiState.showDeviceConnectPanel = false
                                        }
                                    },
                                ),
                    )
                }
                AnimatedVisibility(
                    visibleState = visibility,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    enter = motion.popupEnter,
                    exit = motion.popupExit,
                ) {
                    DeviceConnectPanelSurface(
                        panelWidth = panelWidth,
                        modifier = Modifier.blockInput(!this.transition.acceptsInput(EnterExitState.Visible)).focusGroup(),
                        closeButtonModifier = Modifier.focusRequester(focusRequester),
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceConnectPanelSurface(
    panelWidth: Dp,
    modifier: Modifier = Modifier,
    closeButtonModifier: Modifier = Modifier,
) {
    val manager = DesktopDeviceConnect.manager
    val state by manager.state.collectAsState()
    val syncState by manager.remote.state.collectAsState()
    val castState by PlayerState.castManager.state.collectAsState()
    val castDevices by PlayerState.castManager.devices.collectAsState()
    DisposableEffect(Settings.shared.castEnabled) {
        if (Settings.shared.castEnabled) PlayerState.startCastDiscovery()
        onDispose(PlayerState::stopCastDiscovery)
    }
    val scope = rememberCoroutineScope()
    val deviceConnectPaneTitle = stringResource(Res.string.device_connect_picker_title)

    LaunchedEffect(Unit) {
        manager.refreshPairingState()
    }

    Surface(
        modifier =
            modifier
                .width(panelWidth)
                .fillMaxHeight()
                .padding(start = 8.dp)
                .semantics { paneTitle = deviceConnectPaneTitle },
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        ConnectedDevicesContent(
            devices = castDevices,
            castState = castState,
            onDeviceClick = PlayerState::castTo,
            onDisconnectCast = PlayerState::disconnectCast,
            deviceState = state,
            syncState = syncState,
            onEnableDeviceConnect = { scope.launch { manager.enable(Unit) } },
            onTransferTo = manager.remote::transferTo,
            onClose = { UiState.showDeviceConnectPanel = false },
            onOpenIntegrations = {
                UiState.showDeviceConnectPanel = false
                UiState.showIntegrationsSettings = true
            },
            closeButtonModifier = closeButtonModifier,
        )
    }
}

internal fun deviceConnectSideSheetWidth(windowWidth: Dp): Dp =
    when {
        windowWidth < 600.dp -> 300.dp
        windowWidth < 960.dp -> 320.dp
        else -> 400.dp
    }

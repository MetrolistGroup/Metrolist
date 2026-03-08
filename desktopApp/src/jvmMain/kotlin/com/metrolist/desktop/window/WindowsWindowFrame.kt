package com.metrolist.desktop.window

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.MutableWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.FontLoadResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.zIndex
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.desktop.ui.components.DesktopTitleBarHeight
import com.metrolist.desktop.utils.AppLogger
import com.metrolist.desktop.window.jna.ComposeWindowProcedure
import com.metrolist.desktop.window.jna.User32Extend
import com.metrolist.desktop.window.jna.structure.WinUserConst.HTCLIENT
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.exit_fullscreen
import com.metrolist.shared.generated.resources.menu_minimize
import com.metrolist.shared.generated.resources.next
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.previous
import com.metrolist.shared.generated.resources.window_maximize
import com.metrolist.shared.generated.resources.window_restore
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import dev.nucleusframework.launcher.windows.StockIcon
import dev.nucleusframework.launcher.windows.TaskbarIconSource
import dev.nucleusframework.launcher.windows.ThumbnailToolbarButton
import dev.nucleusframework.launcher.windows.WindowsThumbnailToolbar
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val MinimizeIcon: ImageVector =
    ImageVector
        .Builder(
            name = "Minimize",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(3.75f, 12.5f)
                horizontalLineToRelative(16.5f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, false, 0.0f, -1.5f)
                horizontalLineTo(3.75f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, false, 0.0f, 1.5f)
                close()
            }
        }.build()
val MaximizeIcon: ImageVector =
    ImageVector
        .Builder(
            name = "Maximize",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(3.0f, 6.25f)
                curveTo(3.0f, 4.45f, 4.46f, 3.0f, 6.25f, 3.0f)
                horizontalLineToRelative(11.5f)
                curveTo(19.55f, 3.0f, 21.0f, 4.46f, 21.0f, 6.25f)
                verticalLineToRelative(11.5f)
                curveToRelative(0.0f, 1.8f, -1.46f, 3.25f, -3.25f, 3.25f)
                horizontalLineTo(6.25f)
                arcTo(3.25f, 3.25f, 0.0f, false, true, 3.0f, 17.75f)
                verticalLineTo(6.25f)
                close()
                moveTo(6.25f, 4.5f)
                curveToRelative(-0.97f, 0.0f, -1.75f, 0.78f, -1.75f, 1.75f)
                verticalLineToRelative(11.5f)
                curveToRelative(0.0f, 0.97f, 0.78f, 1.75f, 1.75f, 1.75f)
                horizontalLineToRelative(11.5f)
                curveToRelative(0.97f, 0.0f, 1.75f, -0.78f, 1.75f, -1.75f)
                verticalLineTo(6.25f)
                curveToRelative(0.0f, -0.97f, -0.78f, -1.75f, -1.75f, -1.75f)
                horizontalLineTo(6.25f)
                close()
            }
        }.build()
val RestoreIcon: ImageVector =
    ImageVector
        .Builder(
            name = "Restore",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(7.52f, 5.0f)
                horizontalLineTo(6.0f)
                curveToRelative(0.13f, -1.68f, 1.53f, -3.0f, 3.24f, -3.0f)
                horizontalLineToRelative(8.0f)
                arcTo(4.75f, 4.75f, 0.0f, false, true, 22.0f, 6.75f)
                verticalLineToRelative(8.0f)
                arcToRelative(3.25f, 3.25f, 0.0f, false, true, -3.0f, 3.24f)
                verticalLineToRelative(-1.5f)
                curveToRelative(0.85f, -0.13f, 1.5f, -0.86f, 1.5f, -1.74f)
                verticalLineToRelative(-8.0f)
                curveToRelative(0.0f, -1.8f, -1.46f, -3.25f, -3.25f, -3.25f)
                horizontalLineToRelative(-8.0f)
                curveToRelative(-0.88f, 0.0f, -1.61f, 0.65f, -1.73f, 1.5f)
                close()
                moveTo(5.25f, 6.0f)
                arcTo(3.25f, 3.25f, 0.0f, false, false, 2.0f, 9.25f)
                verticalLineToRelative(9.5f)
                curveTo(2.0f, 20.55f, 3.46f, 22.0f, 5.25f, 22.0f)
                horizontalLineToRelative(9.5f)
                curveToRelative(1.8f, 0.0f, 3.25f, -1.46f, 3.25f, -3.25f)
                verticalLineToRelative(-9.5f)
                curveTo(18.0f, 7.45f, 16.55f, 6.0f, 14.75f, 6.0f)
                horizontalLineToRelative(-9.5f)
                close()
                moveTo(3.5f, 9.25f)
                curveToRelative(0.0f, -0.97f, 0.78f, -1.75f, 1.75f, -1.75f)
                horizontalLineToRelative(9.5f)
                curveToRelative(0.97f, 0.0f, 1.75f, 0.78f, 1.75f, 1.75f)
                verticalLineToRelative(9.5f)
                curveToRelative(0.0f, 0.97f, -0.78f, 1.75f, -1.75f, 1.75f)
                horizontalLineToRelative(-9.5f)
                curveToRelative(-0.97f, 0.0f, -1.75f, -0.78f, -1.75f, -1.75f)
                verticalLineToRelative(-9.5f)
                close()
            }
        }.build()
val CloseIcon: ImageVector =
    ImageVector
        .Builder(
            name = "menu",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveToRelative(4.4f, 4.55f)
                lineToRelative(0.07f, -0.08f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, 0.98f, -0.07f)
                lineToRelative(0.08f, 0.07f)
                lineTo(12.0f, 10.94f)
                lineToRelative(6.47f, -6.47f)
                arcToRelative(0.75f, 0.75f, 0.0f, true, true, 1.06f, 1.06f)
                lineTo(13.06f, 12.0f)
                lineToRelative(6.47f, 6.47f)
                curveToRelative(0.27f, 0.27f, 0.3f, 0.68f, 0.07f, 0.98f)
                lineToRelative(-0.07f, 0.08f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -0.98f, 0.07f)
                lineToRelative(-0.08f, -0.07f)
                lineTo(12.0f, 13.06f)
                lineToRelative(-6.47f, 6.47f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -1.06f, -1.06f)
                lineTo(10.94f, 12.0f)
                lineTo(4.47f, 5.53f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -0.07f, -0.98f)
                lineToRelative(0.07f, -0.08f)
                lineToRelative(-0.07f, 0.08f)
                close()
            }
        }.build()

val ArrowLeftIcon: ImageVector =
    ImageVector
        .Builder(
            name = "arrowleft",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(10.3f, 19.72f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, 1.4f, -1.43f)
                lineTo(6.33f, 13.0f)
                horizontalLineTo(20.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, 0.0f, -2.0f)
                horizontalLineTo(6.33f)
                lineToRelative(5.37f, -5.28f)
                arcToRelative(1.0f, 1.0f, 0.0f, false, false, -1.4f, -1.42f)
                lineToRelative(-6.93f, 6.82f)
                curveToRelative(-0.5f, 0.5f, -0.5f, 1.3f, 0.0f, 1.78f)
                lineToRelative(6.92f, 6.83f)
                close()
            }
        }.build()

val ExitFullscreenIcon: ImageVector =
    ImageVector
        .Builder(
            name = "exitfullscreenicon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1f,
                stroke = null,
                strokeAlpha = 1f,
                strokeLineWidth = 1f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Bevel,
                strokeLineMiter = 1f,
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(21.78f, 2.22f)
                curveToRelative(0.26f, 0.27f, 0.29f, 0.68f, 0.07f, 0.98f)
                lineToRelative(-0.07f, 0.08f)
                lineToRelative(-6.23f, 6.23f)
                horizontalLineToRelative(5.7f)
                curveToRelative(0.38f, 0.0f, 0.7f, 0.28f, 0.74f, 0.65f)
                verticalLineToRelative(0.1f)
                curveToRelative(0.0f, 0.38f, -0.27f, 0.69f, -0.64f, 0.74f)
                horizontalLineTo(13.68f)
                arcToRelative(0.73f, 0.73f, 0.0f, false, true, -0.2f, -0.04f)
                lineToRelative(-0.1f, -0.04f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -0.38f, -0.56f)
                verticalLineToRelative(-7.6f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, 1.49f, -0.1f)
                verticalLineTo(8.43f)
                lineToRelative(6.23f, -6.22f)
                curveToRelative(0.29f, -0.3f, 0.76f, -0.3f, 1.06f, 0.0f)
                close()
                moveTo(11.0f, 13.75f)
                verticalLineToRelative(7.5f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -1.5f, 0.11f)
                verticalLineToRelative(-5.8f)
                lineToRelative(-6.22f, 6.22f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -1.13f, -0.98f)
                lineToRelative(0.07f, -0.08f)
                lineToRelative(6.22f, -6.22f)
                horizontalLineTo(2.75f)
                arcToRelative(0.75f, 0.75f, 0.0f, false, true, -0.74f, -0.64f)
                verticalLineToRelative(-0.1f)
                curveToRelative(0.0f, -0.42f, 0.33f, -0.76f, 0.74f, -0.76f)
                horizontalLineToRelative(7.56f)
                lineToRelative(0.07f, 0.01f)
                lineToRelative(0.1f, 0.03f)
                lineToRelative(0.05f, 0.02f)
                lineToRelative(0.09f, 0.04f)
                lineToRelative(0.08f, 0.06f)
                curveToRelative(0.06f, 0.04f, 0.11f, 0.09f, 0.15f, 0.14f)
                lineToRelative(0.07f, 0.1f)
                lineToRelative(0.04f, 0.1f)
                lineToRelative(0.02f, 0.07f)
                lineToRelative(0.01f, 0.06f)
                verticalLineToRelative(0.06f)
                verticalLineToRelative(-0.01f)
                lineToRelative(0.01f, 0.07f)
                close()
            }
        }.build()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrameWindowScope.WindowsWindowFrame(
    onCloseRequest: () -> Unit,
    state: WindowState,
    captionBarHeight: Dp = 32.dp,
    darkTheme: Boolean,
    lastNormalPlacement: WindowPlacement,
    content: @Composable (
        windowInset: WindowInsets,
        captionBarInset: WindowInsets,
        onMaximized: () -> Unit,
        toggleFullscreen: () -> Unit,
    ) -> Unit,
) {
    LaunchedEffect(window) {
        window.findSkiaLayer()?.transparency = true
    }

    val paddingInset = remember { MutableWindowInsets() }
    val captionHitTest = remember { WindowsCaptionHitTester() }
    val layoutHitTestOwner = rememberLayoutHitTestOwner()
    val contentPaddingInset = remember { MutableWindowInsets() }
    val captionHeight = if (PlayerState.isExpanded) captionBarHeight else DesktopTitleBarHeight

    val procedure =
        remember(window) {
            ComposeWindowProcedure(
                window = window,
                hitTest = { x, y ->
                    captionHitTest.hitTest(x, y, PlayerState.isExpanded, layoutHitTestOwner::hitTest)
                },
                onWindowInsetUpdate = { paddingInset.insets = it },
            )
        }

    val previousIcon = remember { TaskbarIconSource.FromFile(IconResources.toTempFile("previous.ico")) }
    val playIcon = remember { TaskbarIconSource.FromFile(IconResources.toTempFile("play.ico")) }
    val pauseIcon = remember { TaskbarIconSource.FromFile(IconResources.toTempFile("pause.ico")) }
    val nextIcon = remember { TaskbarIconSource.FromFile(IconResources.toTempFile("next.ico")) }

    val previousTooltip by rememberUpdatedState(stringResource(Res.string.previous))
    val playTooltip by rememberUpdatedState(stringResource(Res.string.play))
    val pauseTooltip by rememberUpdatedState(stringResource(Res.string.pause))
    val nextTooltip by rememberUpdatedState(stringResource(Res.string.next))

    val mediaControls by PlayerState.mediaControls.collectAsState()
    val taskbar =
        remember(procedure) {
            val hwnd = Pointer.nativeValue(procedure.windowHandle.pointer)

            fun nativeCall(
                operation: String,
                call: () -> Boolean,
            ): Boolean =
                runCatching(call)
                    .onFailure {
                        AppLogger.warning("WindowsTaskbar", "$operation failed", it)
                    }.getOrDefault(false)
                    .also { success ->
                        if (!success) AppLogger.warning("WindowsTaskbar", "$operation failed: ${WindowsThumbnailToolbar.lastError}")
                    }
            WindowsTaskbarController(
                executor = windowsTaskbarExecutor,
                isWindow = { !procedure.isDestroyed && hwnd != 0L && User32.INSTANCE.IsWindow(procedure.windowHandle) },
                // Handle clicks in our existing procedure so Nucleus does not subclass from its COM worker.
                register = { buttons -> nativeCall("Register") { WindowsThumbnailToolbar.setButtons(hwnd, buttons) } },
                update = { buttons -> nativeCall("Update") { WindowsThumbnailToolbar.updateButtons(hwnd, buttons) } },
                unregister = {
                    nativeCall("Unregister") { WindowsThumbnailToolbar.unregister(hwnd) }
                    Unit
                },
            )
        }

    LaunchedEffect(procedure.taskbarGeneration) {
        if (procedure.taskbarGeneration > 0) taskbar.taskbarCreated()
    }

    // TaskbarButtonCreated can arrive before the procedure is installed; register anyway once the window has settled.
    LaunchedEffect(taskbar) {
        while (!window.isShowing) delay(16.milliseconds)
        delay(2000.milliseconds)
        taskbar.taskbarCreated()
    }

    DisposableEffect(taskbar) {
        procedure.onTaskbarClick = { buttonId ->
            when (buttonId) {
                0 -> PlayerState.skipPrevious()
                1 -> if (PlayerState.isPlaying) PlayerState.pause() else PlayerState.play()
                2 -> PlayerState.skipNext()
            }
        }
        onDispose {
            procedure.onTaskbarClick = null
            taskbar.close()
        }
    }

    LaunchedEffect(
        taskbar,
        PlayerState.isPlaying,
        PlayerState.isPlaybackLoading,
        mediaControls,
        previousTooltip,
        playTooltip,
        pauseTooltip,
        nextTooltip,
    ) {
        val paused = !PlayerState.isPlaying || PlayerState.isPlaybackLoading
        taskbar.setButtons(
            listOf(
                ThumbnailToolbarButton(id = 0, tooltip = previousTooltip, icon = previousIcon, enabled = mediaControls.previous),
                ThumbnailToolbarButton(
                    id = 1,
                    tooltip = if (paused) playTooltip else pauseTooltip,
                    icon = if (paused) playIcon else pauseIcon,
                    enabled = mediaControls.playPause,
                ),
                ThumbnailToolbarButton(id = 2, tooltip = nextTooltip, icon = nextIcon, enabled = mediaControls.next),
            ),
        )
    }

    val oldStyle = remember { mutableIntStateOf(0) }
    val oldPlacement = remember { mutableStateOf(WinUser.WINDOWPLACEMENT()) }
    val playerFullscreenPlacement = remember { PlayerFullscreenPlacement() }

    fun windowsToggleFullscreen() {
        val user32 = User32.INSTANCE
        val nativeFullscreen = procedure.isFullscreen || state.placement == WindowPlacement.Fullscreen
        val currentPlacement = WinUser.WINDOWPLACEMENT()
        user32.GetWindowPlacement(procedure.windowHandle, currentPlacement)
        val transition =
            playerFullscreenPlacement.toggle(
                from =
                    WindowState(
                        placement =
                            when {
                                state.placement == WindowPlacement.Fullscreen -> WindowPlacement.Fullscreen
                                currentPlacement.showCmd == WinUser.SW_SHOWMAXIMIZED -> WindowPlacement.Maximized
                                else -> WindowPlacement.Floating
                            },
                        size = state.size,
                        position = state.position,
                    ),
                playerFullscreen = PlayerState.isFullscreen,
                fallback = lastNormalPlacement,
                nativeFullscreen = nativeFullscreen,
            )
        PlayerState.isFullscreen = transition.playerFullscreen
        if (transition.playerFullscreen) {
            procedure.isFullscreen = true
            oldStyle.value = user32.GetWindowLong(procedure.windowHandle, WinUser.GWL_STYLE)
            oldPlacement.value = currentPlacement

            val newStyle = oldStyle.value and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).inv()
            user32.SetWindowLong(procedure.windowHandle, WinUser.GWL_STYLE, newStyle)

            val monitor = user32.MonitorFromWindow(procedure.windowHandle, WinUser.MONITOR_DEFAULTTONEAREST)
            val monitorInfo = WinUser.MONITORINFO().apply { cbSize = size() }
            user32.GetMonitorInfo(monitor, monitorInfo)
            val rc = monitorInfo.rcMonitor

            user32.SetWindowPos(
                procedure.windowHandle,
                null,
                rc.left,
                rc.top,
                rc.right - rc.left,
                rc.bottom - rc.top,
                WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED,
            )
        } else {
            if (procedure.isFullscreen) {
                procedure.isFullscreen = false
                user32.SetWindowLong(procedure.windowHandle, WinUser.GWL_STYLE, oldStyle.intValue)
                user32.SetWindowPos(
                    procedure.windowHandle,
                    null,
                    0,
                    0,
                    0,
                    0,
                    WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED,
                )
                user32.SetWindowPlacement(procedure.windowHandle, oldPlacement.value)

                if (oldPlacement.value.showCmd == WinUser.SW_SHOWMAXIMIZED) {
                    user32.ShowWindow(
                        procedure.windowHandle,
                        WinUser.SW_RESTORE,
                    )
                    user32.ShowWindow(
                        procedure.windowHandle,
                        WinUser.SW_MAXIMIZE,
                    )
                }
            }
            transition.windowState.let {
                state.size = it.size
                state.position = it.position
                state.placement = it.placement
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Paint the client area behind the native maximized-frame insets too.
                .drawBehind { drawRect(Color(window.background.rgb)) }
                .windowInsetsPadding(if (state.placement == WindowPlacement.Maximized) paddingInset else WindowInsets()),
    ) {
        val hoveringFullscreen = remember { mutableStateOf(false) }
        content(
            WindowInsets(top = captionHeight),
            contentPaddingInset.union(WindowInsets(top = captionHeight)),
            {},
            ::windowsToggleFullscreen,
        )
        if (PlayerState.isExpanded) {
            CaptionButton(
                onClick = {
                    if (PlayerState.isFullscreen) {
                        windowsToggleFullscreen()
                    } else if (UiState.showVideoFullscreen) {
                        UiState.showVideoFullscreen = false
                    } else {
                        PlayerState.exitExpanded()
                    }
                },
                slightlyBigger = true,
                modifier =
                    Modifier.onGloballyPositioned { captionHitTest.backButton = it.boundsInWindow() }.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Enter -> {
                                        hoveringFullscreen.value = true
                                    }

                                    PointerEventType.Exit -> {
                                        hoveringFullscreen.value = false
                                    }
                                }
                            }
                        }
                    },
                icon = CaptionButtonIcon.ArrowLeft,
                isActive = true,
                darkTheme = darkTheme,
                isCloseButton = false,
            )
        }
        val offsetCondition = remember { mutableStateOf(false) }
        val offset = animateDpAsState(if (offsetCondition.value) -captionHeight else 0.dp)
        LaunchedEffect(PlayerState.isFullscreen && !hoveringFullscreen.value) {
            if (PlayerState.isFullscreen && !hoveringFullscreen.value) {
                delay(1000.milliseconds)
                offsetCondition.value = true
            } else {
                offsetCondition.value = false
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .height(captionHeight)
                    .offset(x = 0.dp, y = offset.value)
                    .zIndex(99999f)
                    .onGloballyPositioned { captionHitTest.captionBar = it.boundsInWindow() },
        ) {
            Spacer(modifier = Modifier.weight(1f))
            CaptionButtonRow(
                windowHandle = procedure.windowHandle,
                state = state,
                isMaximize = state.placement == WindowPlacement.Maximized,
                isActive = procedure.isWindowActive,
                darkTheme = darkTheme,
                accentColor = procedure.windowFrameColor,
                frameColorEnabled = procedure.isWindowFrameAccentColorEnabled,
                onCloseRequest = onCloseRequest,
                modifier =
                    Modifier
                        .align(Alignment.Top)
                        .onSizeChanged {
                            contentPaddingInset.insets = WindowInsets(right = it.width, top = it.height)
                        }.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Enter -> {
                                            hoveringFullscreen.value = true
                                        }

                                        PointerEventType.Exit -> {
                                            hoveringFullscreen.value = false
                                        }
                                    }
                                }
                            }
                        },
                toggleFullscreen = { windowsToggleFullscreen() },
                onMaximizeButtonRectUpdate = {
                    captionHitTest.maximizeButton = it
                },
                onMinimizeButtonRectUpdate = {
                    captionHitTest.minimizeButton = it
                },
            ) {
                captionHitTest.closeButton = it
            }
        }
        if (PlayerState.isFullscreen) {
            Box(
                modifier =
                    Modifier
                        .height(4.dp)
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    when (event.type) {
                                        PointerEventType.Enter -> {
                                            hoveringFullscreen.value = true
                                        }

                                        PointerEventType.Exit -> {
                                            hoveringFullscreen.value = false
                                        }
                                    }
                                }
                            }
                        },
            )
        }
    }
}

@Composable
fun CaptionButtonRow(
    windowHandle: HWND,
    state: WindowState,
    isMaximize: Boolean,
    isActive: Boolean,
    darkTheme: Boolean,
    accentColor: Color,
    frameColorEnabled: Boolean,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier,
    toggleFullscreen: () -> Unit,
    onMaximizeButtonRectUpdate: (Rect) -> Unit,
    onMinimizeButtonRectUpdate: (Rect) -> Unit = {},
    onCloseButtonRectUpdate: (Rect) -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .zIndex(1f),
    ) {
        CaptionButton(
            onClick = {
                User32.INSTANCE.ShowWindow(windowHandle, WinUser.SW_MINIMIZE)
            },
            icon = CaptionButtonIcon.Minimize,
            isActive = isActive,
            isCloseButton = false,
            darkTheme = darkTheme,
            modifier =
                Modifier.onGloballyPositioned {
                    onMinimizeButtonRectUpdate(it.boundsInWindow())
                },
        )
        CaptionButton(
            onClick = {
                if (PlayerState.isFullscreen) {
                    toggleFullscreen()
                } else {
                    if (isMaximize) {
                        User32.INSTANCE.ShowWindow(
                            windowHandle,
                            WinUser.SW_RESTORE,
                        )
                        state.placement = WindowPlacement.Floating
                    } else {
                        User32.INSTANCE.ShowWindow(
                            windowHandle,
                            WinUser.SW_MAXIMIZE,
                        )
                        state.placement = WindowPlacement.Maximized
                    }
                }
            },
            slightlyBigger = PlayerState.isFullscreen,
            darkTheme = darkTheme,
            icon =
                if (PlayerState.isFullscreen) {
                    CaptionButtonIcon.ExitFullscreen
                } else {
                    if (isMaximize) {
                        CaptionButtonIcon.Restore
                    } else {
                        CaptionButtonIcon.Maximize
                    }
                },
            isActive = isActive,
            isCloseButton = false,
            modifier =
                Modifier.onGloballyPositioned {
                    onMaximizeButtonRectUpdate(it.boundsInWindow())
                },
        )
        CaptionButton(
            icon = CaptionButtonIcon.Close,
            onClick = onCloseRequest,
            isActive = isActive,
            darkTheme = darkTheme,
            isCloseButton = true,
            modifier =
                Modifier.onGloballyPositioned {
                    onCloseButtonRectUpdate(it.boundsInWindow())
                },
        )
    }
}

@Composable
fun CaptionButton(
    onClick: () -> Unit,
    icon: CaptionButtonIcon,
    isActive: Boolean,
    slightlyBigger: Boolean = false,
    modifier: Modifier = Modifier,
    isCloseButton: Boolean,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val label =
        stringResource(
            when (icon) {
                CaptionButtonIcon.ArrowLeft -> Res.string.back
                CaptionButtonIcon.Minimize -> Res.string.menu_minimize
                CaptionButtonIcon.Maximize -> Res.string.window_maximize
                CaptionButtonIcon.Restore -> Res.string.window_restore
                CaptionButtonIcon.Close -> Res.string.close
                CaptionButtonIcon.ExitFullscreen -> Res.string.exit_fullscreen
            },
        )
    var hovering by remember { mutableStateOf(false) }
    var pressing by remember { mutableStateOf(false) }
    Box(
        modifier =
            modifier
                .size(46.dp, 32.dp)
                .semantics { contentDescription = label }
                .clickable(
                    onClick = onClick,
                    role = Role.Button,
                    interactionSource = interaction,
                    indication = null,
                ).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter -> {
                                    hovering = true
                                }

                                PointerEventType.Exit -> {
                                    hovering = false
                                }

                                PointerEventType.Press -> {
                                    pressing = true
                                }

                                PointerEventType.Release -> {
                                    pressing = false
                                }
                            }
                        }
                    }
                }.background(
                    if (hovering) {
                        if (isCloseButton) {
                            if (pressing) Color(0xFFB3271E) else Color(0xFFC42B1C)
                        } else {
                            if (darkTheme) {
                                Color.White.copy(if (pressing) 0.1f else 0.05f)
                            } else {
                                Color.Black.copy(if (pressing) 0.1f else 0.05f)
                            }
                        }
                    } else {
                        Color.Transparent
                    },
                ),
    ) {
        val fontFamily by rememberFontIconFamily()
        val base = if (darkTheme) Color.White else Color.Black
        val contentColor =
            when {
                !isActive -> base.copy(0.5f)
                pressing -> base.copy(0.7f)
                else -> base
            }
        if (fontFamily != null) {
            BasicText(
                text = icon.glyph.toString(),
                style =
                    TextStyle(
                        color = contentColor,
                        fontFamily = fontFamily,
                        textAlign = TextAlign.Center,
                        fontSize = if (slightlyBigger) 12.sp else 10.sp,
                    ),
                modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center).clearAndSetSemantics {},
            )
        } else {
            Icon(
                imageVector = icon.imageVector,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center).size(13.dp),
            )
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun rememberFontIconFamily(): State<FontFamily?> {
    val fontIconFamily = remember { mutableStateOf<FontFamily?>(null) }
    val fontFamilyResolver = LocalFontFamilyResolver.current
    LaunchedEffect(fontFamilyResolver) {
        fontIconFamily.value =
            sequenceOf("Segoe Fluent Icons", "Segoe MDL2 Assets").firstNotNullOfOrNull {
                val fontFamily = FontFamily(it)
                runCatching {
                    val result = fontFamilyResolver.resolve(fontFamily).value as FontLoadResult
                    if (result.typeface == null || result.typeface?.familyName != it) {
                        null
                    } else {
                        fontFamily
                    }
                }.getOrNull()
            }
    }
    return fontIconFamily
}

enum class CaptionButtonIcon(
    val glyph: Char,
    val imageVector: ImageVector,
) {
    Minimize(
        glyph = '\uE921',
        imageVector = MinimizeIcon,
    ),
    Maximize(
        glyph = '\uE922',
        imageVector = MaximizeIcon,
    ),
    Restore(
        glyph = '\uE923',
        imageVector = RestoreIcon,
    ),
    Close(
        glyph = '\uE8BB',
        imageVector = CloseIcon,
    ),
    ArrowLeft(
        glyph = '\uE72B',
        imageVector = ArrowLeftIcon,
    ),
    ExitFullscreen(
        glyph = '\uE73F',
        imageVector = ExitFullscreenIcon,
    ),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrameWindowScope.WindowsMiniPlayerWindowFrame(
    onCloseRequest: () -> Unit,
    state: WindowState,
    captionBarHeight: Dp = 32.dp,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(window) {
        window.findSkiaLayer()?.transparency = true
    }

    val paddingInset = remember { MutableWindowInsets() }
    val maxButtonRect = remember { mutableStateOf(Rect.Zero) }
    val minButtonRect = remember { mutableStateOf(Rect.Zero) }
    val closeButtonRect = remember { mutableStateOf(Rect.Zero) }
    val captionBarRect = remember { mutableStateOf(Rect.Zero) }
    val layoutHitTestOwner = rememberLayoutHitTestOwner()
    val contentPaddingInset = remember { MutableWindowInsets() }

    val procedure =
        remember(window) {
            ComposeWindowProcedure(
                window = window,
                hitTest = { x, y ->
                    HTCLIENT
                },
                onWindowInsetUpdate = { paddingInset.insets = it },
            )
        }

    LaunchedEffect(window) {
        val hwnd = procedure.windowHandle
        val user32 = User32Extend.instance ?: return@LaunchedEffect

        val wsExToolWindow = 0x00000080
        val wsExAppWindow = 0x00040000

        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val newExStyle = (exStyle or wsExToolWindow) and wsExAppWindow.inv()
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, newExStyle)

        user32.SetWindowPos(
            hwnd,
            null,
            0,
            0,
            0,
            0,
            WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or
                WinUser.SWP_NOZORDER or WinUser.SWP_FRAMECHANGED,
        )

        val oldStyle = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val newStyle = oldStyle and (WinUser.WS_THICKFRAME or WinUser.WS_MAXIMIZEBOX).inv()
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, newStyle)
    }

    Box(
        modifier =
            Modifier
                .windowInsetsPadding(if (state.placement == WindowPlacement.Maximized) paddingInset else WindowInsets()),
    ) {
        content()
    }
}

object IconResources {
    private val cache = mutableMapOf<String, String>()

    @Synchronized
    fun toTempFile(
        resourcePath: String,
        suffix: String = ".ico",
    ): String {
        cache[resourcePath]?.let { return it }

        val stream =
            Thread
                .currentThread()
                .contextClassLoader
                .getResourceAsStream(resourcePath)
                ?: error("Classpath resource not found: $resourcePath")

        val file =
            kotlin.io.path
                .createTempFile(prefix = "icon-", suffix = suffix)
                .toFile()
                .apply { deleteOnExit() }

        stream.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }

        val path = file.absolutePath
        cache[resourcePath] = path
        return path
    }
}

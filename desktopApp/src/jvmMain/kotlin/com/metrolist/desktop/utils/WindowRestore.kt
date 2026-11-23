/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope
import com.metrolist.desktop.window.DesktopWindowScope
import com.metrolist.shared.utils.OsUtil
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window

@Composable
fun DesktopWindowScope.WindowDragHandle(
    isMaximized: Boolean,
    onRestoreFromMaximized: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tao = taoWindow
    if (tao != null) {
        Box(
            modifier =
                Modifier.pointerInput(tao) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
                            if (!currentEvent.buttons.isPrimaryPressed) continue
                            tao.dragWindow()
                            down.consume()
                        }
                    }
                },
        ) {
            content()
        }
        return
    }

    val window =
        awtWindow ?: run {
            content()
            return
        }
    if (!OsUtil.isLinux) {
        val awtScope =
            remember(window) {
                object : WindowScope {
                    override val window: Window = window
                }
            }
        awtScope.WindowDraggableArea(content = content)
        return
    }

    val currentIsMaximized by rememberUpdatedState(isMaximized)
    val currentOnRestoreFromMaximized by rememberUpdatedState(onRestoreFromMaximized)
    Box(
        modifier =
            Modifier.pointerInput(window) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Final)
                        if (!currentEvent.buttons.isPrimaryPressed) continue
                        // A failed native move falls back to moving the window from AWT.
                        if (runCatching { LinuxWindowMove.start(window) }.getOrDefault(false)) {
                            down.consume()
                            continue
                        }

                        val start = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: continue
                        var origin = window.location
                        var pointerOrigin = start
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.buttons.isPrimaryPressed) break
                            val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: continue
                            if (!dragging) {
                                val dx = pointer.x - start.x
                                val dy = pointer.y - start.y
                                if (dx * dx + dy * dy < 16) continue
                                if (currentIsMaximized) {
                                    currentOnRestoreFromMaximized()
                                    origin = Point(pointer.x - window.width / 2, pointer.y - 20)
                                    pointerOrigin = pointer
                                }
                                dragging = true
                            }
                            window.setLocation(
                                origin.x + pointer.x - pointerOrigin.x,
                                origin.y + pointer.y - pointerOrigin.y,
                            )
                        }
                    }
                }
            },
    ) {
        content()
    }
}

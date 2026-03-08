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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope
import java.awt.MouseInfo
import java.awt.Point

@Composable
fun WindowScope.WindowDragHandle(
    isMaximized: Boolean,
    onRestoreFromMaximized: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!LinuxWindowMove.isX11Toolkit()) {
        WindowDraggableArea(content = content)
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
                        if (LinuxWindowMove.start(window)) {
                            down.consume()
                            continue
                        }

                        val start = MouseInfo.getPointerInfo()?.location ?: continue
                        var origin = window.location
                        var pointerOrigin = start
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (!event.buttons.isPrimaryPressed) break
                            val pointer = MouseInfo.getPointerInfo()?.location ?: continue
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

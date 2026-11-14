package com.metrolist.shared.ui.components

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.clearSkikoComposeImplementation
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalComposeUiApi::class)
class PlayerSheetOutlineTest {
    @BeforeTest
    fun registerGraphics() {
        registerSkikoComposeImplementation()
    }

    @AfterTest
    fun clearGraphics() {
        clearSkikoComposeImplementation()
    }

    @Test
    fun movingOutlineKeepsTheMiniPlayerHeightAndNavigationGap() {
        val window = Size(420f, 900f)
        val miniPlayer = Rect(16f, 0f, 404f, 64f)
        for (expressive in listOf(false, true)) {
            val style = PlayerSheetContainerStyle(Color.Black, 32.dp, expressive, 3.dp, null)
            for (bottomInset in listOf(34f, 114f)) {
                for (step in (0..100) + (100 downTo 0)) {
                    val progress = step / 100f
                    val shape = PlayerSheetClipShape(miniPlayer, style, progress)
                    for (direction in LayoutDirection.entries) {
                        val bounds = shape.createOutline(window, direction, Density(1f)).bounds
                        val sheetTop = (window.height - miniPlayer.height - bottomInset) * (1f - progress)
                        assertEquals(16f * (1f - progress), bounds.left, 0.001f)
                        assertEquals(420f - 16f * (1f - progress), bounds.right, 0.001f)
                        assertEquals(0f, bounds.top, 0.001f)
                        assertEquals(900f - bottomInset * (1f - progress), bounds.bottom + sheetTop, 0.001f)
                        assertEquals(bounds, shape.createOutline(window, direction, Density(1f)).bounds)
                    }
                }
            }
        }
    }
}

package com.metrolist.shared.ui.menu

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopMenuPositionProviderTest {
    private val window = IntSize(800, 600)
    private val menu = IntSize(360, 300)

    @Test
    fun cursorMenusFlipAtTheWindowEdges() {
        val position = DesktopMenuPositionProvider(IntOffset(780, 580), null, 8)
        assertEquals(IntOffset(420, 280), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, menu))
        assertEquals(1f, position.transformOrigin.pivotFractionX)
        assertEquals(1f, position.transformOrigin.pivotFractionY)
        assertEquals(IntOffset(0, 0), position.calculatePosition(IntRect.Zero, IntSize(200, 100), LayoutDirection.Rtl, menu))
    }

    @Test
    fun buttonMenusUseTheButtonInsteadOfTheParentAndFlipAboveIt() {
        val button = IntRect(740, 540, 788, 588)
        val position = DesktopMenuPositionProvider(null, button, 8)
        assertEquals(IntOffset(428, 232), position.calculatePosition(IntRect(0, 0, 800, 600), window, LayoutDirection.Ltr, menu))
        assertEquals(1f, position.transformOrigin.pivotFractionY)
    }

    @Test
    fun buttonsAtTheTopOpenBelowAndRespectLayoutDirection() {
        val button = IntRect(400, 10, 448, 58)
        val position = DesktopMenuPositionProvider(null, button, 8)
        assertEquals(IntOffset(88, 66), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, menu))
        assertEquals(IntOffset(400, 66), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Rtl, menu))
        assertEquals(0f, position.transformOrigin.pivotFractionY)
    }

    @Test
    fun tallMenusScrollOnTheLargerSideWithoutCoveringTheButton() {
        for (button in listOf(IntRect(760, 190, 808, 238), IntRect(-10, 350, 38, 398))) {
            val position = DesktopMenuPositionProvider(null, button, 8)
            val size = IntSize(menu.width, position.availableHeight(window))
            for (direction in LayoutDirection.entries) {
                val offset = position.calculatePosition(IntRect.Zero, window, direction, size)
                val bounds = IntRect(offset, size)
                assertFalse(bounds.overlaps(button))
                assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= window.width && bounds.bottom <= window.height)
            }
        }
    }

    @Test
    fun aShortWindowLimitsTheMenuToTheSpaceAboveTheButton() {
        val window = IntSize(280, 120)
        val button = IntRect(232, 44, 280, 92)
        val position = DesktopMenuPositionProvider(null, button, 8)
        val size = IntSize(window.width, position.availableHeight(window))
        assertEquals(36, size.height)
        val offset = position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, size)
        assertEquals(IntOffset.Zero, offset)
        assertFalse(IntRect(offset, size).overlaps(button))
    }

    @Test
    fun playerMenusUseTheFullHeightBesideTheButton() {
        val button = IntRect(350, 400, 398, 448)
        val position = DesktopMenuPositionProvider(null, button, 8, menu.width)
        val size = IntSize(menu.width, position.availableHeight(window))
        assertEquals(window.height, size.height)
        assertEquals(IntOffset(406, 0), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, size))
        assertFalse(IntRect(IntOffset(406, 0), size).overlaps(button))
        assertEquals(0f, position.transformOrigin.pivotFractionX)
    }

    @Test
    fun playerMenusAlignWithTheirButtonAndFlipSidesAtWindowEdges() {
        val size = IntSize(menu.width, 500)
        for (direction in LayoutDirection.entries) {
            val leftButton = IntRect(180, 540, 228, 588)
            val rightButton = IntRect(740, 540, 788, 588)
            val leftPosition = DesktopMenuPositionProvider(null, leftButton, 8, size.width)
            val rightPosition = DesktopMenuPositionProvider(null, rightButton, 8, size.width)
            assertEquals(IntOffset(236, 100), leftPosition.calculatePosition(IntRect.Zero, window, direction, size))
            assertEquals(IntOffset(372, 100), rightPosition.calculatePosition(IntRect.Zero, window, direction, size))
        }
        val button = IntRect(350, 30, 398, 78)
        val position = DesktopMenuPositionProvider(null, button, 8, size.width)
        assertEquals(IntOffset(406, 30), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, size))
    }

    @Test
    fun playerMenusPreferTheLayoutDirectionWhenBothSidesFit() {
        val button = IntRect(550, 400, 598, 448)
        val position = DesktopMenuPositionProvider(null, button, 8, menu.width)
        val window = IntSize(1200, 600)
        assertEquals(IntOffset(606, 300), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, menu))
        assertEquals(IntOffset(182, 300), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Rtl, menu))
    }

    @Test
    fun playerMenusFallBackAboveOrBelowWhenNeitherSideFits() {
        val window = IntSize(500, 400)
        for (button in listOf(IntRect(226, 330, 274, 378), IntRect(226, 22, 274, 70))) {
            val position = DesktopMenuPositionProvider(null, button, 8, menu.width)
            assertEquals(322, position.availableHeight(window))
            val size = IntSize(menu.width, position.availableHeight(window))
            val offset = position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, size)
            assertFalse(IntRect(offset, size).overlaps(button))
            assertTrue(offset.y >= 0 && offset.y + size.height <= window.height)
        }
    }

    @Test
    fun rightClickPlayerMenusKeepTheirCursorPlacement() {
        val button = IntRect(100, 100, 148, 148)
        val position = DesktopMenuPositionProvider(IntOffset(780, 580), button, 8, menu.width)
        assertEquals(window.height, position.availableHeight(window))
        assertEquals(IntOffset(420, 280), position.calculatePosition(IntRect.Zero, window, LayoutDirection.Ltr, menu))
    }

    @Test
    fun anchorBoundsStayFixedThroughClosingAndRefreshOnReopen() {
        val anchor = DesktopMenuAnchor()
        val first = IntRect(100, 100, 148, 148)
        val next = IntRect(300, 100, 348, 148)
        anchor.update(true, first)
        anchor.update(true, next)
        anchor.update(false, null)
        assertEquals(first, anchor.bounds)
        anchor.update(true, next)
        assertEquals(next, anchor.bounds)
    }

    @Test
    fun onlySecondaryPressesUseWindowCursorCoordinates() {
        val state = CursorContextMenuState()
        state.updateCursor(Offset(600f, 400f), Density(2f), PointerType.Mouse, secondary = true)
        assertEquals(DpOffset(300.dp, 200.dp), state.offset)
        state.updateCursor(Offset(600f, 400f), Density(2f), PointerType.Mouse, secondary = false)
        assertEquals(DpOffset.Zero, state.offset)
    }
}

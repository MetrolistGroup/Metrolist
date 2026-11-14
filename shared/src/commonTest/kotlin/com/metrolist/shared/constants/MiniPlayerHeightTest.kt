package com.metrolist.shared.constants

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class MiniPlayerHeightTest {
    @Test
    fun normalAndSmallTextKeepTheExistingMinimum() {
        assertEquals(66.dp, modernMiniPlayerHeight(Density(1f)))
        assertEquals(66.dp, modernMiniPlayerHeight(Density(3f, fontScale = 0.85f)))
    }

    @Test
    fun largeTextFitsBothLinesAndPreservesTheGapAndPadding() {
        assertEquals(78.dp, modernMiniPlayerHeight(linearDensity(1f, fontScale = 2f)))
        assertEquals(78.dp, modernMiniPlayerHeight(linearDensity(3f, fontScale = 2f)))
    }

    private fun linearDensity(
        density: Float,
        fontScale: Float,
    ): Density =
        object : Density {
            override val density = density
            override val fontScale = fontScale

            override fun TextUnit.toDp(): Dp = (value * fontScale).dp
        }

    @Test
    fun platformNonlinearConversionsDetermineTheLineHeights() {
        val density =
            object : Density {
                override val density = 2f
                override val fontScale = 2f

                override fun TextUnit.toDp(): Dp =
                    when (this) {
                        16.sp -> 30.dp
                        14.sp -> 26.dp
                        else -> error("Unexpected text size")
                    }
            }
        assertEquals(74.dp, modernMiniPlayerHeight(density))
    }
}

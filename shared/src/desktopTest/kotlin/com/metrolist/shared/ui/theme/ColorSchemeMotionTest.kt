package com.metrolist.shared.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.metrolist.shared.settings.ThemePaletteStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ColorSchemeMotionTest {
    @Test
    fun everyMaterialRoleInterpolatesIncludingFixedAndSurfaceRoles() {
        val start = themeColorScheme(Color.Red, false, ThemePaletteStyle.EXPRESSIVE)
        val end = themeColorScheme(Color.Blue, true, ThemePaletteStyle.EXPRESSIVE)
        val roles =
            ColorScheme::class.java.methods.filter {
                it.name.startsWith("get") && it.name.contains("-") &&
                    it.returnType == Long::class.javaPrimitiveType
            }
        assertTrue(roles.size >= 48)
        for (fraction in listOf(0f, 0.25f, 0.5f, 1f)) {
            val frame = interpolateColorScheme(start, end, fraction)
            for (role in roles) {
                val from = Color((role.invoke(start) as Long).toULong())
                val to = Color((role.invoke(end) as Long).toULong())
                assertEquals(lerp(from, to, fraction).value.toLong(), role.invoke(frame), role.name)
            }
        }
        assertSame(end, interpolateColorScheme(start, end, 0.25f, enabled = false))
    }
}

package com.metrolist.shared.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaterialSymbolsTest {
    @Test
    fun everySymbolParsesInTheTranslated960Viewport() {
        val icons =
            listOf(
                MetrolistIcons::class.java,
                MetrolistIconsFilled::class.java,
                MetrolistIconsOutlined::class.java,
                MetrolistIconsRounded::class.java,
                SongStatusIcons::class.java,
            ).flatMap(::iconsIn)

        assertTrue(icons.isNotEmpty())
        assertEquals(icons.size, icons.map { it.name }.distinct().size)
        icons.forEach { icon ->
            assertEquals(960f, icon.viewportWidth, icon.name)
            assertEquals(960f, icon.viewportHeight, icon.name)
            val expectedSize = if (icon.name.startsWith("SongStatus.")) 10.dp else 24.dp
            assertEquals(expectedSize, icon.defaultWidth, icon.name)
            assertEquals(expectedSize, icon.defaultHeight, icon.name)
            assertEquals(icon.name.startsWith("AutoMirrored."), icon.autoMirror, icon.name)
            assertEquals(1, icon.root.size, icon.name)
            val translated = assertIs<VectorGroup>(icon.root[0], icon.name)
            assertEquals(960f, translated.translationY, icon.name)
            assertEquals(1, translated.size, icon.name)
            val path = assertIs<VectorPath>(translated[0], icon.name)
            assertTrue(path.pathData.isNotEmpty(), icon.name)
        }
    }

    private fun iconsIn(type: Class<*>): List<ImageVector> {
        val instance = type.getField("INSTANCE").get(null)
        val icons =
            type.methods
                .filter { it.parameterCount == 0 && it.returnType == ImageVector::class.java }
                .map { it.invoke(instance) as ImageVector }
        return icons + type.declaredClasses.flatMap(::iconsIn)
    }
}

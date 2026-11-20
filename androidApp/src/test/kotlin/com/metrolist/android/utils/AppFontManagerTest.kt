package com.metrolist.android.utils

import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.ui.text.font.FontFamily
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.RandomAccessFile
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFontManagerTest {
    private var previousSettings: Settings? = null

    @Before
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun restoreSettings() {
        Settings.replaceSharedForTest(previousSettings)
    }

    @Test
    fun consecutiveImportsOfTheSameExtensionReloadAndARejectedReplacementKeepsTheCurrentFont() {
        val directory = Files.createTempDirectory("font-imports").toFile()
        val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = directory
            }
        try {
            val source = directory.resolve("selected.ttf")
            val firstBytes = javaClass.getResourceAsStream("/font-import-fixtures/first.ttf")!!.use { it.readBytes() }
            val secondBytes = javaClass.getResourceAsStream("/font-import-fixtures/second.ttf")!!.use { it.readBytes() }
            source.writeBytes(firstBytes)
            val first = runBlocking { AppFontManager.importFont(context, Uri.fromFile(source)) { "" }.getOrThrow() }
            val firstFile = directory.resolve("fonts").resolve(first.fileName)
            assertArrayEquals(firstBytes, firstFile.readBytes())
            assertNotEquals(FontFamily.Default, AppFontManager.loadFontFamily(context, first.fileName))

            source.writeBytes(secondBytes)
            val second = runBlocking { AppFontManager.importFont(context, Uri.fromFile(source)) { first.fileName }.getOrThrow() }
            val secondFile = directory.resolve("fonts").resolve(second.fileName)
            assertNotEquals(first.fileName, second.fileName)
            assertFalse(firstFile.exists())
            assertArrayEquals(secondBytes, secondFile.readBytes())
            assertNotEquals(FontFamily.Default, AppFontManager.loadFontFamily(context, second.fileName))

            RandomAccessFile(source, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
            assertTrue(
                runBlocking { AppFontManager.importFont(context, Uri.fromFile(source)) { error("Invalid font was selected") } }.isFailure,
            )
            assertArrayEquals(secondBytes, secondFile.readBytes())
            assertNotEquals(FontFamily.Default, AppFontManager.loadFontFamily(context, second.fileName))
            assertEquals(listOf(second.fileName), directory.resolve("fonts").listFiles()!!.map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resetRetiresOnlyThePreviouslySelectedCustomFont() {
        val directory = Files.createTempDirectory("custom-fonts").toFile()
        val context =
            object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                override fun getFilesDir() = directory
            }
        try {
            val fonts = directory.resolve("fonts").apply { mkdirs() }
            val legacy = fonts.resolve("app_font.ttf").apply { writeBytes(byteArrayOf(1)) }
            val imported = fonts.resolve("app_font_12345678-1234-1234-1234-123456789abc.otf").apply { writeBytes(byteArrayOf(2)) }
            val downloaded = fonts.resolve("GoogleSans-Regular.ttf").apply { writeBytes(byteArrayOf(3)) }
            assertTrue(AppFontManager.hasImportedFont(context, legacy.name))
            assertTrue(AppFontManager.hasImportedFont(context, imported.name))
            assertFalse(AppFontManager.hasImportedFont(context, "../${legacy.name}"))
            assertFalse(AppFontManager.hasImportedFont(context, downloaded.name))

            runBlocking { AppFontManager.retireImportedFont(context, "../${legacy.name}") }
            assertTrue(legacy.exists())
            runBlocking { AppFontManager.retireImportedFont(context, legacy.name) }

            assertFalse(legacy.exists())
            assertTrue(imported.exists())
            assertTrue(downloaded.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

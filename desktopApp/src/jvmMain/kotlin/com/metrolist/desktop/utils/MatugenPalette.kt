/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.util.Properties

internal class MatugenPalette(
    val sourceColor: Color,
    val light: ColorScheme,
    val dark: ColorScheme,
)

internal object MatugenColors {
    var palette by mutableStateOf<MatugenPalette?>(null)
        private set

    /** Loads the palette and reloads it whenever Matugen rewrites the file, until cancelled. */
    suspend fun watch(file: File = File(XdgUtils.getXdgConfigHome(), "metrolist/matugen.properties")) =
        withContext(Dispatchers.IO) {
            val directory = file.parentFile.apply { mkdirs() }
            try {
                FileSystems.getDefault().newWatchService().use { watcher ->
                    directory.toPath().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                    while (true) {
                        load(file)
                        do {
                            val key = runInterruptible { watcher.take() }
                            val changed = key.pollEvents().any { (it.context() as? Path)?.toString() == file.name }
                            key.reset()
                        } while (!changed)
                    }
                }
            } catch (e: IOException) {
                Logger.w("Matugen", "Cannot watch ${directory.path}: ${e.message}")
            } finally {
                palette = null
            }
        }

    private fun load(file: File) {
        if (!file.isFile) {
            palette = null
            return
        }
        // Keep the previous palette when a read races Matugen's write; the next modify event reloads it.
        runCatching { parseMatugenPalette(file.readText()) }
            .onSuccess { palette = it }
            .onFailure { Logger.w("Matugen", "Ignoring ${file.path}: ${it.message}") }
    }
}

internal fun parseMatugenPalette(text: String): MatugenPalette {
    val values = Properties().apply { load(text.reader()) }

    fun color(key: String): Color {
        val hex = values.getProperty(key)?.trim()?.removePrefix("#")
        val rgb = hex?.takeIf { it.length == 6 }?.toLongOrNull(16)
        requireNotNull(rgb) { "$key is missing or not a #rrggbb color" }
        return Color(0xFF000000 or rgb)
    }

    fun scheme(mode: String): ColorScheme {
        fun role(name: String) = color("$mode.$name")
        return ColorScheme(
            primary = role("primary"),
            onPrimary = role("on_primary"),
            primaryContainer = role("primary_container"),
            onPrimaryContainer = role("on_primary_container"),
            inversePrimary = role("inverse_primary"),
            secondary = role("secondary"),
            onSecondary = role("on_secondary"),
            secondaryContainer = role("secondary_container"),
            onSecondaryContainer = role("on_secondary_container"),
            tertiary = role("tertiary"),
            onTertiary = role("on_tertiary"),
            tertiaryContainer = role("tertiary_container"),
            onTertiaryContainer = role("on_tertiary_container"),
            background = role("background"),
            onBackground = role("on_background"),
            surface = role("surface"),
            onSurface = role("on_surface"),
            surfaceVariant = role("surface_variant"),
            onSurfaceVariant = role("on_surface_variant"),
            surfaceTint = role("surface_tint"),
            inverseSurface = role("inverse_surface"),
            inverseOnSurface = role("inverse_on_surface"),
            error = role("error"),
            onError = role("on_error"),
            errorContainer = role("error_container"),
            onErrorContainer = role("on_error_container"),
            outline = role("outline"),
            outlineVariant = role("outline_variant"),
            scrim = role("scrim"),
            surfaceBright = role("surface_bright"),
            surfaceContainer = role("surface_container"),
            surfaceContainerHigh = role("surface_container_high"),
            surfaceContainerHighest = role("surface_container_highest"),
            surfaceContainerLow = role("surface_container_low"),
            surfaceContainerLowest = role("surface_container_lowest"),
            surfaceDim = role("surface_dim"),
            primaryFixed = role("primary_fixed"),
            primaryFixedDim = role("primary_fixed_dim"),
            onPrimaryFixed = role("on_primary_fixed"),
            onPrimaryFixedVariant = role("on_primary_fixed_variant"),
            secondaryFixed = role("secondary_fixed"),
            secondaryFixedDim = role("secondary_fixed_dim"),
            onSecondaryFixed = role("on_secondary_fixed"),
            onSecondaryFixedVariant = role("on_secondary_fixed_variant"),
            tertiaryFixed = role("tertiary_fixed"),
            tertiaryFixedDim = role("tertiary_fixed_dim"),
            onTertiaryFixed = role("on_tertiary_fixed"),
            onTertiaryFixedVariant = role("on_tertiary_fixed_variant"),
        )
    }

    return MatugenPalette(color("source_color"), scheme("light"), scheme("dark"))
}

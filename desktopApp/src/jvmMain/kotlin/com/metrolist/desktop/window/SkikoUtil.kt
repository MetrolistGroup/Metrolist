package com.metrolist.desktop.window

import androidx.compose.ui.awt.ComposeWindow
import org.jetbrains.skiko.SkiaLayer
import java.awt.Container
import javax.swing.JComponent

internal fun <T : JComponent> findComponent(
    container: Container,
    klass: Class<T>,
): T? {
    val componentSequence = container.components.asSequence()
    return componentSequence
        .filter { klass.isInstance(it) }
        .ifEmpty {
            componentSequence
                .filterIsInstance<Container>()
                .mapNotNull { findComponent(it, klass) }
        }.map { klass.cast(it) }
        .firstOrNull()
}

internal inline fun <reified T : JComponent> Container.findComponent() = findComponent(this, T::class.java)

internal fun ComposeWindow.findSkiaLayer(): SkiaLayer? = findComponent<SkiaLayer>()

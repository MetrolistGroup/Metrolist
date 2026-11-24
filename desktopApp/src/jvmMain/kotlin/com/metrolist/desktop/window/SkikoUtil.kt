package com.metrolist.desktop.window

import org.jetbrains.skiko.GraphicsApi
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

internal fun Container.findSkiaLayer(): SkiaLayer? = findComponent<SkiaLayer>()

internal fun Container.findRenderApi(): GraphicsApi? = findSkiaLayer()?.renderApi

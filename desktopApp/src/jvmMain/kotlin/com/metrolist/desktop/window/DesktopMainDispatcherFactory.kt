package com.metrolist.desktop.window

import com.metrolist.desktop.utils.WaylandScale
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import kotlin.coroutines.CoroutineContext

/**
 * Selects Nucleus's Tao main dispatcher when Linux runs on native Wayland, and keeps Swing's EDT
 * dispatcher everywhere else (macOS, Windows, and Linux X11/XWayland).
 */
@OptIn(InternalCoroutinesApi::class)
class DesktopMainDispatcherFactory : MainDispatcherFactory {
    // Higher than TaoMainDispatcherFactory (100) and SwingDispatcherFactory (0), below TestMainDispatcherFactory.
    override val loadPriority: Int = 101

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher {
        val targetName =
            if (OsUtil.isLinux && WaylandScale.shouldUseNativeWayland()) {
                TAO_DISPATCHER_FACTORY
            } else {
                SWING_DISPATCHER_FACTORY
            }
        val delegate =
            allFactories.firstOrNull { it.javaClass.name == targetName }
                ?: allFactories.firstOrNull { it !== this && it.javaClass.name != javaClass.name }
                ?: error("No desktop MainDispatcherFactory found for $targetName")
        val dispatcher = delegate.createDispatcher(allFactories)
        return if (targetName == TAO_DISPATCHER_FACTORY) QueuedMainDispatcher(dispatcher) else dispatcher
    }

    override fun hintOnError(): String = "Failed to initialize desktop Main dispatcher"

    companion object {
        private const val TAO_DISPATCHER_FACTORY = "dev.nucleusframework.window.tao.dispatch.TaoMainDispatcherFactory"
        private const val SWING_DISPATCHER_FACTORY = "kotlinx.coroutines.swing.SwingDispatcherFactory"
    }
}

/**
 * Tao runs Dispatchers.Main work inline when already on its thread. Swing always queues it, and startup code
 * such as PlayerState's init launches collectors before later fields are assigned, so keep Swing's ordering.
 */
private class QueuedMainDispatcher(
    private val delegate: MainCoroutineDispatcher,
) : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher get() = delegate.immediate

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) = delegate.dispatch(context, block)

    override fun toString(): String = delegate.toString()
}

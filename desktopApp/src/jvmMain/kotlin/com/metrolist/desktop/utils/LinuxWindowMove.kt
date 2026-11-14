package com.metrolist.desktop.utils

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import java.awt.Component
import java.awt.Toolkit
import java.awt.Window

@Suppress("FunctionName")
internal interface WindowMoveX11 : X11 {
    fun XUngrabPointer(
        display: X11.Display,
        time: NativeLong,
    ): Int
}

internal object LinuxWindowMove {
    private val x11 by lazy { Native.load("X11", WindowMoveX11::class.java) }

    fun isX11Toolkit(): Boolean = Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"

    fun start(window: Window): Boolean {
        if (!isX11Toolkit()) return false
        val toolkit = Toolkit.getDefaultToolkit().javaClass

        val lock = toolkit.getMethod("awtLock")
        val unlock = toolkit.getMethod("awtUnlock")
        lock.invoke(null)
        try {
            val accessor = Class.forName("sun.awt.AWTAccessor").getMethod("getComponentAccessor").invoke(null)
            val peer =
                Class
                    .forName("sun.awt.AWTAccessor\$ComponentAccessor")
                    .getMethod("getPeer", Component::class.java)
                    .invoke(accessor, window) ?: return false
            // The WM manages the peer's shell, not JAWT's child content window.
            val windowId = X11.Window(Class.forName("sun.awt.X11.XBaseWindow").getMethod("getWindow").invoke(peer) as Long)
            // Use AWT's connection so releasing its pointer grab lets the window manager take over.
            val display =
                X11.Display().apply {
                    pointer = Pointer(toolkit.getMethod("getDisplay").invoke(null) as Long)
                }
            return startX11WindowMove(x11, display, windowId)
        } finally {
            unlock.invoke(null)
        }
    }
}

internal fun startX11WindowMove(
    x11: WindowMoveX11,
    display: X11.Display,
    window: X11.Window,
): Boolean {
    val atom = x11.XInternAtom(display, "_NET_WM_MOVERESIZE", true) ?: return false
    val root = x11.XDefaultRootWindow(display)
    val rootX = IntByReference()
    val rootY = IntByReference()
    if (!x11.XQueryPointer(
            display,
            root,
            X11.WindowByReference(),
            X11.WindowByReference(),
            rootX,
            rootY,
            IntByReference(),
            IntByReference(),
            IntByReference(),
        )
    ) {
        return false
    }

    val event =
        X11.XEvent().apply {
            setType(X11.XClientMessageEvent::class.java)
            xclient.type = X11.ClientMessage
            xclient.display = display
            xclient.window = window
            xclient.message_type = atom
            xclient.format = 32
            xclient.data.setType(Array<NativeLong>::class.java)
            xclient.data.l =
                arrayOf(
                    NativeLong(rootX.value.toLong()),
                    NativeLong(rootY.value.toLong()),
                    NativeLong(8), // _NET_WM_MOVERESIZE_MOVE
                    NativeLong(1), // Left mouse button
                    NativeLong(1), // Normal application
                )
        }
    x11.XUngrabPointer(display, NativeLong(X11.CurrentTime.toLong()))
    x11.XUngrabKeyboard(display, NativeLong(X11.CurrentTime.toLong()))
    val sent =
        x11.XSendEvent(
            display,
            root,
            0,
            NativeLong((X11.SubstructureRedirectMask or X11.SubstructureNotifyMask).toLong()),
            event,
        ) != 0
    x11.XFlush(display)
    return sent
}

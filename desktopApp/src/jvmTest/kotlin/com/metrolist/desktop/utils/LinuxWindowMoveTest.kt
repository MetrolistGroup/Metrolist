package com.metrolist.desktop.utils

import java.awt.Component
import kotlin.test.Test
import kotlin.test.assertFalse

class LinuxWindowMoveTest {
    @Test
    fun unexportedAwtPeerPackagesUseManualWindowMovement() {
        assertFalse(hasAwtX11PeerAccess(Component::class.java.module, LinuxWindowMove::class.java.module))
    }
}

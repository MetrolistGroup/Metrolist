package com.metrolist.desktop.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopFileDialogsTest {
    @Test
    fun pickerWorkMovesFromBackgroundThreadToEdtAndReturnsItsResult() =
        runBlocking(Dispatchers.Default) {
            val result =
                onFileDialogThread {
                    assertTrue(EventQueue.isDispatchThread())
                    "selected file"
                }
            assertEquals("selected file", result)
        }

    @Test
    fun pickerFailureReachesCaller(): Unit =
        runBlocking(Dispatchers.Default) {
            assertFailsWith<IllegalStateException> {
                onFileDialogThread { error("picker failed") }
            }
        }
}

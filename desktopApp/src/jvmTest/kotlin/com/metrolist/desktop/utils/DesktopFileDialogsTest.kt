package com.metrolist.desktop.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopFileDialogsTest {
    @Test
    fun linuxKeepsPortalSelectionForDocumentsAndExternalDrives() =
        runBlocking<Unit>(Dispatchers.Default) {
            for (selected in listOf(File("/home/test/Documents/backup.zip"), File("/media/external/playlist.m3u"))) {
                val result =
                    desktopFileDialog(
                        isLinux = true,
                        portalAvailable = {
                            assertFalse(EventQueue.isDispatchThread())
                            true
                        },
                        fileKitDialog = {
                            assertFalse(EventQueue.isDispatchThread())
                            selected
                        },
                        nativeDialog = { error("The native dialog cannot grant sandbox access") },
                    )
                assertSame(selected, result)
            }
        }

    @Test
    fun linuxWithoutPortalRunsTheNativeDialogOnEdt() =
        runBlocking<Unit>(Dispatchers.Default) {
            val selected = File("backup.zip")
            val result =
                desktopFileDialog(
                    isLinux = true,
                    portalAvailable = {
                        assertFalse(EventQueue.isDispatchThread())
                        false
                    },
                    fileKitDialog = { error("FileKit would open its native fallback on IO") },
                    nativeDialog = {
                        assertTrue(EventQueue.isDispatchThread())
                        selected
                    },
                )
            assertSame(selected, result)
        }

    @Test
    fun cancellingThePortalDoesNotOpenANativeDialog() =
        runBlocking<Unit>(Dispatchers.Default) {
            val result =
                desktopFileDialog<File?>(
                    isLinux = true,
                    portalAvailable = { true },
                    fileKitDialog = { null },
                    nativeDialog = { error("A cancelled portal must stay cancelled") },
                )
            assertNull(result)
        }

    @Test
    fun portalFailuresAndCoroutineCancellationReachTheCaller() =
        runBlocking<Unit>(Dispatchers.Default) {
            for (failure in listOf(IllegalStateException("portal failed"), CancellationException("cancelled"))) {
                val thrown =
                    assertFailsWith<IllegalStateException> {
                        desktopFileDialog<File?>(
                            isLinux = true,
                            portalAvailable = { true },
                            fileKitDialog = { throw failure },
                            nativeDialog = { error("Do not replace a failed portal with a native picker") },
                        )
                    }
                assertEquals(failure.message, thrown.message)
            }
        }

    @Test
    fun nativeCancellationAndFailuresReachTheCaller() =
        runBlocking<Unit>(Dispatchers.Default) {
            assertNull(desktopFileDialog<File?>(true, { false }, { error("No portal") }, { null }))
            val failure = IllegalStateException("native dialog failed")
            val thrown =
                assertFailsWith<IllegalStateException> {
                    desktopFileDialog<File?>(true, { false }, { error("No portal") }) {
                        assertTrue(EventQueue.isDispatchThread())
                        throw failure
                    }
                }
            assertEquals(failure.message, thrown.message)
        }

    @Test
    fun otherPlatformsKeepFileKitWithoutProbingTheLinuxPortal() =
        runBlocking<Unit>(Dispatchers.Default) {
            val selected = File("backup.zip")
            val result =
                desktopFileDialog(
                    isLinux = false,
                    portalAvailable = { error("Do not probe Linux services on other platforms") },
                    fileKitDialog = { selected },
                    nativeDialog = { error("Keep the platform FileKit dialog") },
                )
            assertSame(selected, result)
        }
}

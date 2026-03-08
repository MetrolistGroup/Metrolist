package com.metrolist.desktop.utils

import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxWebKitSupportTest {
    @Test
    fun stalledLdconfigIsTerminatedWithoutWaitingForOutputEof() {
        assumeTrue(Platform.isLinux())
        val process = ProcessBuilder("sh", "-c", "printf 'partial output'; read line").start()
        try {
            assertNull(readLdconfigCache(timeoutMillis = 0) { process })
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun linuxRequiresBothWebKitAndJavaScriptCore() {
        val root = File.createTempFile("webkit-probe", null).apply { delete() }
        val libDir = File(root, "lib").apply { mkdirs() }
        File(libDir, LINUX_WEBKIT_SONAME).writeText("")
        try {
            assertFalse(
                isLinuxWebKitAvailable(
                    osName = "Linux",
                    librarySearchPaths = listOf(libDir),
                    ldconfigOutput = { null },
                    loadLibrary = { error("Incomplete runtime must not load") },
                ),
            )
            assertTrue(
                isLinuxWebKitAvailable(
                    osName = "Linux",
                    librarySearchPaths = emptyList(),
                    ldconfigOutput = {
                        """
                        $LINUX_WEBKIT_SONAME (libc6,x86-64) => /usr/lib/$LINUX_WEBKIT_SONAME
                        $LINUX_JAVASCRIPTCORE_SONAME (libc6,x86-64) => /usr/lib/$LINUX_JAVASCRIPTCORE_SONAME
                        """.trimIndent()
                    },
                    loadLibrary = {},
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun linuxRejectsPresentLibrariesWithMissingTransitiveDependencies() {
        val root =
            File.createTempFile("webkit-probe", null).apply {
                delete()
                mkdirs()
            }
        File(root, LINUX_WEBKIT_SONAME).writeText("")
        File(root, LINUX_JAVASCRIPTCORE_SONAME).writeText("")
        try {
            for (failingLibrary in listOf(LINUX_WEBKIT_SONAME, LINUX_JAVASCRIPTCORE_SONAME)) {
                assertFalse(
                    isLinuxWebKitAvailable(
                        osName = "Linux",
                        librarySearchPaths = listOf(root),
                        ldconfigOutput = { error("Both libraries were found on disk") },
                        loadLibrary = {
                            if (it == failingLibrary) {
                                throw UnsatisfiedLinkError("libSvtAv1Enc.so.3: cannot open shared object file")
                            }
                        },
                    ),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedAvailabilityProbesKeepExistingNativeHandlesUsable() {
        assumeTrue(Platform.isLinux())
        val libraries =
            try {
                listOf(
                    NativeLibrary.getInstance(LINUX_WEBKIT_SONAME),
                    NativeLibrary.getInstance(LINUX_JAVASCRIPTCORE_SONAME),
                )
            } catch (error: UnsatisfiedLinkError) {
                assumeNoException("WebKitGTK runtime is not installed", error)
                return
            }
        repeat(2) {
            assertTrue(
                isLinuxWebKitAvailable(
                    osName = "Linux",
                    librarySearchPaths = emptyList(),
                    ldconfigOutput = { "$LINUX_WEBKIT_SONAME\n$LINUX_JAVASCRIPTCORE_SONAME" },
                ),
            )
            assertTrue(libraries[0].getFunction("webkit_get_major_version").invokeInt(emptyArray()) > 0)
            libraries[1].getFunction("JSContextGroupCreate")
        }
    }

    @Test
    fun linuxRejectsCachedLibrariesThatCannotLoad() {
        assertFalse(
            isLinuxWebKitAvailable(
                osName = "Linux",
                librarySearchPaths = emptyList(),
                ldconfigOutput = { "$LINUX_WEBKIT_SONAME\n$LINUX_JAVASCRIPTCORE_SONAME" },
                loadLibrary = { throw UnsatisfiedLinkError("Missing dependency") },
            ),
        )
    }
}

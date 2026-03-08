package com.metrolist.shared.api.lyrics.romanizer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JapaneseDictionaryInstallerTest {
    @Test
    fun rejectsChecksumMismatchWithoutLeavingAnInstall() {
        val storage = createTempDirectory()
        try {
            val installer = JapaneseDictionaryInstaller(storage, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, "00")

            assertNull(installer.install())
            assertNull(installer.installedDirectory())
            assertNoStagingDirectories(storage)
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun retriesAfterAFailedDownload() {
        val storage = createTempDirectory()
        try {
            val artifact = archive(*japaneseDictionaryResources.toTypedArray())
            var attempts = 0
            val installer =
                JapaneseDictionaryInstaller(
                    storage,
                    { ByteArrayInputStream(if (attempts++ == 0) byteArrayOf(1, 2, 3) else artifact) },
                    sha256(artifact),
                )

            assertNull(installer.install())
            assertNotNull(installer.install())
            assertEquals(2, attempts)
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun rejectsCorruptAndIncompleteArchives() {
        val storage = createTempDirectory()
        try {
            val corrupt = "not a zip".encodeToByteArray()
            assertNull(installer(storage, corrupt).install())
            assertNull(installer(storage, archive(japaneseDictionaryResources.first())).install())
            assertNoStagingDirectories(storage)
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun installsOnlyAllowlistedDataAndReusesItWhenOffline() {
        val storage = createTempDirectory()
        try {
            val escapedName = "../${storage.name}-escaped.bin"
            val artifact = archive(*japaneseDictionaryResources.toTypedArray(), escapedName, "untrusted.class")
            var downloads = 0
            val installed =
                assertNotNull(
                    JapaneseDictionaryInstaller(storage, {
                        downloads++
                        ByteArrayInputStream(artifact)
                    }, sha256(artifact)).install(),
                )

            assertEquals(1, downloads)
            assertTrue(japaneseDictionaryResources.all { File(installed, it).isFile })
            assertFalse(File(storage.parentFile, "${storage.name}-escaped.bin").exists())
            assertFalse(File(installed, "untrusted.class").exists())
            assertNotNull(JapaneseDictionaryInstaller(storage, { error("offline") }, sha256(artifact)).install())
            assertEquals(1, downloads)
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun removesAbandonedStagingDirectoriesBeforeRetrying() {
        val storage = createTempDirectory()
        try {
            val abandoned = File(storage, ".ipadic-${UUID.randomUUID()}.tmp")
            assertTrue(abandoned.mkdir())
            File(abandoned, "dictionary.jar").writeBytes(byteArrayOf(1, 2, 3))

            assertNotNull(installer(storage, archive(*japaneseDictionaryResources.toTypedArray())).install())
            assertFalse(abandoned.exists())
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun concurrentCallsPerformOneInstall() {
        val storage = createTempDirectory()
        try {
            val artifact = archive(*japaneseDictionaryResources.toTypedArray())
            val downloads = AtomicInteger()
            val start = CountDownLatch(1)
            val results = arrayOfNulls<File>(8)
            val workers =
                results.indices.map { index ->
                    thread {
                        start.await()
                        results[index] =
                            JapaneseDictionaryInstaller(
                                storage,
                                {
                                    downloads.incrementAndGet()
                                    ByteArrayInputStream(artifact)
                                },
                                sha256(artifact),
                            ).install()
                    }
                }
            start.countDown()
            workers.forEach(Thread::join)

            assertEquals(1, downloads.get())
            assertTrue(results.all { it != null && it == results[0] })
        } finally {
            storage.deleteRecursively()
        }
    }

    @Test
    fun interruptedWaiterKeepsItsInterruptWithoutCancellingTheInstaller() {
        val storage = createTempDirectory()
        val downloadStarted = CountDownLatch(1)
        val continueDownload = CountDownLatch(1)
        val artifact = archive(*japaneseDictionaryResources.toTypedArray())
        val installer =
            JapaneseDictionaryInstaller(storage, {
                downloadStarted.countDown()
                continueDownload.await()
                ByteArrayInputStream(artifact)
            }, sha256(artifact))
        var installed: File? = null
        val owner = thread { installed = installer.install() }
        try {
            assertTrue(downloadStarted.await(5, TimeUnit.SECONDS))
            val interrupted = AtomicBoolean()
            var waiterResult: File? = storage
            val waiter =
                thread {
                    Thread.currentThread().interrupt()
                    waiterResult = installer.install()
                    interrupted.set(Thread.currentThread().isInterrupted)
                }
            waiter.join(5_000)
            assertFalse(waiter.isAlive)
            assertNull(waiterResult)
            assertTrue(interrupted.get())
            continueDownload.countDown()
            owner.join(5_000)
            assertFalse(owner.isAlive)
            assertNotNull(installed)
        } finally {
            continueDownload.countDown()
            owner.join(5_000)
            storage.deleteRecursively()
        }
    }

    private fun assertNoStagingDirectories(storage: File) {
        assertFalse(storage.listFiles().orEmpty().any { it.name.startsWith(".ipadic-") && it.name.endsWith(".tmp") })
    }

    private fun installer(
        storage: File,
        artifact: ByteArray,
    ) = JapaneseDictionaryInstaller(storage, { ByteArrayInputStream(artifact) }, sha256(artifact))

    private fun archive(vararg entries: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEachIndexed { index, name ->
                    val entryName =
                        if (name.startsWith("../") ||
                            name.startsWith("com/atilika/")
                        ) {
                            name
                        } else {
                            "com/atilika/kuromoji/ipadic/$name"
                        }
                    zip.putNextEntry(ZipEntry(entryName))
                    zip.write(byteArrayOf(index.toByte(), 42))
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun createTempDirectory(): File = Files.createTempDirectory("kuromoji-test-").toFile()
}

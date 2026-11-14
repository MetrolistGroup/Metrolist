package com.metrolist.shared.api.lyrics.romanizer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.concurrent.withLock

internal const val JAPANESE_DICTIONARY_VERSION = "0.9.0"
internal const val JAPANESE_DICTIONARY_SHA256 = "24909fd751c0b439f7af5131b080eb65bc85062f0c4977ca4a71c76abe74e0b6"
internal const val JAPANESE_DICTIONARY_URL =
    "https://repo.maven.apache.org/maven2/com/atilika/kuromoji/kuromoji-ipadic/0.9.0/kuromoji-ipadic-0.9.0.jar"

private const val MAX_ARTIFACT_BYTES = 20L * 1024 * 1024
private const val MAX_DICTIONARY_BYTES = 40L * 1024 * 1024
private const val MAX_DICTIONARY_FILE_BYTES = 15L * 1024 * 1024
private const val MAX_DOWNLOAD_MILLIS = 90_000L
private const val READY_MARKER = ".ready"
private const val MANIFEST = "manifest"
private const val RESOURCE_ROOT = "com/atilika/kuromoji/ipadic/"

internal val japaneseDictionaryResources =
    setOf(
        "characterDefinitions.bin",
        "connectionCosts.bin",
        "doubleArrayTrie.bin",
        "tokenInfoDictionary.bin",
        "tokenInfoFeaturesMap.bin",
        "tokenInfoPartOfSpeechMap.bin",
        "tokenInfoTargetMap.bin",
        "unknownDictionary.bin",
    )

internal class JapaneseDictionaryInstaller(
    private val storageDirectory: File,
    private val openArtifact: () -> InputStream,
    private val expectedSha256: String = JAPANESE_DICTIONARY_SHA256,
) {
    fun install(): File? {
        installLock.withLock {
            installedDirectory()?.let { return it }
            if (installationInProgress) {
                try {
                    while (installationInProgress) installationFinished.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                return installedDirectory()
            }
            installationInProgress = true
        }
        return try {
            installAtomically()
        } finally {
            installLock.withLock {
                installationInProgress = false
                installationFinished.signalAll()
            }
        }
    }

    fun installedDirectory(): File? = targetDirectory().takeIf(::isInstalled)

    private fun targetDirectory() = File(storageDirectory, "ipadic-$JAPANESE_DICTIONARY_VERSION-${expectedSha256.take(12)}")

    private fun isInstalled(directory: File): Boolean =
        directory.isDirectory &&
            File(directory, READY_MARKER).isFile &&
            File(directory, MANIFEST).let { it.isFile && it.readText() == "$JAPANESE_DICTIONARY_VERSION\n$expectedSha256\n" } &&
            japaneseDictionaryResources.all { name ->
                File(directory, name).let { it.isFile && it.length() in 1..MAX_DICTIONARY_FILE_BYTES }
            }

    private fun installAtomically(): File? {
        if (!storageDirectory.isDirectory && !storageDirectory.mkdirs()) return null
        return try {
            val lockFile = File(storageDirectory, ".ipadic-install.lock")
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    installedDirectory()?.let { return it }
                    removeStaleStagingDirectories()
                    installInStaging()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun installInStaging(): File? {
        val target = targetDirectory()
        val staging = File(storageDirectory, ".ipadic-${UUID.randomUUID()}.tmp")
        try {
            target.deleteRecursively()
            if (!staging.mkdir()) throw IOException("Cannot create temporary dictionary directory")
            val archive = File(staging, "dictionary.jar")
            download(archive)
            extract(archive, staging)
            if (!archive.delete()) throw IOException("Cannot remove downloaded dictionary archive")
            File(staging, MANIFEST).writeText("$JAPANESE_DICTIONARY_VERSION\n$expectedSha256\n")
            File(staging, READY_MARKER).writeText("")
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return target.takeIf(::isInstalled)
        } catch (_: Exception) {
            return null
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun removeStaleStagingDirectories() {
        storageDirectory
            .listFiles()
            .orEmpty()
            .filter(::isInstallerStagingDirectory)
            .forEach(File::deleteRecursively)
    }

    private fun isInstallerStagingDirectory(file: File): Boolean {
        if (!file.isDirectory || !file.name.startsWith(".ipadic-") || !file.name.endsWith(".tmp")) return false
        return runCatching { UUID.fromString(file.name.removePrefix(".ipadic-").removeSuffix(".tmp")) }.isSuccess
    }

    private fun download(destination: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val startedAt = System.nanoTime()
        openArtifact().use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if ((System.nanoTime() - startedAt) / 1_000_000 > MAX_DOWNLOAD_MILLIS) {
                        throw IOException("Dictionary download timed out")
                    }
                    total += read
                    if (total > MAX_ARTIFACT_BYTES) throw IOException("Dictionary archive exceeds size limit")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } != expectedSha256) {
            throw IOException("Dictionary archive checksum mismatch")
        }
    }

    private fun extract(
        archive: File,
        destination: File,
    ) {
        val requiredPaths = japaneseDictionaryResources.map { RESOURCE_ROOT + it }.toSet()
        val entries = mutableMapOf<String, ZipEntry>()
        ZipFile(archive).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.name in requiredPaths && entries.put(entry.name, entry) != null) {
                    throw IOException("Duplicate dictionary entry")
                }
            }
            if (entries.keys != requiredPaths) throw IOException("Dictionary archive is incomplete")
            var total = 0L
            for (name in requiredPaths) {
                val entry = entries.getValue(name)
                if (entry.isDirectory || entry.size !in 1..MAX_DICTIONARY_FILE_BYTES) throw IOException("Invalid dictionary entry")
                val target = File(destination, name.removePrefix(RESOURCE_ROOT))
                zip.getInputStream(entry).use { input ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileSize = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            fileSize += read
                            total += read
                            if (fileSize > MAX_DICTIONARY_FILE_BYTES || total > MAX_DICTIONARY_BYTES) {
                                throw IOException("Extracted dictionary exceeds size limit")
                            }
                            output.write(buffer, 0, read)
                        }
                        if (fileSize != entry.size) throw IOException("Truncated dictionary entry")
                    }
                }
            }
        }
    }

    private companion object {
        val installLock = ReentrantLock()
        val installationFinished = installLock.newCondition()
        var installationInProgress = false
    }
}

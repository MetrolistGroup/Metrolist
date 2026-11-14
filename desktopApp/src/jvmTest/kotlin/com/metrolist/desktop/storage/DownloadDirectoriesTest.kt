package com.metrolist.desktop.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class DownloadDirectoriesTest {
    @Test
    fun damagedDirectoryHistoryDoesNotHideCurrentDownloadsOrAuthorizeOtherPaths() {
        val root = Files.createTempDirectory("download-directories").toFile()
        try {
            val current = root.resolve("current").apply { mkdir() }
            val audio = current.resolve("song.m4a").apply { writeText("audio") }
            val outside = root.resolve("private").apply { writeText("private") }
            val index = root.resolve("directories.json").apply { writeText("damaged") }
            val directories = DownloadDirectories(current, index)
            assertEquals(audio.canonicalFile, directories.safeFile(audio.path, current))
            assertNull(directories.safeFile(outside.path, current))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changingAndResettingKeepsPreviouslyApprovedFilesAfterRestart() {
        val root = Files.createTempDirectory("download-directories").toFile()
        try {
            val default = root.resolve("default").apply { mkdir() }
            val first = root.resolve("first").apply { mkdir() }
            val second = root.resolve("second")
            val audio = first.resolve("song.m4a").apply { writeText("audio") }
            val outside = root.resolve("unapproved").apply { writeText("private") }
            val index = root.resolve("directories.json")
            DownloadDirectories(default, index).rememberChange(first, second)
            val restarted = DownloadDirectories(default, index)
            assertEquals(audio.canonicalFile, restarted.safeFile(audio.path, second))
            restarted.rememberChange(second, default)
            assertEquals(audio.canonicalFile, DownloadDirectories(default, index).safeFile(audio.path, default))
            assertNull(restarted.safeFile(outside.path, default))
            Files.createSymbolicLink(second.resolve("escape").toPath(), outside.toPath())
            assertNull(restarted.safeFile(second.resolve("escape").path, default))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidDestinationDoesNotChangeApprovedDirectories() {
        val root = Files.createTempDirectory("download-directories").toFile()
        try {
            val index = root.resolve("directories.json")
            val invalid = root.resolve("file").apply { writeText("not a directory") }
            val directories = DownloadDirectories(root, index)
            assertFails { directories.rememberChange(root, invalid) }
            assertEquals(false, index.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

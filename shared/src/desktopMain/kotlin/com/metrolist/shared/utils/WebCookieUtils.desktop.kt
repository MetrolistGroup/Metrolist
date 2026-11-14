package com.metrolist.shared.utils

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

actual suspend fun clearWebCookies() {
    val osName = System.getProperty("os.name")
    val userHome = File(System.getProperty("user.home"))
    val (profileDirectories, allowedRoot) =
        when {
            osName.contains("win", ignoreCase = true) -> {
                val localAppData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                val baseDirectory = localAppData?.let(::File) ?: File(userHome, "AppData/Local")
                val appDirectory = File(baseDirectory, "Metrolist")
                listOf(
                    File(appDirectory, "WebView2"),
                    File(appDirectory, "login-helper"),
                    File(appDirectory, "spotify-login-helper"),
                ) to appDirectory
            }

            osName.contains("mac", ignoreCase = true) -> {
                val appDirectory = File(userHome, "Library/Application Support/Metrolist")
                listOf(File(appDirectory, "WebView")) to appDirectory
            }

            else -> {
                val xdgDataHome =
                    System
                        .getenv("XDG_DATA_HOME")
                        ?.takeIf(String::isNotBlank)
                        ?.let(::File)
                        ?.takeIf(File::isAbsolute)
                val baseDirectory = xdgDataHome ?: File(userHome, ".local/share")
                val appDirectory = File(baseDirectory, "Metrolist")
                listOf(File(appDirectory, "WebView")) to appDirectory
            }
        }
    profileDirectories.forEach { deleteWebViewProfileDirectory(it, allowedRoot) }
}

internal fun deleteWebViewProfileDirectory(
    profileDirectory: File,
    allowedRoot: File,
) {
    val homePath = allowedRoot.toPath().toAbsolutePath().normalize()
    val profilePath = profileDirectory.toPath().toAbsolutePath().normalize()
    check(profilePath != homePath && profilePath.startsWith(homePath)) {
        "Refusing to clear a WebView profile outside the app data directory."
    }

    var current = homePath
    for (segment in homePath.relativize(profilePath)) {
        current = current.resolve(segment)
        check(!Files.isSymbolicLink(current)) { "Refusing to clear a WebView profile through a symbolic link." }
    }
    if (!Files.exists(profilePath, LinkOption.NOFOLLOW_LINKS)) return

    Files.walkFileTree(
        profilePath,
        object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(
                file: java.nio.file.Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: java.nio.file.Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        },
    )
    check(!Files.exists(profilePath, LinkOption.NOFOLLOW_LINKS)) { "Unable to clear the persistent WebView login profile." }
}

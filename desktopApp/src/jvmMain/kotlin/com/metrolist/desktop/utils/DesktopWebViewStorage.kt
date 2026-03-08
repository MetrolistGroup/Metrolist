package com.metrolist.desktop.utils

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

internal fun windowsWebViewDataDirectory(
    localAppData: String?,
    userHome: String,
): File {
    val baseDirectory = localAppData?.takeIf(String::isNotBlank)?.let(::File) ?: File(userHome, "AppData/Local")
    return File(baseDirectory, "Metrolist/WebView2")
}

internal fun desktopWebViewDataDirectory(
    osName: String,
    localAppData: String?,
    userHome: String,
    xdgDataHome: String? = null,
): File =
    when {
        osName.contains("win", ignoreCase = true) -> {
            windowsWebViewDataDirectory(localAppData, userHome)
        }

        osName.contains("mac", ignoreCase = true) -> {
            File(userHome, "Library/Application Support/Metrolist/WebView")
        }

        else -> {
            val xdgDirectory = xdgDataHome?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isAbsolute)
            File(xdgDirectory ?: File(userHome, ".local/share"), "Metrolist/WebView")
        }
    }

private fun webViewDataRoot(
    osName: String,
    localAppData: String?,
    userHome: String,
    xdgDataHome: String?,
): File =
    when {
        osName.contains("win", ignoreCase = true) -> {
            localAppData?.takeIf(String::isNotBlank)?.let(::File) ?: File(userHome, "AppData/Local")
        }

        osName.contains("mac", ignoreCase = true) -> {
            File(userHome, "Library/Application Support")
        }

        else -> {
            xdgDataHome?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isAbsolute) ?: File(userHome, ".local/share")
        }
    }

internal fun prepareAppOwnedDirectory(
    directory: File,
    allowedRoot: File,
) {
    val rootPath = allowedRoot.toPath().toAbsolutePath().normalize()
    val directoryPath = directory.toPath().toAbsolutePath().normalize()
    check(directoryPath != rootPath && directoryPath.startsWith(rootPath)) {
        "Refusing to create a WebView profile outside the app data directory."
    }

    Files.createDirectories(rootPath)
    val permissions = PosixFilePermissions.fromString("rwx------")
    val posix = Files.getFileAttributeView(rootPath, PosixFileAttributeView::class.java) != null
    var current: Path = rootPath
    for (segment in rootPath.relativize(directoryPath)) {
        current = current.resolve(segment)
        check(!Files.isSymbolicLink(current)) { "Refusing to use a WebView profile through a symbolic link." }
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) { "The WebView profile path is not a directory." }
        } else {
            if (posix) Files.createDirectory(current, PosixFilePermissions.asFileAttribute(permissions)) else Files.createDirectory(current)
        }
        if (posix) {
            Files.setPosixFilePermissions(current, permissions)
            check(Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS) == permissions)
        }
    }
    check(Files.isWritable(directoryPath)) {
        "Metrolist cannot create a writable WebView data directory at ${directory.absolutePath}"
    }
}

internal fun prepareDesktopWebViewDataDirectory(): File {
    val osName = System.getProperty("os.name")
    val localAppData = System.getenv("LOCALAPPDATA")
    val userHome = System.getProperty("user.home")
    val xdgDataHome = System.getenv("XDG_DATA_HOME")
    val directory =
        desktopWebViewDataDirectory(
            osName = osName,
            localAppData = localAppData,
            userHome = userHome,
            xdgDataHome = xdgDataHome,
        )
    prepareAppOwnedDirectory(directory, webViewDataRoot(osName, localAppData, userHome, xdgDataHome))
    return directory
}

internal fun prepareSpotifyWebViewDataDirectory(): File {
    val osName = System.getProperty("os.name")
    val localAppData = System.getenv("LOCALAPPDATA")
    val userHome = System.getProperty("user.home")
    val xdgDataHome = System.getenv("XDG_DATA_HOME")
    val sharedDirectory = desktopWebViewDataDirectory(osName, localAppData, userHome, xdgDataHome)
    val directory = spotifyWebViewDataDirectory(sharedDirectory)
    prepareAppOwnedDirectory(directory, webViewDataRoot(osName, localAppData, userHome, xdgDataHome))
    return directory
}

internal fun spotifyWebViewDataDirectory(sharedDirectory: File): File = File(sharedDirectory.parentFile, "${sharedDirectory.name}-Spotify")

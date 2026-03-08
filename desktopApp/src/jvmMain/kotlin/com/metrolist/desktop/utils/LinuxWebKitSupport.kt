package com.metrolist.desktop.utils

import com.metrolist.desktop.utils.WaylandScale.LibC
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal const val LINUX_WEBKIT_SONAME = "libwebkit2gtk-4.1.so.0"
internal const val LINUX_JAVASCRIPTCORE_SONAME = "libjavascriptcoregtk-4.1.so.0"

// WebKit constructors can start native threads; keep their code mapped for the process lifetime.
private val linuxWebKitLibraries = ConcurrentHashMap<String, NativeLibrary>()

internal fun configureLinuxWebKitRenderer(
    osName: String = System.getProperty("os.name"),
    setEnvironment: (String, String, Int) -> Int = { name, value, overwrite ->
        Native.load("c", LibC::class.java).setenv(name, value, overwrite)
    },
) {
    if (!osName.lowercase().contains("linux")) return
    check(setEnvironment("WEBKIT_DMABUF_RENDERER_FORCE_SHM", "1", 0) == 0) {
        "Failed to configure WebKitGTK shared-memory buffer transport"
    }
}

/** Probe before composing WebView; missing WebKitGTK 4.1 libraries fail class initialization. */
internal fun isLinuxWebKitAvailable(
    osName: String = System.getProperty("os.name"),
    librarySearchPaths: List<File> = linuxWebKitLibrarySearchPaths(),
    ldconfigOutput: () -> String? = { readLdconfigCache() },
    loadLibrary: (String) -> Unit = { linuxWebKitLibraries.computeIfAbsent(it, NativeLibrary::getInstance) },
): Boolean {
    if (!osName.lowercase().contains("linux")) return true
    val hasWebKit = librarySearchPaths.any { File(it, LINUX_WEBKIT_SONAME).isFile }
    val hasJavaScriptCore = librarySearchPaths.any { File(it, LINUX_JAVASCRIPTCORE_SONAME).isFile }
    if (!hasWebKit || !hasJavaScriptCore) {
        val cache = ldconfigOutput() ?: return false
        if (!cache.contains(LINUX_WEBKIT_SONAME) || !cache.contains(LINUX_JAVASCRIPTCORE_SONAME)) return false
    }
    return try {
        // Present libraries can still fail to load when a transitive system dependency is missing.
        loadLibrary(LINUX_WEBKIT_SONAME)
        loadLibrary(LINUX_JAVASCRIPTCORE_SONAME)
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}

internal fun linuxWebKitLibrarySearchPaths(
    appDir: String? = System.getenv("APPDIR"),
    ldLibraryPath: String? = System.getenv("LD_LIBRARY_PATH"),
    javaLibraryPath: String? = System.getProperty("java.library.path"),
): List<File> {
    val paths = linkedSetOf<File>()
    appDir
        ?.takeIf(String::isNotBlank)
        ?.let { directory ->
            paths += File(directory, "usr/lib")
            paths += File(directory, "usr/lib64")
            paths += File(directory, "lib")
            paths += File(directory, "lib64")
        }
    sequenceOf(ldLibraryPath, javaLibraryPath)
        .filterNotNull()
        .flatMap { it.split(File.pathSeparatorChar) }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::File)
        .forEach { paths += it }
    paths +=
        listOf(
            File("/usr/lib"),
            File("/usr/lib64"),
            File("/usr/lib/x86_64-linux-gnu"),
            File("/usr/lib/aarch64-linux-gnu"),
            File("/lib/x86_64-linux-gnu"),
            File("/lib/aarch64-linux-gnu"),
            File("/lib64"),
            File("/lib"),
        )
    return paths.toList()
}

internal fun linuxWebKitInstallCommand(osRelease: String? = readOsRelease()): String? =
    when {
        osReleaseMatches(osRelease, "arch", "cachyos", "manjaro", "endeavouros", "garuda") -> "sudo pacman -S webkit2gtk-4.1"
        osReleaseMatches(osRelease, "fedora", "rhel", "centos", "nobara") -> "sudo dnf install webkit2gtk4.1"
        osReleaseMatches(osRelease, "opensuse", "suse") -> "sudo zypper install libwebkit2gtk-4_1-0"
        osReleaseMatches(osRelease, "debian", "ubuntu", "linuxmint") -> "sudo apt install libwebkit2gtk-4.1-0"
        else -> null
    }

internal fun osReleaseMatches(
    osRelease: String?,
    vararg tokens: String,
): Boolean {
    if (osRelease.isNullOrBlank()) return false
    val normalized = osRelease.lowercase()
    return tokens.any { token ->
        Regex("""(?m)^id\s*=\s*"?${Regex.escape(token)}"?\s*$""").containsMatchIn(normalized) ||
            Regex("""(?m)^id_like\s*=\s*.*\b${Regex.escape(token)}\b.*$""").containsMatchIn(normalized)
    }
}

internal fun readLdconfigCache(
    timeoutMillis: Long = 2_000,
    startProcess: () -> Process = { ProcessBuilder("ldconfig", "-p").redirectErrorStream(true).start() },
): String? =
    runCatching {
        val process = startProcess()
        val output = StringBuilder()
        val reader =
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { stream ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            val remaining = 1024 * 1024 - output.length
                            if (remaining > 0) output.appendRange(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }.apply {
                isDaemon = true
                name = "WebKitLibraryProbe"
                start()
            }
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) return@runCatching null
            reader.join(100)
            if (!reader.isAlive && process.exitValue() == 0) output.toString() else null
        } finally {
            process.destroyForcibly()
        }
    }.getOrNull()

internal fun readOsRelease(): String? =
    sequenceOf("/etc/os-release", "/usr/lib/os-release")
        .map(::File)
        .firstOrNull(File::isFile)
        ?.readText()

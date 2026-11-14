import groovy.json.JsonOutput
import org.gradle.api.tasks.bundling.Zip
import java.io.File
import java.nio.file.Files

val appVersion = providers.gradleProperty("appVersion").get()

tasks.register("createAppImageLocal") {
    group = "distribution"
    description = "Creates an AppImage using local appimagetool (downloads it if missing)."
    notCompatibleWithConfigurationCache("This task launches appimagetool directly and is intentionally untracked for local packaging runs.")
    dependsOn("packageReleaseAppImage", "patchLinuxLauncher")

    val toolsDir = rootProject.file("tools")
    val appImageTool = rootProject.file("tools/appimagetool.AppImage")
    val packageAppDir = file("${layout.buildDirectory.get()}/compose/binaries/main-release/app/Metrolist")
    val appDir = file("${layout.buildDirectory.get()}/appimage/Metrolist.AppDir")
    val outFile = rootProject.file("dist/Metrolist.AppImage")

    doFirst {
        toolsDir.mkdirs()

        if (!appImageTool.exists()) {
            throw GradleException(
                "tools/appimagetool.AppImage not found. Run ./scripts/build_appimage.sh to download it, or place appimagetool.AppImage in tools/",
            )
        }

        if (!packageAppDir.isDirectory) {
            throw GradleException("Release application directory not found at ${packageAppDir.absolutePath}")
        }
        appDir.deleteRecursively()
        if (!packageAppDir.copyRecursively(appDir, overwrite = true)) {
            throw GradleException("Failed to create the AppImage staging directory")
        }
        packageAppDir
            .walkTopDown()
            .filter { it.isFile && it.canExecute() }
            .forEach { source -> File(appDir, source.relativeTo(packageAppDir).path).setExecutable(true, false) }

        // Compose desktop files can omit the AudioVideo category.
        val desktopFile = file("${appDir.absolutePath}/Metrolist.desktop")
        desktopFile.parentFile.mkdirs()
        desktopFile.writeText(
            """
[Desktop Entry]
Type=Application
Name=Metrolist
Exec=bin/Metrolist %u
Icon=Metrolist
StartupWMClass=Metrolist
Comment=Metrolist music client
Categories=AudioVideo;Player;
Terminal=false
MimeType=x-scheme-handler/metrolist;x-scheme-handler/metrolist-auth;
            """.trimIndent(),
        )

        val appRunFile = file("${appDir.absolutePath}/AppRun")
        appRunFile.writeText(
            "#!/bin/sh\n" +
                $$"""
export MALLOC_ARENA_MAX=4
export MALLOC_MMAP_THRESHOLD_="${MALLOC_MMAP_THRESHOLD_:-131072}"
exec "$APPDIR/bin/Metrolist" "$@"
                """.trimIndent(),
        )
        appRunFile.setExecutable(true, false)

        val appIconFile = file("${appDir.absolutePath}/Metrolist.png")
        val sourceIcon = file("src/desktopMain/resources/logo.png")
        if (!sourceIcon.exists()) {
            throw GradleException("Missing AppImage icon source at ${sourceIcon.absolutePath}")
        }
        sourceIcon.copyTo(appIconFile, overwrite = true)

        // Playback timing requires resources at $APPDIR/resources.
        val resourcesLink = file("${appDir.absolutePath}/resources")
        if (!resourcesLink.exists()) {
            Files.createSymbolicLink(
                resourcesLink.toPath(),
                File("lib/app/resources").toPath(),
            )
        }
        outFile.parentFile.mkdirs()
    }

    doLast {
        val processBuilder =
            ProcessBuilder(appImageTool.absolutePath, "--appimage-extract-and-run", appDir.absolutePath, outFile.absolutePath)
        processBuilder.environment().remove("SOURCE_DATE_EPOCH")
        val process = processBuilder.inheritIO().start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("appimagetool failed with exit code $exitCode")
        }

        outFile.setExecutable(true, false)
    }
}

// Skiko caches these environment variables during JNI_OnLoad, before Kotlin can set them.
tasks.register("patchLinuxLauncher") {
    group = "distribution"
    description = "Wraps the native launcher with a shell script that sets Wayland/cursor env vars."
    notCompatibleWithConfigurationCache("File operations are not cached.")
    mustRunAfter(tasks.matching { it.name == "createReleaseDistributable" || it.name == "packageReleaseAppImage" })

    val packageAppDir = file("${layout.buildDirectory.get()}/compose/binaries/main-release/app/Metrolist")
    val linkedRuntimeDirectory = layout.buildDirectory.dir("compose/tmp/main-release/runtime")
    dependsOn("createReleaseRuntimeImage")

    doLast {
        val binDir = packageAppDir.resolve("bin")
        val launcher = binDir.resolve("Metrolist")
        val realBinary = binDir.resolve("Metrolist.bin")
        val libAppDir = packageAppDir.resolve("lib/app")
        val cfgFile = libAppDir.resolve("Metrolist.cfg")
        val newCfgFile = binDir.resolve("Metrolist.bin.cfg")

        fun patchCfgFile(file: File) {
            if (!file.exists()) return
            val lines = file.readLines()
            var classpathReplaced = false
            val patchedLines = mutableListOf<String>()
            for (line in lines) {
                if (line.startsWith("app.classpath=")) {
                    if (!classpathReplaced) {
                        patchedLines.add($$"app.classpath=$APPDIR/*")
                        classpathReplaced = true
                    }
                } else {
                    patchedLines.add(line)
                }
            }
            file.writeText(patchedLines.joinToString("\n") + "\n")
        }

        if (!launcher.exists()) {
            logger.warn("patchLinuxLauncher: ${launcher.absolutePath} not found, skipping.")
            return@doLast
        }
        // AppImage packaging must use the configured runtime instead of linking another one.
        val archivePath = "lib/server/classes_coh.jsa"
        val linkedRuntime = linkedRuntimeDirectory.get().asFile
        val packagedRuntime = packageAppDir.resolve("lib/runtime")
        val baseArchive = linkedRuntime.resolve(archivePath)
        check(baseArchive.isFile && baseArchive.length() > 0) { "Linked runtime is missing its base CDS archive" }
        val packagedArchive = packagedRuntime.resolve(archivePath)
        check(packagedArchive.isFile) { "Packaged runtime is missing its base CDS archive" }
        check(Files.mismatch(linkedRuntime.resolve("lib/modules").toPath(), packagedRuntime.resolve("lib/modules").toPath()) == -1L) {
            "Packaged runtime differs from the configured linked runtime"
        }
        check(Files.mismatch(baseArchive.toPath(), packagedArchive.toPath()) == -1L) {
            "Packaged CDS archive differs from the linked runtime"
        }
        if (!realBinary.exists()) {
            check(launcher.renameTo(realBinary)) { "Could not rename the Linux launcher" }
        }

        if (cfgFile.exists()) {
            patchCfgFile(cfgFile)
            cfgFile.copyTo(newCfgFile, overwrite = true)
            cfgFile.copyTo(libAppDir.resolve("Metrolist.bin.cfg"), overwrite = true)
        } else {
            patchCfgFile(newCfgFile)
            patchCfgFile(libAppDir.resolve("Metrolist.bin.cfg"))
        }

        // Write the wrapper script
        launcher.writeText(
            "#!/bin/sh\n" +
                $$"""
# ── Cursor / rendering env vars ──────────────────────────────────────────────
# Skiko caches these at JNI_OnLoad, so they MUST be set before the JVM starts.

# Wayland support in Skiko
export SKIKO_WAYLAND="${SKIKO_WAYLAND:-1}"

# Embedded GTK login windows are X11 children of AWT windows
export GDK_BACKEND="${GDK_BACKEND:-x11}"

# Prevent AWT from reparenting (needed for some tiling WMs)
export _JAVA_AWT_WM_NONREPARENTING="${_JAVA_AWT_WM_NONREPARENTING:-1}"

# Limit glibc malloc arenas to reduce memory fragmentation
export MALLOC_ARENA_MAX="${MALLOC_ARENA_MAX:-4}"
# Return large freed native buffers to the OS rather than retaining them in malloc arenas.
export MALLOC_MMAP_THRESHOLD_="${MALLOC_MMAP_THRESHOLD_:-131072}"

# ── WebView compatibility (fix black screen / GBM errors) ────────────────────
# WebKitGTK (used by Wry) often fails with GBM errors on certain drivers/Wayland; users can set either to 0.
export WEBKIT_DISABLE_DMABUF_RENDERER="${WEBKIT_DISABLE_DMABUF_RENDERER:-1}"
export WEBKIT_DISABLE_COMPOSITING_MODE="${WEBKIT_DISABLE_COMPOSITING_MODE:-1}"

# ── Cursor theme / size detection ────────────────────────────────────────────
# Skip discovery if coreutils timeout is unavailable; never block startup indefinitely.
probe_cursor() {
    command -v timeout >/dev/null 2>&1 || return 1
    timeout --kill-after=1s 2s "$@" 2>/dev/null
}
if [ -z "$XCURSOR_THEME" ]; then
    # Try Hyprland
    CURSOR_THEME="$(probe_cursor hyprctl getoption cursor:theme | sed -n 's/.*string: *//p')"
    if [ -z "$CURSOR_THEME" ]; then
        # Try GNOME / gsettings
        CURSOR_THEME="$(probe_cursor gsettings get org.gnome.desktop.interface cursor-theme | tr -d "'")"
    fi
    if [ -n "$CURSOR_THEME" ]; then
        export XCURSOR_THEME="$CURSOR_THEME"
        export GDK_CURSOR_THEME="$CURSOR_THEME"
    fi
fi

if [ -z "$XCURSOR_SIZE" ]; then
    # Try Hyprland
    CURSOR_SIZE="$(probe_cursor hyprctl getoption cursor:size | sed -n 's/.*int: *//p')"
    if [ -z "$CURSOR_SIZE" ]; then
        # Try GNOME / gsettings
        CURSOR_SIZE="$(probe_cursor gsettings get org.gnome.desktop.interface cursor-size)"
    fi
    if [ -n "$CURSOR_SIZE" ] && [ "$CURSOR_SIZE" -eq "$CURSOR_SIZE" ] 2>/dev/null; then
        export XCURSOR_SIZE="$CURSOR_SIZE"
    fi
fi

export METROLIST_CURSOR_CONFIGURED=1

# Keep archives outside read-only installations, including AppImage mounts and Flatpak's /app.
case "$XDG_CACHE_HOME" in
    /*) METROLIST_CDS_CACHE="$XDG_CACHE_HOME/metrolist" ;;
    *) METROLIST_CDS_CACHE="$HOME/.cache/metrolist" ;;
esac
export METROLIST_CDS_CACHE

case "$XDG_STATE_HOME" in
    /*) METROLIST_LOG_DIR="$XDG_STATE_HOME/metrolist/logs" ;;
    *) METROLIST_LOG_DIR="$HOME/.local/state/metrolist/logs" ;;
esac
METROLIST_JVM_ERROR_FILE="$(printf '%s' "$METROLIST_LOG_DIR" | sed 's/%/%%/g')/hs_err_pid%p.log"
export METROLIST_JVM_ERROR_FILE

# ── Launch ───────────────────────────────────────────────────────────────────
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
exec "$DIR/Metrolist.bin" "$@"
                """.trimIndent(),
        )
        launcher.setExecutable(true, false)
        logger.lifecycle("patchLinuxLauncher: wrapped launcher with env-var setup.")
    }
}

tasks.matching { it.name == "packageReleaseAppImage" || it.name == "createReleaseDistributable" }.configureEach {
    finalizedBy("patchLinuxLauncher")
}

fun registerWindowsPortableArchive(
    taskName: String,
    buildType: String,
    distributableTask: String,
    appDirectory: String,
    archiveName: String,
) {
    val manifestFile = layout.buildDirectory.file("windows-portable/$buildType/install-manifest.json")
    val manifestVersion = appVersion
    val generateManifest =
        tasks.register("generate${buildType}WindowsInstallManifest") {
            inputs.property("version", manifestVersion)
            outputs.file(manifestFile)
            doLast {
                val output = manifestFile.get().asFile
                output.parentFile.mkdirs()
                val manifest =
                    mapOf(
                        "schema" to 1,
                        "appId" to "com.metrolist.desktop",
                        "version" to manifestVersion,
                        "platform" to "windows",
                        "architecture" to "x86_64",
                        "launcher" to "Metrolist.exe",
                    )
                output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
            }
        }
    val appDir = layout.buildDirectory.dir("compose/binaries/$appDirectory/app/Metrolist")
    tasks.register<Zip>(taskName) {
        group = "distribution"
        description = "Assembles the $buildType portable Windows ZIP."
        notCompatibleWithConfigurationCache("This task validates the native Windows distribution at execution time.")
        dependsOn(generateManifest)
        if (org.gradle.internal.os.OperatingSystem
                .current()
                .isWindows
        ) {
            dependsOn(distributableTask)
        }
        destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
        archiveFileName.set(archiveName)
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
        from(appDir) {
            exclude("install-manifest.json")
        }
        from(manifestFile)

        doFirst {
            if (!org.gradle.internal.os.OperatingSystem
                    .current()
                    .isWindows
            ) {
                throw GradleException("$taskName can only run on Windows")
            }
            val directory = appDir.get().asFile
            if (!directory.isDirectory || !directory.resolve("Metrolist.exe").isFile) {
                throw GradleException("Windows application directory is incomplete at ${directory.absolutePath}")
            }
            val requiredLoginHelperResources =
                setOf(
                    "metro-wv2-helper.exe",
                    "metro-wv2-helper.exe.sha256",
                )
            val packagedFiles =
                directory
                    .walkTopDown()
                    .filter(File::isFile)
                    .map { it.relativeTo(directory).invariantSeparatorsPath }
                    .toSet()
            requiredLoginHelperResources.forEach { requiredPath ->
                val resourceSuffixes = setOf("resources/$requiredPath", "resources/windows/$requiredPath")
                if (packagedFiles.none { packagedFile -> resourceSuffixes.any(packagedFile::endsWith) }) {
                    throw GradleException("Windows application directory is missing resources/$requiredPath")
                }
            }
        }
    }
}

registerWindowsPortableArchive(
    taskName = "assembleWindowsRelease",
    buildType = "Release",
    distributableTask = "createReleaseDistributable",
    appDirectory = "main-release",
    archiveName = "Metrolist-Desktop-Windows.zip",
)

registerWindowsPortableArchive(
    taskName = "assembleWindowsDebug",
    buildType = "Debug",
    distributableTask = "createDistributable",
    appDirectory = "main",
    archiveName = "Metrolist-Desktop-Windows-Debug.zip",
)

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import java.io.OutputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile

// Packaging and integrity tasks stay in applied scripts, not buildSrc: its ephemeral
// CI outputs otherwise invalidate the configuration cache between builds.
val currentOperatingSystem =
    org.gradle.internal.os.OperatingSystem
        .current()
val isArm64 =
    providers.gradleProperty("metrolist.desktop.arch").orElse(System.getProperty("os.arch")).get().lowercase().let {
        it.contains("aarch64") || it.contains("arm64")
    }
val platform =
    when {
        currentOperatingSystem.isWindows -> "win"
        currentOperatingSystem.isMacOsX -> "mac"
        currentOperatingSystem.isLinux -> "linux"
        else -> error("Unknown OS")
    }
val jnaPlatform =
    when (platform) {
        "win" -> if (isArm64) "win32-aarch64" else "win32-x86-64"
        "mac" -> if (isArm64) "darwin-aarch64" else "darwin-x86-64"
        else -> if (isArm64) "linux-aarch64" else "linux-x86-64"
    }
val sqliteNativePath =
    when (platform) {
        "win" -> "Windows/${if (isArm64) "aarch64" else "x86_64"}"
        "mac" -> "Mac/${if (isArm64) "aarch64" else "x86_64"}"
        else -> "Linux/${if (isArm64) "aarch64" else "x86_64"}"
    }

abstract class PatchDmgAppearanceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val backgroundFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val iconFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dmgDirectory: DirectoryProperty

    @TaskAction
    fun patch() {
        val os =
            org.gradle.internal.os.OperatingSystem
                .current()
        if (!os.isMacOsX) return

        val background = backgroundFile.get().asFile
        val icon = iconFile.get().asFile
        val dmgDir = dmgDirectory.get().asFile
        check(background.isFile && icon.isFile && dmgDir.isDirectory) { "Background image, icon or DMG directory is missing" }

        dmgDir
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("dmg", ignoreCase = true) }
            .forEach { dmgFile ->
                patchDmg(dmgFile, background, icon)
            }
    }

    private fun runTool(vararg command: String) {
        val exit = ProcessBuilder(*command).inheritIO().start().waitFor()
        check(exit == 0) { "${command.first()} failed with exit code $exit while patching the DMG appearance" }
    }

    private fun patchDmg(
        dmgFile: File,
        backgroundImage: File,
        iconFile: File,
    ) {
        val workDir =
            Files
                .createTempDirectory(dmgFile.parentFile.toPath(), "metrolist-bg-")
                .toFile()
        val rwDmg = File(workDir, "writable.dmg")
        val patchedDmg = File(workDir, "patched.dmg")
        val mountDir = File(workDir, "mount").apply { mkdir() }
        var mounted = false
        try {
            runTool("hdiutil", "convert", dmgFile.absolutePath, "-format", "UDRW", "-o", rwDmg.absolutePath)
            // A failed attach may leave a mount behind; retain it unless detach succeeds.
            mounted = true
            runTool("hdiutil", "attach", rwDmg.absolutePath, "-mountpoint", mountDir.absolutePath, "-nobrowse")
            try {
                val bgDir = File(mountDir, ".background").apply { mkdirs() }
                val bgTiff = File(bgDir, "background.tiff")
                bgTiff.setWritable(true)
                if (backgroundImage.extension.equals("tiff", ignoreCase = true) ||
                    backgroundImage.extension.equals("tif", ignoreCase = true)
                ) {
                    backgroundImage.copyTo(bgTiff, overwrite = true)
                } else {
                    runTool("sips", "-s", "format", "tiff", backgroundImage.absolutePath, "--out", bgTiff.absolutePath)
                }

                val mountPath = mountDir.absolutePath
                val bgTiffPath = bgTiff.absolutePath
                val appleScript =
                    """
                    tell application "Finder"
                      set targetDisk to disk of (POSIX file "$mountPath" as alias)
                      open targetDisk
                      set theWindow to container window of targetDisk
                      set current view of theWindow to icon view
                      try
                        set toolbar visible of theWindow to false
                      end try
                      try
                        set statusbar visible of theWindow to false
                      end try
                      -- Window sized to 660 × 428 so content area below the ~28pt title bar is 660 × 400.
                      set the bounds of theWindow to {400, 100, 1060, 528}
                      set theViewOptions to the icon view options of theWindow
                      set arrangement of theViewOptions to not arranged
                      set icon size of theViewOptions to 128
                      set background picture of theViewOptions to POSIX file "$bgTiffPath"
                      -- Positions match the artwork blob centres (left app, right Applications).
                      set position of item "Metrolist.app" of targetDisk to {163, 200}
                      set position of item "Applications" of targetDisk to {497, 189}
                      update targetDisk without registering applications
                      delay 5
                      close theWindow
                    end tell
                    """.trimIndent()

                val scriptFile = File(workDir, "setup-bg.scpt").apply { writeText(appleScript) }
                runTool("osascript", scriptFile.absolutePath)
                val volumeIcon = File(mountDir, ".VolumeIcon.icns")
                volumeIcon.setWritable(true)
                iconFile.copyTo(volumeIcon, overwrite = true)
                val setFileBin = if (File("/usr/bin/SetFile").exists()) "/usr/bin/SetFile" else "SetFile"
                runTool(setFileBin, "-c", "icnC", volumeIcon.absolutePath)
                runTool(setFileBin, "-a", "C", mountDir.absolutePath)
                volumeIcon.setWritable(false)
            } finally {
                for (attempt in 1..5) {
                    val exit =
                        ProcessBuilder("hdiutil", "detach", mountDir.absolutePath, "-force")
                            .inheritIO()
                            .start()
                            .waitFor()
                    if (exit == 0) {
                        mounted = false
                        break
                    }
                    if (attempt < 5) Thread.sleep(1000)
                }
                check(!mounted) { "Could not detach DMG at ${mountDir.absolutePath}" }
            }
            runTool("hdiutil", "convert", rwDmg.absolutePath, "-format", "ULMO", "-o", patchedDmg.absolutePath)
            val script =
                """
                import Cocoa
                guard let image = NSImage(contentsOfFile: CommandLine.arguments[1]),
                      NSWorkspace.shared.setIcon(image, forFile: CommandLine.arguments[2]) else {
                    exit(1)
                }
                """.trimIndent()
            runTool("swift", "-e", script, iconFile.absolutePath, patchedDmg.absolutePath)
            Files.move(patchedDmg.toPath(), dmgFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            // Never recursively delete a mount whose detach failed.
            if (!mounted) workDir.deleteRecursively()
        }
    }
}

if (platform == "mac") {
    listOf("" to "main", "Release" to "main-release").forEach { (variant, directory) ->
        val patchAppearance =
            tasks.register<PatchDmgAppearanceTask>("patchPackage${variant}DmgAppearance") {
                backgroundFile.set(layout.projectDirectory.file("src/desktopMain/resources/installer_background.tiff"))
                iconFile.set(layout.projectDirectory.file("src/desktopMain/resources/logo.icns"))
                dmgDirectory.set(layout.buildDirectory.dir("compose/binaries/$directory/dmg"))
            }
        tasks.matching { it.name == "package${variant}Dmg" }.configureEach {
            finalizedBy(patchAppearance)
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn("syncExternalResources")
}

tasks
    .matching {
        it.name == "build" ||
            it.name == "createReleaseDistributable" ||
            it.name.startsWith("packageRelease")
    }.configureEach {
        if (providers.gradleProperty("metrolist.verifyPackaging").map(String::toBooleanStrict).getOrElse(true)) {
            dependsOn(":shared:verifyDesktopJarIntegrity")
        }
    }

private class VerifyReleaseDistributableAction(
    private val appDirectory: File,
    private val desktopClasses: File,
    private val sharedClasses: File,
    private val expectedSqlitePrefix: String,
    private val keptNativePrefixes: List<String>,
    private val expectedFfmpegPrefix: String,
    private val jniPrefix: String,
    private val jniExtension: String,
    private val platform: String,
    private val requiredArm64WebView: String?,
) : Action<Task> {
    override fun execute(task: Task) {
        val root = appDirectory
        val runtimeRelease =
            root.walkTopDown().single { it.name == "release" && it.parentFile.resolve("lib/modules").isFile }
        val runtimeProperties = Properties().apply { runtimeRelease.inputStream().use(::load) }
        check(Runtime.Version.parse(runtimeProperties.getProperty("JAVA_VERSION").trim('"')).feature() == 25) {
            "Release distributable must bundle Java 25"
        }
        val baseArchive = runtimeRelease.parentFile.resolve("lib/server/classes_coh.jsa")
        val windowsBaseArchive = runtimeRelease.parentFile.resolve("bin/server/classes_coh.jsa")
        check(listOf(baseArchive, windowsBaseArchive).any { it.isFile && it.length() > 0 }) {
            "Release distributable must contain a base CDS archive"
        }
        val launcherConfig = root.walkTopDown().single { it.name == "Metrolist.cfg" }.readText()
        listOf(
            "-Xshare:auto",
            "-XX:+VerifySharedSpaces",
            "-XX:+AutoCreateSharedArchive",
            "-XX:SharedArchiveFile=",
            "-Dmetrolist.cds.cache=",
        ).forEach { option ->
            check(launcherConfig.contains("java-options=$option")) { "Release launcher is missing $option" }
        }
        val jars = root.walkTopDown().filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }.toList()
        if (jars.isEmpty()) throw GradleException("Release distributable contains no JARs at ${root.absolutePath}")

        check(jars.none { it.name.contains("quickjs", ignoreCase = true) }) {
            "Release distributable must not contain QuickJS JARs"
        }
        check(jars.none { it.name.startsWith("kotlin-reflect-") }) {
            "Release distributable must not contain kotlin-reflect"
        }
        check(jars.none { it.name.startsWith("pinyin-") || it.name.startsWith("nlp-common-") || it.name.startsWith("heaven-") }) {
            "Release distributable must use the in-tree Mandarin table"
        }

        URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            listOf(
                "com.atilika.kuromoji.viterbi.ViterbiNode\$Type",
                "javax.jmdns.ServiceInfo\$Fields",
            ).forEach { className ->
                check(loader.loadClass(className).enumConstants?.isNotEmpty() == true) {
                    "Release enum $className must retain values() for EnumMap reflection"
                }
            }
        }

        val packagedEntries = mutableSetOf<String>()
        jars.forEach { jar ->
            ZipFile(jar).use { archive ->
                val entries = archive.entries().asSequence().toList()
                entries.forEach { packagedEntries += it.name }
                entries.filterNot { it.isDirectory }.forEach { entry ->
                    archive.getInputStream(entry).use { it.copyTo(OutputStream.nullOutputStream()) }
                }
            }
        }
        check(packagedEntries.none { it.contains("quickjs", ignoreCase = true) }) {
            "Release distributable must not contain QuickJS classes or natives"
        }
        check("kotlin/reflect/jvm/internal/ReflectionFactoryImpl.class" !in packagedEntries) {
            "Release distributable must not contain repackaged kotlin-reflect classes"
        }
        check("romanization/mandarin.bin" in packagedEntries) {
            "Release distributable is missing the Mandarin table"
        }
        if (requiredArm64WebView != null && requiredArm64WebView !in packagedEntries) {
            throw GradleException("Release distributable is missing the ARM64 WebView native library $requiredArm64WebView")
        }
        val sqliteNatives = packagedEntries.filter { it.startsWith("org/sqlite/native/") && !it.endsWith('/') }
        if (sqliteNatives.isEmpty() || sqliteNatives.any { !it.startsWith(expectedSqlitePrefix) }) {
            throw GradleException("Release distributable must contain only $expectedSqlitePrefix SQLite natives")
        }
        val ffmpegNatives =
            packagedEntries.filter {
                it.startsWith("org/bytedeco/ffmpeg/") && it.matches(Regex(".*\\.(so(\\..*)?|dylib|dll)$"))
            }
        check(ffmpegNatives.isNotEmpty() && ffmpegNatives.all { it.startsWith(expectedFfmpegPrefix) }) {
            "Release distributable must contain only $expectedFfmpegPrefix FFmpeg natives"
        }
        for (library in listOf("avutil", "swresample", "avcodec", "avformat", "swscale", "avfilter", "avdevice")) {
            check("$expectedFfmpegPrefix$jniPrefix$library.$jniExtension" in packagedEntries) {
                "Release distributable is missing the FFmpeg $library JNI library"
            }
            val libraryPrefix = "$expectedFfmpegPrefix${if (platform == "win") "" else "lib"}$library"
            check(ffmpegNatives.any { it.startsWith(libraryPrefix) }) {
                "Release distributable is missing the FFmpeg $library shared library"
            }
        }
        val foreignNatives =
            packagedEntries.filter { entry ->
                entry.matches(Regex(".*\\.(so|dll|dylib|jnilib)$")) &&
                    (
                        entry.startsWith("com/sun/jna/") || entry.startsWith("jni/") || entry.contains("composewebview_wry") ||
                            entry.startsWith("nucleus/native/") || entry.startsWith("composetray/native/")
                    ) &&
                    keptNativePrefixes.none(entry::startsWith)
            }
        check(foreignNatives.isEmpty()) {
            "Release distributable must not contain other platforms' natives: ${foreignNatives.take(5)}"
        }
        val nucleusPlatform = keptNativePrefixes.single { it.startsWith("nucleus/native/") }
        val trayPlatform = keptNativePrefixes.single { it.startsWith("composetray/native/") }
        val requiredNatives =
            when (platform) {
                "win" -> listOf("nucleus_windows_theme.dll", "nucleus_share.dll", "nucleus_media_control_windows.dll")
                "mac" -> listOf("libnucleus_darkmode.dylib", "libnucleus_share.dylib", "libnucleus_media_control_macos.dylib")
                else -> listOf("libnucleus_linux_theme.so", "libnucleus_share.so", "libnucleus_media_control_linux.so")
            }
        requiredNatives.forEach { native ->
            check("$nucleusPlatform$native" in packagedEntries) { "Release distributable is missing $nucleusPlatform$native" }
        }
        val trayNative =
            when (platform) {
                "win" -> "WinTray.dll"
                "mac" -> "libMacTray.dylib"
                else -> "libLinuxTray.so"
            }
        check("$trayPlatform$trayNative" in packagedEntries) { "Release distributable is missing $trayPlatform$trayNative" }
        if (platform == "win") {
            check("${nucleusPlatform}nucleus_launcher_windows.dll" in packagedEntries) {
                "Release distributable is missing the Windows taskbar native library"
            }
        }
        check(listOf("ffmpeg", "ffprobe", "ffmpeg.exe", "ffprobe.exe").none { "$expectedFfmpegPrefix$it" in packagedEntries }) {
            "Release distributable must not contain unused FFmpeg command-line tools"
        }
        val compiledClasses =
            listOf(desktopClasses, sharedClasses)
                .flatMap { classesDirectory ->
                    classesDirectory
                        .walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .map { it.relativeTo(classesDirectory).invariantSeparatorsPath }
                        .toList()
                }.toSet()
        if (compiledClasses.isEmpty()) throw GradleException("Desktop compilation produced no application classes")
        val missingClasses = compiledClasses - packagedEntries
        if (missingClasses.isNotEmpty()) {
            throw GradleException("Release distributable is missing ${missingClasses.size} compiled application classes")
        }
    }
}

val verifyReleaseDistributableJars =
    tasks.register("verifyReleaseDistributableJars") {
        group = "verification"
        description = "Verifies the Java 25 runtime, packaged JAR entries, and compiled application classes."
        dependsOn("createReleaseDistributable")
        val appDirectory = layout.buildDirectory.dir("compose/binaries/main-release/app")
        val desktopClasses = layout.buildDirectory.dir("classes/kotlin/jvm/main")
        val sharedClasses = project(":shared").layout.buildDirectory.dir("classes/kotlin/desktop/main")
        inputs.dir(appDirectory)
        inputs.dir(desktopClasses)
        inputs.dir(sharedClasses)

        val expectedSqlitePrefix = "org/sqlite/native/$sqliteNativePath/"
        val nucleusPlatform = jnaPlatform.replace("x86-64", "x64")
        val keptNativePrefixes =
            listOf("com/sun/jna/$jnaPlatform/", "$jnaPlatform/", "nucleus/native/$nucleusPlatform/", "composetray/native/$nucleusPlatform/")
        val ffmpegPlatform =
            when (platform) {
                "win" -> "windows-${if (isArm64) "arm64" else "x86_64"}"
                "mac" -> "macosx-${if (isArm64) "arm64" else "x86_64"}"
                else -> "linux-${if (isArm64) "arm64" else "x86_64"}"
            }
        val expectedFfmpegPrefix = "org/bytedeco/ffmpeg/$ffmpegPlatform/"
        val jniPrefix = if (platform == "win") "jni" else "libjni"
        val jniExtension =
            if (platform == "win") {
                "dll"
            } else if (platform == "mac") {
                "dylib"
            } else {
                "so"
            }
        val requiredArm64WebView =
            when {
                !isArm64 -> null
                platform == "linux" -> "linux-aarch64/libcomposewebview_wry.so"
                platform == "win" -> "win32-aarch64/composewebview_wry.dll"
                else -> null
            }

        doLast(
            VerifyReleaseDistributableAction(
                appDirectory = appDirectory.get().asFile,
                desktopClasses = desktopClasses.get().asFile,
                sharedClasses = sharedClasses.get().asFile,
                expectedSqlitePrefix = expectedSqlitePrefix,
                keptNativePrefixes = keptNativePrefixes,
                expectedFfmpegPrefix = expectedFfmpegPrefix,
                jniPrefix = jniPrefix,
                jniExtension = jniExtension,
                platform = platform,
                requiredArm64WebView = requiredArm64WebView,
            ),
        )
    }

tasks
    .matching { it.name in setOf("assembleWindowsRelease", "packageReleaseDmg", "packageReleaseAppImage") }
    .configureEach {
        if (providers.gradleProperty("metrolist.verifyPackaging").map(String::toBooleanStrict).getOrElse(true)) {
            dependsOn(verifyReleaseDistributableJars)
        }
    }

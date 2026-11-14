import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.WriteProperties
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Adoptium publishes no Windows ARM64 JDK 25; Microsoft's OpenJDK build is the one available there.
val jdkVendor =
    if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") {
        JvmVendorSpec.MICROSOFT
    } else {
        JvmVendorSpec.ADOPTIUM
    }

val currentOperatingSystem =
    org.gradle.internal.os.OperatingSystem
        .current()
val targetArchitecture =
    providers
        .gradleProperty("metrolist.desktop.arch")
        .orElse(System.getProperty("os.arch"))
        .get()
        .lowercase()
require(targetArchitecture in setOf("aarch64", "arm64", "x86_64", "amd64")) { "Unsupported desktop architecture: $targetArchitecture" }
val isArm64 = targetArchitecture.contains("aarch64") || targetArchitecture.contains("arm64")
val platformClassifier =
    when {
        currentOperatingSystem.isWindows -> "win"
        currentOperatingSystem.isMacOsX -> if (isArm64) "mac-aarch64" else "mac"
        currentOperatingSystem.isLinux -> if (isArm64) "linux-aarch64" else "linux"
        else -> error("Unknown OS")
    }
val sqliteNativePath =
    when {
        currentOperatingSystem.isWindows -> "Windows/${if (isArm64) "aarch64" else "x86_64"}"
        currentOperatingSystem.isMacOsX -> "Mac/${if (isArm64) "aarch64" else "x86_64"}"
        currentOperatingSystem.isLinux -> "Linux/${if (isArm64) "aarch64" else "x86_64"}"
        else -> error("Unknown OS")
    }
val platform =
    when {
        currentOperatingSystem.isWindows -> "win"
        currentOperatingSystem.isMacOsX -> "mac"
        currentOperatingSystem.isLinux -> "linux"
        else -> error("Unknown OS")
    }

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("org.jetbrains.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

compose {
    dependencyCompatibility {
        exclude("io.coil-kt.coil3")
    }
}

configurations.matching { it.name.startsWith("jvm") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    // Ktor's JSON converter links its OpenAPI schema model, whose YAML adapter is never used here.
    exclude(group = "com.charleskorn.kaml")
    exclude(group = "it.krzeminski")
}

group = "com.metrolist"
val appVersion = providers.gradleProperty("appVersion").get()
version = appVersion

val desktopMemoryJvmArgs =
    listOf(
        "-XX:MaxDirectMemorySize=128m",
        "-Xms32m",
        "-Xmx384m",
        "-XX:+UseG1GC",
        "-XX:MinHeapFreeRatio=10",
        "-XX:MaxHeapFreeRatio=20",
        "-XX:+ExplicitGCInvokesConcurrent",
        "-XX:ParallelGCThreads=2",
        "-XX:ConcGCThreads=1",
        "-XX:TieredStopAtLevel=1",
        "-XX:CICompilerCount=1",
        "-XX:ReservedCodeCacheSize=32m",
        "-XX:TrimNativeHeapInterval=5000",
        "-XX:+UseCompactObjectHeaders",
        "-Djdk.nio.maxCachedBufferSize=262144",
        "-Dskiko.gpu.resourceCacheLimit=64m",
        "-Dorg.bytedeco.javacpp.maxPhysicalBytes=0",
        "-Dorg.bytedeco.javacpp.maxBytes=0",
    )

interface ArchiveOps {
    @get:Inject
    val archiveOperations: ArchiveOperations
}
val archiveOps = objects.newInstance<ArchiveOps>().archiveOperations

val sqliteJdbcSource = configurations.create("sqliteJdbcSource") { isTransitive = false }
dependencies.add(sqliteJdbcSource.name, libs.sqlite.jdbc)
val sqliteJdbcSourceJar = sqliteJdbcSource.elements.map { files -> files.single().asFile }
val platformSqliteJdbc =
    tasks.register<Jar>("platformSqliteJdbc") {
        archiveFileName.set("sqlite-jdbc-${libs.versions.sqliteJdbc.get()}-$platformClassifier.jar")
        destinationDirectory.set(layout.buildDirectory.dir("platform-libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest.attributes["Multi-Release"] = "true"
        from(sqliteJdbcSourceJar.map(archiveOps::zipTree)) {
            exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "org/sqlite/native/**")
        }
        from(sqliteJdbcSourceJar.map(archiveOps::zipTree)) {
            include("org/sqlite/native/$sqliteNativePath/**")
        }
    }

val jnaPlatform =
    when (platform) {
        "win" -> if (isArm64) "win32-aarch64" else "win32-x86-64"
        "mac" -> if (isArm64) "darwin-aarch64" else "darwin-x86-64"
        else -> if (isArm64) "linux-aarch64" else "linux-x86-64"
    }
tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    inputs.property("desktopArchitecture", targetArchitecture)
    val keptNatives =
        mapOf(
            "jna-5" to "com/sun/jna/$jnaPlatform/",
            "wrywebview-" to "$jnaPlatform/",
            "nucleus." to "nucleus/native/${jnaPlatform.replace("x86-64", "x64")}/",
            "share-jvm" to "nucleus/native/${jnaPlatform.replace("x86-64", "x64")}/",
            "ComposeNativeTray-jvm" to "composetray/native/${jnaPlatform.replace("x86-64", "x64")}/",
        )
    val outputFiles = outputs.files
    // doLast must not read script-level values; the configuration cache cannot store the script object.
    val macHost = currentOperatingSystem.isMacOsX
    val targetArm64 = isArm64
    doLast {
        val nativeExtensions = setOf("so", "dll", "dylib", "jnilib")
        outputFiles.asFileTree.matching { include("*.jar") }.forEach { jar ->
            val isSkikoRuntime =
                macHost &&
                    targetArm64 != (System.getProperty("os.arch") in setOf("aarch64", "arm64")) &&
                    jar.name.startsWith("skiko-") && jar.name.contains("-awt-runtime")
            val keep = keptNatives.entries.firstOrNull { jar.name.startsWith(it.key) }?.value
            if (keep == null && !isSkikoRuntime) return@forEach
            val skikoPlatform = "macos-${if (targetArm64) "arm64" else "x64"}"
            val stripped = File(jar.parentFile, "${jar.name}.tmp")
            ZipFile(jar).use { source ->
                if (isSkikoRuntime) {
                    check(source.getEntry("libskiko-$skikoPlatform.dylib")?.isDirectory == false) {
                        "Skiko runtime is missing the $skikoPlatform native library"
                    }
                }
                ZipOutputStream(stripped.outputStream().buffered()).use { destination ->
                    source
                        .entries()
                        .asSequence()
                        .filter {
                            if (isSkikoRuntime) {
                                val nativeName = it.name.removeSuffix(".sha256")
                                (nativeName.substringAfterLast('.') !in nativeExtensions && nativeName != "icudtl.dat") ||
                                    it.name.contains(skikoPlatform)
                            } else {
                                it.name.substringAfterLast('.') !in nativeExtensions || it.name.startsWith(keep!!)
                            }
                        }.forEach { entry ->
                            if (isSkikoRuntime && entry.name.removeSuffix(".sha256").substringAfterLast('.') in nativeExtensions) {
                                val library = File(jar.parentFile, entry.name.substringAfterLast('/'))
                                source.getInputStream(entry).use { input ->
                                    library.outputStream().use { input.copyTo(it) }
                                }
                            } else {
                                destination.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                                source.getInputStream(entry).use { it.copyTo(destination) }
                                destination.closeEntry()
                            }
                        }
                }
            }
            // Compose unpacks skiko-* JARs for the host; cross-target natives are already loose packaging inputs.
            val target = if (isSkikoRuntime) File(jar.parentFile, "metrolist-${jar.name}") else jar
            Files.move(stripped.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (target != jar) jar.delete()
        }
    }
}

// Compose WebView publishes no ARM64 natives for Linux or Windows; build them from the pinned Rust sources.
val arm64WebViewJar =
    if (isArm64 && (currentOperatingSystem.isLinux || currentOperatingSystem.isWindows)) {
        val windows = currentOperatingSystem.isWindows
        val buildNativeWebView =
            tasks.register<Exec>("buildArm64WebView") {
                val script = rootProject.file(if (windows) "scripts/build_windows_webview.ps1" else "scripts/build_linux_webview.sh")
                val output = layout.buildDirectory.dir("generated/arm64-webview")
                inputs.file(script).withPathSensitivity(PathSensitivity.RELATIVE)
                outputs.dir(output)
                if (windows) {
                    // The DLL depends only on the pinned sources and toolchain. The Linux library links against the
                    // host's WebKitGTK, which is not a declared input, so only Windows uses the build cache.
                    outputs.cacheIf { true }
                    workingDir(rootProject.projectDir)
                    commandLine(
                        "powershell",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        script.relativeTo(rootProject.projectDir).path,
                        output
                            .get()
                            .asFile
                            .relativeTo(rootProject.projectDir)
                            .path,
                    )
                } else {
                    commandLine("bash", script.absolutePath, output.get().asFile.absolutePath)
                }
            }
        tasks.register<Jar>("arm64WebViewJar") {
            archiveFileName.set("composewebview-${if (windows) "win32" else "linux"}-aarch64.jar")
            destinationDirectory.set(layout.buildDirectory.dir("platform-libs"))
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            from(buildNativeWebView)
        }
    } else {
        null
    }

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(jdkVendor)
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }

        getByName("commonMain") {
            dependencies {
                implementation(libs.runtime)
                implementation(libs.foundation)
                implementation(libs.material3)
                implementation(libs.material3.adaptive)
                implementation(libs.material3.adaptive.layout)
                implementation(libs.ui)
                implementation(libs.components.resources)
            }
        }

        getByName("jvmMain") {
            dependencies {
                implementation(project(":shared")) {
                    exclude(group = "org.xerial", module = "sqlite-jdbc")
                }
                implementation(libs.compose.desktop)
                implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                    exclude(group = "org.jetbrains.skiko", module = "skiko")
                }
                implementation("io.coil-kt.coil3:coil-network-okhttp:${libs.versions.coil.get()}") {
                    exclude(group = "org.jetbrains.skiko", module = "skiko")
                }
                implementation(libs.okhttp)
                implementation(libs.room.runtime)
                implementation(libs.materialKolor)

                implementation(libs.compose.reorderable)
                implementation(files(platformSqliteJdbc))
                implementation(libs.datastore)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation("io.github.kdroidfilter:composewebview:1.0.0-beta-02") {
                    exclude(group = "org.jetbrains.skiko")
                }
                arm64WebViewJar?.let { implementation(files(it)) }
                implementation(libs.slf4j.simple)
                implementation(libs.sentry)

                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.filekit.dialogs)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)

                implementation(libs.darkmodedetector)

                implementation(libs.composenativetray)
                implementation(libs.sharesheet)
                implementation(libs.windowstaskbar)
                implementation(libs.mediacontrols)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                // Only the transcoder test reads M4A tags back through JavaCV.
                implementation("org.bytedeco:javacv:${libs.versions.javacv.get()}") {
                    isTransitive = false
                }
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
            }
        }
    }

    targets.all {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}

val sidecarChecksumResourcesDir = layout.buildDirectory.dir("sidecar-checksum-resources")
val nativeResourcesDir = layout.buildDirectory.dir("native-resources")

// buildSrc outputs are ephemeral on CI and invalidate the configuration cache.
val generateSidecarChecksums =
    tasks.register("generateSidecarChecksums") {
        val sidecars =
            listOf(
                Triple("linux/bgutil-ytdlp-pot-provider-rs", "linux", "bgutil-ytdlp-pot-provider-rs.sha256"),
                Triple("macos/bgutil-ytdlp-pot-provider-rs", "macos", "bgutil-ytdlp-pot-provider-rs.sha256"),
                Triple("windows/bgutil-ytdlp-pot-provider-rs.exe", "windows", "bgutil-ytdlp-pot-provider-rs.exe.sha256"),
                Triple("windows/metro-wv2-helper.exe", "windows", "metro-wv2-helper.exe.sha256"),
            )
        val resourcesDirectory = layout.projectDirectory.dir("src/desktopMain/resources")
        inputs
            .files(
                project.fileTree(resourcesDirectory) {
                    include(sidecars.map { (source, _, _) -> source })
                },
            ).withPropertyName("sidecarFiles")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(sidecarChecksumResourcesDir).withPropertyName("outputDirectory")

        val externalDir = resourcesDirectory.asFile
        val outputDir = sidecarChecksumResourcesDir
        doLast {
            val output = outputDir.get().asFile
            output.deleteRecursively()
            output.mkdirs()
            sidecars.forEach { (sourceName, platform, checksumName) ->
                val source = File(externalDir, sourceName)
                if (!source.isFile) return@forEach
                val digest = MessageDigest.getInstance("SHA-256")
                DigestInputStream(source.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
                val checksum = digest.digest().joinToString("") { "%02x".format(it) }
                File(output, "$platform/$checksumName").also {
                    it.parentFile.mkdirs()
                    it.writeText(checksum)
                }
            }
        }
    }

val syncExternalResources =
    tasks.register<Sync>("syncExternalResources") {
        dependsOn(generateSidecarChecksums)
        into(nativeResourcesDir)

        from(project.file("src/desktopMain/resources")) {
            include("windows/*.dll", "windows/*.exe", "linux/*.so", "linux/bgutil*", "macos/*.dylib", "macos/bgutil*")
        }

        if (platform == "win") {
            from(project.file("src/desktopMain/resources/logo.png")) { into("windows") }
        }
        from(sidecarChecksumResourcesDir)
    }

val desktopJavaLauncher =
    extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(jdkVendor)
    }

tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(desktopJavaLauncher)
    val rootProjectDir = layout.projectDirectory.asFile.parentFile ?: rootDir
    workingDir = rootProjectDir
    environment("MALLOC_ARENA_MAX", "4")
    if (platform == "linux") {
        environment("WEBKIT_DMABUF_RENDERER_FORCE_SHM", providers.environmentVariable("WEBKIT_DMABUF_RENDERER_FORCE_SHM").orElse("1").get())
        environment("MALLOC_MMAP_THRESHOLD_", providers.environmentVariable("MALLOC_MMAP_THRESHOLD_").orElse("131072").get())
        // Gradle daemons can retain stale session variables from the terminal that launched them.
        listOf(
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "LC_MESSAGES",
            "DISPLAY",
            "XAUTHORITY",
            "WAYLAND_DISPLAY",
            "XDG_RUNTIME_DIR",
            "XDG_SESSION_TYPE",
            "DBUS_SESSION_BUS_ADDRESS",
        ).forEach { variable ->
            providers.environmentVariable(variable).orNull?.let { value ->
                environment(variable, value)
            }
        }

        fun detectTerminalLocale(): Locale? {
            val localeName =
                sequenceOf("LC_ALL", "LC_MESSAGES", "LANG")
                    .mapNotNull(System::getenv)
                    .map(String::trim)
                    .map { it.substringBefore('.').substringBefore('@') }
                    .firstOrNull {
                        it.isNotEmpty() &&
                            !it.equals("C", ignoreCase = true) &&
                            !it.equals("POSIX", ignoreCase = true)
                    }
                    ?: return null
            val languageTag = localeName.replace('_', '-')
            return Locale.forLanguageTag(languageTag).takeIf { it.language.isNotBlank() && it.language != "und" }
        }

        environment("LC_NUMERIC", "C")
        environment(
            "GDK_BACKEND",
            providers.environmentVariable("GDK_BACKEND").orNull ?: "x11",
        )
        environment("_JAVA_AWT_WM_NONREPARENTING", "1")
        environment(
            "SKIKO_WAYLAND",
            providers.environmentVariable("SKIKO_WAYLAND").orNull ?: "1",
        )

        fun detectXAuthority(): String? {
            val runtimeDirectory = System.getenv("XDG_RUNTIME_DIR")?.let(::File)
            val currentMutterAuthority =
                runtimeDirectory
                    ?.listFiles { file -> file.isFile && file.name.startsWith(".mutter-Xwaylandauth.") }
                    ?.maxByOrNull(File::lastModified)
                    ?.absolutePath
            return currentMutterAuthority
                ?: System.getenv("XAUTHORITY")?.takeIf { File(it).isFile }
        }

        // Skiko caches cursor settings during JNI_OnLoad, before Kotlin can set them.
        fun probeCursor(vararg command: String): String? {
            val process = runCatching { ProcessBuilder(*command).redirectErrorStream(true).start() }.getOrNull() ?: return null
            val output = StringBuilder()
            val reader =
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { stream ->
                            val buffer = CharArray(1_024)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                val remaining = 4_096 - output.length
                                if (remaining > 0) output.appendRange(buffer, 0, minOf(count, remaining))
                            }
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            return try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) return null
                reader.join(100)
                if (reader.isAlive || process.exitValue() != 0) null else output.toString().trim()
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

        fun detectCursorTheme(): String? =
            System.getenv("XCURSOR_THEME")?.takeIf(String::isNotBlank)
                ?: probeCursor("gsettings", "get", "org.gnome.desktop.interface", "cursor-theme")
                    ?.removeSurrounding("'")
                    ?.takeIf(String::isNotBlank)
                ?: probeCursor("hyprctl", "getoption", "cursor:theme")?.let { text ->
                    Regex("""string:\s*(.+)""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }

        fun detectCursorSize(): String? =
            System.getenv("XCURSOR_SIZE")?.takeIf(String::isNotBlank)
                ?: probeCursor("gsettings", "get", "org.gnome.desktop.interface", "cursor-size")
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.toString()
                ?: probeCursor("hyprctl", "getoption", "cursor:size")?.let { text ->
                    Regex("""int:\s*(\d+)""")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?.toString()
                }

        doFirst {
            systemProperty("java.awt.headless", "false")
            detectTerminalLocale()?.let { locale ->
                systemProperty("user.language", locale.language)
                locale.country.takeIf(String::isNotBlank)?.let { country ->
                    systemProperty("user.country", country)
                }
            }
            detectXAuthority()?.let { authority ->
                environment("XAUTHORITY", authority)
            }
            detectCursorTheme()?.let { theme ->
                environment("XCURSOR_THEME", theme)
                environment("GDK_CURSOR_THEME", theme)
            }
            detectCursorSize()?.let { size ->
                environment("XCURSOR_SIZE", size)
            }
            environment("METROLIST_CURSOR_CONFIGURED", "1")
        }
    }

    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
    jvmArgs(desktopMemoryJvmArgs)
    if (System.getProperty("os.name").contains("Linux")) {
        jvmArgs("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
    }
    if (System.getProperty("os.name").contains("Mac")) {
        jvmArgs("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED")
        jvmArgs("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED")

        val brewPath = if (System.getProperty("os.arch").lowercase().contains("aarch64")) "/opt/homebrew/lib" else "/usr/local/lib"
        val existing = System.getenv("DYLD_FALLBACK_LIBRARY_PATH") ?: ""
        environment("DYLD_FALLBACK_LIBRARY_PATH", if (existing.isEmpty()) brewPath else "$existing:$brewPath")
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

val sentryDebugMetaProperties = layout.buildDirectory.file("generated/sentry/sentry-debug-meta.properties")
val sentrySourceRevision =
    providers
        .gradleProperty("metrolist.sourceRevision")
        .orElse(providers.environmentVariable("GITHUB_SHA"))
        .map(String::trim)
        .filter { it.matches(Regex("[0-9a-fA-F]{40}")) }
        .orElse("local")
        .get()
val sentryBundleId = UUID.nameUUIDFromBytes("com.metrolist.desktop:$platform:$appVersion:$sentrySourceRevision".encodeToByteArray())
val generateSentryDebugMetaProperties =
    tasks.register<WriteProperties>("generateSentryDebugMetaProperties") {
        destinationFile.set(sentryDebugMetaProperties)
        encoding = "UTF-8"
        lineSeparator = "\n"
        property("io.sentry.build-tool", "gradle")
        property("io.sentry.bundle-ids", sentryBundleId)
    }

tasks.named<ProcessResources>("jvmProcessResources") {
    dependsOn(generateSentryDebugMetaProperties)
    from(sentryDebugMetaProperties)
    from(layout.projectDirectory.dir("src/desktopMain/resources")) {
        include("logo.svg", "logo.png", "logo_dark.png", "logo.icns", "nucleus/nucleus-app.properties")
    }
}

compose.resources {
    packageOfResClass = "com.metrolist.desktop.generated.resources"
}

// Compose strips command-line tools from its runtime; temporarily restore java to generate the base CDS archive.
tasks.withType<AbstractJLinkTask>().configureEach {
    val cdsOptions = desktopMemoryJvmArgs.toList()
    val runtimeDirectory = destinationDir
    val toolchainHome = javaHome
    val javaName = if (platform == "win") "java.exe" else "java"
    inputs.property("baseCdsJvmArgs", cdsOptions)
    // Compose leaves JDK tools undeclared; fingerprint the full JDK before caching the linked runtime and CDS archive.
    inputs.dir(toolchainHome).withPropertyName("runtimeJdk").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("runtimeHost", "${System.getProperty("os.name")}:${System.getProperty("os.version")}:${System.getProperty("os.arch")}")
    outputs.cacheIf { true }
    doLast {
        val runtime = runtimeDirectory.get().asFile
        val java = runtime.resolve("bin/$javaName")
        java.parentFile.mkdirs()
        File(toolchainHome.get(), "bin/$javaName").copyTo(java, overwrite = true)
        java.setExecutable(true)
        try {
            val process = ProcessBuilder(listOf(java.absolutePath) + cdsOptions + "-Xshare:dump").inheritIO().start()
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("Timed out generating the runtime CDS archive")
            }
            check(process.exitValue() == 0) { "Failed to generate the runtime CDS archive" }
        } finally {
            java.delete()
        }
    }
}

compose.desktop {
    application {
        buildTypes.release.proguard {
            version.set("7.10.0")
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))

            // Temurin can link from lib/modules; Compose 1.11 only discovers JMOD libraries.
            val jdkHome =
                desktopJavaLauncher
                    .get()
                    .metadata.installationPath.asFile
            if (!jdkHome.resolve("jmods").isDirectory) {
                val moduleClasses =
                    layout.buildDirectory
                        .dir("proguard/jdk-classes")
                        .get()
                        .asFile
                val libraryConfiguration =
                    layout.buildDirectory
                        .file("proguard/jdk-libraries.pro")
                        .get()
                        .asFile
                val extractJdkClasses =
                    tasks.register<Exec>("extractProguardJdkClasses") {
                        inputs.file(jdkHome.resolve("lib/modules"))
                        outputs.dir(moduleClasses)
                        outputs.file(libraryConfiguration)
                        commandLine(
                            jdkHome.resolve("bin/jimage${if (platform == "win") ".exe" else ""}"),
                            "extract",
                            "--include=glob:**.class",
                            "--dir=$moduleClasses",
                            jdkHome.resolve("lib/modules"),
                        )
                        doFirst { moduleClasses.deleteRecursively() }
                        doLast {
                            check(moduleClasses.resolve("java.base/java/lang/Object.class").isFile) {
                                "JDK runtime image extraction did not produce java.base classes"
                            }
                            libraryConfiguration.writeText(
                                moduleClasses.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.joinToString("\n") {
                                    "-libraryjars 'jdk-classes/${it.name}'(!module-info.class)"
                                },
                            )
                        }
                    }
                configurationFiles.from(files(libraryConfiguration).builtBy(extractJdkClasses))
            }
        }

        mainClass = "com.metrolist.desktop.MainKt"
        javaHome =
            providers.gradleProperty("metrolist.desktop.javaHome").orNull?.let { home ->
                val release = Properties().apply { file("$home/release").inputStream().use(::load) }
                val architecture = release.getProperty("OS_ARCH").trim('"')
                val expectedArchitectures = if (isArm64) setOf("aarch64", "arm64") else setOf("x86_64", "amd64")
                require(architecture in expectedArchitectures) {
                    "Packaging JDK architecture $architecture does not match $targetArchitecture"
                }
                home
            } ?: desktopJavaLauncher
                .get()
                .metadata.installationPath.asFile.absolutePath
        jvmArgs +=
            listOf(
                "--enable-native-access=ALL-UNNAMED",
                "--sun-misc-unsafe-memory-access=allow",
                "-Dorg.slf4j.simpleLogger.log.org.freedesktop.dbus=warn",
                "-XX:ErrorFile=${
                    when (platform) {
                        "linux" -> "\$METROLIST_JVM_ERROR_FILE"
                        "win" -> "\$USERPROFILE/.local/state/metrolist/logs/hs_err_pid%p.log"
                        else -> "\$HOME/.local/state/metrolist/logs/hs_err_pid%p.log"
                    }
                }",
            ) + desktopMemoryJvmArgs

        nativeDistributions {
            when (platform) {
                "win" -> Unit
                "mac" -> targetFormats(TargetFormat.Dmg)
                else -> targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            }
            packageName = "Metrolist"
            val versionMatch =
                Regex("""^(\d+)\.(\d+)\.(\d+)(?:-[A-Za-z0-9][A-Za-z0-9._-]*)?$""").matchEntire(appVersion)
                    ?: throw GradleException("appVersion must use MAJOR.MINOR.PATCH with an optional alphanumeric suffix: $appVersion")
            val versionComponents =
                versionMatch.groupValues.slice(1..3).map { component ->
                    component.toIntOrNull()
                        ?: throw GradleException("appVersion components must be integers between 0 and 65535: $appVersion")
                }
            if (versionComponents.any { it !in 0..65535 }) {
                throw GradleException("appVersion components must be between 0 and 65535: $appVersion")
            }
            packageVersion =
                if (platform == "win") {
                    val (major, minor, patch) = versionComponents
                    if (major !in 0..255 || minor !in 0..255) {
                        throw GradleException("Windows package version must fit MAJOR(0..255).MINOR(0..255): $major.$minor.$patch")
                    }
                    "$major.$minor.$patch"
                } else {
                    versionComponents.joinToString(".")
                }
            description = "Metrolist music player"
            copyright = "© 2026 Metrolist"
            vendor = "MetrolistGroup"

            appResourcesRootDir.set(nativeResourcesDir)

            modules(
                "java.instrument",
                "java.management",
                "java.naming",
                "java.prefs",
                "java.sql",
                "java.xml",
                "jdk.unsupported",
                "jdk.security.auth",
                "jdk.localedata",
            )

            jvmArgs +=
                listOf(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                    "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                    "--add-opens=jdk.security.auth/com.sun.security.auth.module=ALL-UNNAMED",
                ) + desktopMemoryJvmArgs
            when (platform) {
                "linux" -> {
                    jvmArgs += "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED"
                }

                "mac" -> {
                    jvmArgs +=
                        listOf(
                            "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                            "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
                        )
                }
            }

            windows {
                iconFile.set(project.file("src/desktopMain/resources/logo.ico"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/logo.png"))
                jvmArgs += listOf("-Djava.locale.providers=CLDR,SPI", "-Dwebkit.disable.dmabuf.renderer=1")
            }
            macOS {
                bundleID = "com.metrolist.desktop"
                infoPlist {
                    extraKeysRawXml =
                        """
                        <key>NSLocalNetworkUsageDescription</key>
                        <string>Metrolist uses the local network to find and control casting devices.</string>
                        <key>NSBonjourServices</key>
                        <array>
                            <string>_fcast._tcp</string>
                            <string>_googlecast._tcp</string>
                        </array>
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Metrolist URL</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>metrolist</string>
                                    <string>metrolist-auth</string>
                                </array>
                            </dict>
                        </array>
                        """.trimIndent()
                }
                iconFile.set(project.file("src/desktopMain/resources/logo.icns"))
                dmgPackageVersion = packageVersion
                dockName = "Metrolist"
            }
        }
    }
}

val cdsCache =
    when (platform) {
        "win" -> "\$LOCALAPPDATA/Metrolist/cache"
        "mac" -> "\$HOME/Library/Caches/com.metrolist.desktop"
        else -> "\$METROLIST_CDS_CACHE"
    }
// Compose assigns launcherJvmArgs while registering its tasks after project evaluation.
afterEvaluate {
    tasks.withType<AbstractProguardTask>().configureEach {
        javaHome.set(
            desktopJavaLauncher
                .get()
                .metadata.installationPath.asFile.absolutePath,
        )
    }
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (name == "packageReleaseAppImage") {
            runtimeImage.set(tasks.named<AbstractJLinkTask>("createReleaseRuntimeImage").flatMap { it.destinationDir })
        }
        if (name.startsWith("createReleaseDistributable") || name.startsWith("packageRelease")) {
            launcherJvmArgs.addAll(
                "-Dmetrolist.cds.cache=$cdsCache",
                "-Xshare:auto",
                "-XX:+VerifySharedSpaces",
                "-XX:+AutoCreateSharedArchive",
                "-XX:SharedArchiveFile=$cdsCache/appcds-$sentryBundleId.jsa",
            )
        }
    }
}

apply(from = "packaging-integrity.gradle.kts")
apply(from = "packaging-launchers.gradle.kts")

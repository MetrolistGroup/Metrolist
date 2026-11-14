@file:Suppress("UnstableApiUsage")

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
}

compose {
    dependencyCompatibility {
        exclude("io.coil-kt.coil3")
    }
}

configurations.matching { it.name.startsWith("desktop") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// Adoptium publishes no Windows ARM64 JDK 25; Microsoft's OpenJDK build is the one available there.
val jdkVendor =
    if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "aarch64") {
        JvmVendorSpec.MICROSOFT
    } else {
        JvmVendorSpec.ADOPTIUM
    }

val ffmpegVersion = "8.1.2-1.5.14"
val javacppPlatform =
    System.getProperty("org.bytedeco.javacpp.platform") ?: run {
        val osName = System.getProperty("os.name").lowercase()
        val osArch =
            providers
                .gradleProperty("metrolist.desktop.arch")
                .orElse(System.getProperty("os.arch"))
                .get()
                .lowercase()
        when {
            osName.contains("mac") -> if (osArch == "aarch64" || osArch == "arm64") "macosx-arm64" else "macosx-x86_64"
            osName.contains("win") -> if (osArch == "aarch64" || osArch == "arm64") "windows-arm64" else "windows-x86_64"
            else -> if (osArch == "aarch64" || osArch == "arm64") "linux-arm64" else "linux-x86_64"
        }
    }
val ffmpegNativeSource = configurations.create("ffmpegNativeSource") { isTransitive = false }
// Bytedeco publishes no windows-arm64 natives; build its JNI libraries from the platform-neutral preset classes instead.
val windowsArm64FfmpegClasspath =
    if (javacppPlatform == "windows-arm64") {
        configurations.create("windowsArm64FfmpegClasspath") { isTransitive = false }.also {
            dependencies.add(it.name, "org.bytedeco:ffmpeg:$ffmpegVersion")
            dependencies.add(it.name, "org.bytedeco:javacpp:${ffmpegVersion.substringAfter('-')}")
        }
    } else {
        dependencies.add(ffmpegNativeSource.name, "org.bytedeco:ffmpeg:$ffmpegVersion:$javacppPlatform")
        null
    }
val ffmpegNativeSourceJar = ffmpegNativeSource.elements.map { it.single().asFile }

interface ArchiveOps {
    @get:Inject
    val archiveOperations: ArchiveOperations
}
val archiveOps = objects.newInstance<ArchiveOps>().archiveOperations
val windowsArm64FfmpegNatives =
    windowsArm64FfmpegClasspath?.incoming?.files?.let { classpath ->
        val java =
            extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(jdkVendor)
            }
        tasks.register<Exec>("buildWindowsArm64Ffmpeg") {
            val output = layout.buildDirectory.dir("generated/windows-arm64-ffmpeg")
            inputs.file(rootProject.file("scripts/build_windows_arm64_ffmpeg.ps1")).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(classpath).withNormalizer(ClasspathNormalizer::class.java)
            inputs.property("javaVersion", 25)
            outputs.dir(output)
            // The natives depend only on pinned inputs; the build cache skips about a minute of MSVC work.
            outputs.cacheIf { true }
            // Project-relative arguments keep the cache key independent of the checkout path.
            workingDir(rootProject.projectDir)
            commandLine(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                "scripts/build_windows_arm64_ffmpeg.ps1",
                "-Output",
                output
                    .get()
                    .asFile
                    .relativeTo(rootProject.projectDir)
                    .path,
            )
            doFirst {
                environment(
                    "METROLIST_FFMPEG_JAVA",
                    java
                        .get()
                        .executablePath.asFile.absolutePath,
                )
                environment("METROLIST_FFMPEG_CLASSPATH", classpath.asPath)
            }
        }
    }
val desktopFfmpegNatives =
    tasks.register<Jar>("desktopFfmpegNatives") {
        archiveFileName.set("ffmpeg-$ffmpegVersion-$javacppPlatform.jar")
        destinationDirectory.set(layout.buildDirectory.dir("platform-libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest.attributes["Multi-Release"] = "true"
        if (windowsArm64FfmpegNatives != null) {
            from(windowsArm64FfmpegNatives)
        } else {
            from(ffmpegNativeSourceJar.map(archiveOps::zipTree)) {
                exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
                exclude("**/ffmpeg", "**/ffmpeg.exe", "**/ffprobe", "**/ffprobe.exe")
                exclude("META-INF/native-image/**/ffmpeg/**", "META-INF/native-image/**/ffprobe/**")
            }
        }
    }

val icu4jSource = configurations.create("icu4jSource") { isTransitive = false }
dependencies.add(icu4jSource.name, "com.ibm.icu:icu4j:79.1")
val icu4jSourceJar = icu4jSource.elements.map { it.single().asFile }
val desktopIcu4j =
    tasks.register<Jar>("desktopIcu4j") {
        archiveFileName.set("icu4j-79.1-relative-dates.jar")
        destinationDirectory.set(layout.buildDirectory.dir("platform-libs"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(icu4jSourceJar.map(archiveOps::zipTree)) {
            exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
            val data = "com/ibm/icu/impl/data/icudata"
            listOf("brkitr", "coll", "lang", "rbnf", "region", "translit", "unit", "zone").forEach { exclude("$data/$it/**") }
            listOf("unames.icu", "zoneinfo64.res", "uts46.nrm", "confusables*.cfu", "*.spp", "nfkc*.nrm").forEach { exclude("$data/$it") }
        }
    }

abstract class StripKuromojiDictionaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceJar: RegularFileProperty

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @TaskAction
    fun stripDictionary() {
        val output = outputJar.get().asFile
        output.parentFile.mkdirs()
        ZipFile(sourceJar.get().asFile).use { source ->
            ZipOutputStream(output.outputStream().buffered()).use { destination ->
                source
                    .entries()
                    .asSequence()
                    .filter { it.name.endsWith(".class") || it.name in ATTRIBUTION_FILES }
                    .sortedBy { it.name }
                    .forEach { entry ->
                        destination.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                        source.getInputStream(entry).use { it.copyTo(destination) }
                        destination.closeEntry()
                    }
            }
        }
    }

    private companion object {
        val ATTRIBUTION_FILES = setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/CONTRIBUTORS.md")
    }
}

abstract class VerifyKuromojiPackagingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val engineJar: RegularFileProperty

    @TaskAction
    fun verifyPackaging() {
        val jar = engineJar.get().asFile
        ZipFile(jar).use { packaged ->
            check(packaged.entries().asSequence().all { it.name.endsWith(".class") || it.name.startsWith("META-INF/") })
            check(packaged.entries().asSequence().none { it.name.endsWith(".bin") })
            check(listOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/CONTRIBUTORS.md").all { packaged.getEntry(it) != null })
        }
        check(jar.length() < 1024 * 1024) { "Code-only Kuromoji artifact is unexpectedly large: ${jar.length()} bytes" }
        logger.lifecycle("Kuromoji code-only artifact: ${jar.length()} bytes (dictionary payload excluded)")
    }
}

abstract class VerifyIosKuromojiPackagingTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val xcodeProject: RegularFileProperty

    @TaskAction
    fun verifyPackaging() {
        val resources = resourceDirectory.get().asFile
        val engine = File(resources, "kuromoji.js")
        val bootstrap = File(resources, "kuromoji-bootstrap.js")

        fun sha256(file: File): String =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                (it.toInt() and 0xff).toString(16).padStart(2, '0')
            }

        check(sha256(engine) == "ab0ac10f1c4a9b5e246e63e57b04ba14d3a6148cd7e8d62cb97a960d4f85a3e6")
        check(sha256(bootstrap) == "3a2f75400ef6f04301408701c2a2dbe23e614561df4aea3ab36504e9cca4ceb9")
        check(File(resources, "kuromoji-LICENSE-2.0.txt").isFile)
        check(File(resources, "kuromoji-NOTICE.md").isFile)
        check(resources.listFiles().orEmpty().none { it.name.endsWith(".dat.gz") })
        val project = xcodeProject.get().asFile.readText()
        check(
            listOf(
                "kuromoji.js in Resources",
                "kuromoji-bootstrap.js in Resources",
                "kuromoji-LICENSE-2.0.txt in Resources",
                "kuromoji-NOTICE.md in Resources",
            ).all { it in project },
        )
    }
}

val kuromojiIpadicArtifact =
    configurations.create("kuromojiIpadicArtifact") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
dependencies.add(kuromojiIpadicArtifact.name, libs.kuromoji.ipadic.get())
val kuromojiCodeOnlyJar = layout.buildDirectory.file("generated/romanizer/kuromoji-ipadic-code-0.9.0.jar")
val stripKuromojiDictionary =
    tasks.register<StripKuromojiDictionaryTask>("stripKuromojiIpadicDictionary") {
        sourceJar.set(layout.file(kuromojiIpadicArtifact.elements.map { it.single().asFile }))
        outputJar.set(kuromojiCodeOnlyJar)
    }
val kuromojiCodeOnlyDependency = files(kuromojiCodeOnlyJar).builtBy(stripKuromojiDictionary)
val verifyKuromojiPackaging =
    tasks.register<VerifyKuromojiPackagingTask>("verifyKuromojiPackaging") {
        dependsOn(stripKuromojiDictionary)
        engineJar.set(kuromojiCodeOnlyJar)
    }

val verifyIosKuromojiPackaging =
    tasks.register<VerifyIosKuromojiPackagingTask>("verifyIosKuromojiPackaging") {
        resourceDirectory.set(layout.projectDirectory.dir("src/iosMain/resources"))
        xcodeProject.set(layout.projectDirectory.file("../iosApp/iosApp.xcodeproj/project.pbxproj"))
    }

val runPackagingVerification = providers.gradleProperty("metrolist.verifyPackaging").map(String::toBooleanStrict).getOrElse(true)
tasks.matching { it.name.startsWith("compileKotlinIos") }.configureEach {
    if (runPackagingVerification) dependsOn(verifyIosKuromojiPackaging)
}

tasks.named("check") {
    dependsOn(verifyKuromojiPackaging, verifyIosKuromojiPackaging)
}

abstract class GenerateRoomMigrationTestSchemas : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.file("com/metrolist/shared/db/HistoricalRoomSchemas.kt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            buildString {
                appendLine("package com.metrolist.shared.db")
                appendLine("import kotlin.io.encoding.Base64")
                appendLine("internal fun historicalRoomSchema(version: Int): String = when (version) {")
                schemaDirectory
                    .get()
                    .asFile
                    .listFiles()
                    .orEmpty()
                    .filter { it.extension == "json" }
                    .sortedBy { it.nameWithoutExtension.toInt() }
                    .forEach { schema ->
                        val encoded = Base64.getEncoder().encodeToString(schema.readLines().joinToString("") { it.trim() }.toByteArray())
                        appendLine("${schema.nameWithoutExtension} -> Base64.decode(\"$encoded\").decodeToString()")
                    }
                appendLine("else -> error(\"No exported Room schema for version \" + version)")
                appendLine("}")
            },
        )
    }
}

val generateRoomMigrationTestSchemas =
    tasks.register<GenerateRoomMigrationTestSchemas>("generateRoomMigrationTestSchemas") {
        schemaDirectory.set(layout.projectDirectory.dir("schemas/com.metrolist.shared.db.MusicDatabase"))
        outputDirectory.set(layout.buildDirectory.dir("generated/source/roomMigrationTest/kotlin"))
    }

abstract class GenerateOpusTestFixture : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fixture: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.file("com/metrolist/shared/playback/opus/OpusTestFixture.kt").get().asFile
        destination.parentFile.mkdirs()
        val encoded = Base64.getEncoder().encodeToString(fixture.get().asFile.readBytes())
        destination.writeText(
            "package com.metrolist.shared.playback.opus\n" +
                "import kotlin.io.encoding.Base64\n" +
                "internal fun opusWebmFixture(): ByteArray = Base64.decode(\"$encoded\")\n",
        )
    }
}

val generateOpusTestFixture =
    tasks.register<GenerateOpusTestFixture>("generateOpusTestFixture") {
        fixture.set(layout.projectDirectory.file("src/desktopTest/resources/ffmpeg/opus-251.webm"))
        outputDirectory.set(layout.buildDirectory.dir("generated/opusTestFixture"))
    }

tasks.matching { it.name == "lintAnalyzeAndroidHostTest" || it.name == "generateAndroidHostTestLintModel" }.configureEach {
    dependsOn(generateRoomMigrationTestSchemas, generateOpusTestFixture)
}

val appVersion = providers.gradleProperty("appVersion").get()
val innerTubeXVersion = providers.gradleProperty("innerTubeXVersion").get()
val bundledLanguageTags =
    file("src/commonMain/composeResources")
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isDirectory && it.name.startsWith("values-") && it.name != "values-xml" }
        .map { directory ->
            val qualifier = directory.name.removePrefix("values-")
            val languageTag =
                if (qualifier.startsWith("b+")) {
                    qualifier.removePrefix("b+").replace('+', '-')
                } else {
                    qualifier.replace(Regex("-r(?=[A-Z]{2}(?:$|-))"), "-")
                }
            Locale.forLanguageTag(languageTag).toLanguageTag()
        }.filter { it.isNotBlank() && it != "und" }
        .distinct()
        .sorted()
        .toList()

kotlin {
    android {
        namespace = "com.metrolist.shared"
        compileSdk {
            version = release(37) { minorApiLevel = 1 }
        }
        minSdk = 24
        androidResources.enable = true
        withHostTest {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.compilations.getByName("main").cinterops.create("playbackObservation") {
            definitionFile.set(project.file("src/iosMain/cinterop/playbackObservation.def"))
        }
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            freeCompilerArgs += "-Xadd-light-debug=enable"
        }
    }

    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(jdkVendor)
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        getByName("commonMain") {
            dependencies {
                api("com.github.MetrolistGroup.innertubex:innertubex:$innerTubeXVersion")
                api(libs.runtime)
                api(libs.foundation)
                api(libs.androidx.navigationevent.compose)
                api(libs.material3)
                api(libs.material3.adaptive)
                api(libs.material3.adaptive.navigation.suite)
                api(libs.components.resources)
                implementation(libs.ui.tooling.preview)
                implementation(libs.compose.reorderable)
                implementation(libs.swipe)
                api(libs.markdown.renderer)
                implementation(libs.markdown.renderer.m3)
                implementation(libs.markdown.renderer.coil3)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.client.encoding)
                implementation(libs.ktor.client.websockets)
                implementation(libs.ktor.serialization.json)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)

                implementation(libs.datastore)

                implementation(libs.room.runtime)

                implementation(libs.koin.core)

                api(libs.androidx.navigation3.runtime)

                api(libs.multiplatform.settings)
                implementation(libs.multiplatform.settings.datastore)

                implementation(libs.materialKolor)

                api(libs.shimmer)

                implementation("io.coil-kt.coil3:coil-compose:${libs.versions.coil.get()}") {
                    exclude(group = "org.jetbrains.skiko", module = "skiko")
                }
                implementation("io.coil-kt.coil3:coil-network-core:${libs.versions.coil.get()}")
            }
        }
        getByName("commonTest") {
            kotlin.srcDir(generateRoomMigrationTestSchemas)
            kotlin.srcDir(generateOpusTestFixture)
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
                implementation(libs.ktor.client.mock)
                implementation(libs.multiplatform.settings.test)
            }
        }
        val jvmSharedTest =
            create("jvmSharedTest") {
                dependsOn(getByName("commonTest"))
            }
        getByName("androidHostTest") {
            dependsOn(jvmSharedTest)
            dependencies {
                implementation("org.robolectric:robolectric:4.17")
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.test.runner)
            }
        }
        val jvmSharedMain =
            create("jvmSharedMain") {
                dependsOn(getByName("commonMain"))
                dependencies {
                    implementation(project(":listenTogetherProto"))
                    implementation(libs.ktor.client.logging)
                    implementation(libs.ktor.client.cio)
                    implementation(libs.okhttp)
                }
            }
        getByName("androidMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.multiplatform.settings.coroutines)
                implementation(libs.androidx.core.ktx)
                implementation(libs.browser)
                api(libs.room.runtime)
                implementation(libs.coil.network.okhttp)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.timber)

                implementation(libs.kuromoji.core)
                implementation(kuromojiCodeOnlyDependency)

                implementation(libs.media3.exoplayer)
                implementation(libs.media3.common)
                implementation(libs.media3.datasource)
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.compose.desktop)
                implementation(files(desktopIcu4j))
                implementation(libs.room.runtime)
                implementation(libs.sqlite.jdbc)
                implementation(libs.ktor.client.okhttp)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.jmdns)
                api("org.bytedeco:ffmpeg:$ffmpegVersion")
                implementation(files(desktopFfmpegNatives))
                implementation(libs.kuromoji.core)
                implementation(kuromojiCodeOnlyDependency)
            }
        }
        getByName("desktopTest") {
            dependsOn(jvmSharedTest)
            dependencies {
                implementation(libs.coil.network.okhttp)
                implementation(libs.junit4)
                implementation(libs.ktor.client.cio)
                runtimeOnly(libs.kuromoji.ipadic)
            }
        }
        getByName("iosMain") {
            dependencies {
                implementation("io.coil-kt.coil3:coil-network-ktor3:${libs.versions.coil.get()}") {
                    exclude(group = "org.jetbrains.skiko", module = "skiko")
                }
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqlite.bundled)
                implementation("no.synth:kmp-zip:2.1.0")
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

val reflectionFreeTestSuites =
    listOf("*LocalDeviceServerTest", "*DeviceConnectManagerIntegrationTest", "*ListenTogetherManagerLifecycleTest")

tasks.named<Test>("desktopTest") {
    filter { reflectionFreeTestSuites.forEach(::excludeTestsMatching) }
    systemProperty("metrolist.test.withoutReflection", "true")
    systemProperty("io.ktor.development", "true")
    environment(
        "XDG_DATA_HOME",
        layout.buildDirectory
            .dir("test-data/xdg")
            .get()
            .asFile.absolutePath,
    )
}

val prepareComposeResourceAliases =
    tasks.register<Sync>("prepareComposeResourceAliases") {
        val catalog = layout.projectDirectory.dir("src/commonMain/composeResources")
        from(catalog)
        mapOf("in" to "id", "iw" to "he").forEach { (legacy, modern) ->
            from(catalog.dir("values-$legacy")) { into("values-$modern") }
        }
        into(layout.buildDirectory.dir("generated/composeResourceAliases"))
    }

compose.resources {
    packageOfResClass = "com.metrolist.shared.generated.resources"
    publicResClass = true
    customDirectory("commonMain", layout.dir(prepareComposeResourceAliases.map { it.destinationDir }))
}

tasks.matching { it.name == "generateResourceAccessorsForCommonMain" }.configureEach {
    outputs.cacheIf { true }
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val generateBuildConfig =
    tasks.register("generateBuildConfig") {
        val outputDir =
            layout.buildDirectory
                .dir("generated/source/config/main/kotlin")
                .get()
                .asFile

        inputs.files(localPropertiesFile)

        val lastFmKey =
            providers
                .gradleProperty("LASTFM_API_KEY")
                .filter { it.isNotEmpty() }
                .orElse(providers.environmentVariable("LASTFM_API_KEY").filter { it.isNotEmpty() })
                .orElse(providers.provider { localProperties.getProperty("LASTFM_API_KEY").orEmpty() }.filter { it.isNotEmpty() })
                .orElse(providers.provider { "" })
                .get()
                .trim()
        val lastFmSecret =
            providers
                .gradleProperty("LASTFM_SECRET")
                .filter { it.isNotEmpty() }
                .orElse(providers.environmentVariable("LASTFM_SECRET").filter { it.isNotEmpty() })
                .orElse(providers.provider { localProperties.getProperty("LASTFM_SECRET").orEmpty() }.filter { it.isNotEmpty() })
                .orElse(providers.provider { "" })
                .get()
                .trim()
        val isReleaseTask =
            gradle.startParameter.taskNames.any { name ->
                name.contains("Release", ignoreCase = true) ||
                    name.contains("Benchmark", ignoreCase = true) ||
                    (name.contains("package", ignoreCase = true) && !name.contains("Debug", ignoreCase = true)) ||
                    name.contains("Distributable", ignoreCase = true)
            }
        val metrolistDebugProp =
            providers
                .gradleProperty("metrolist.debug")
                .orNull
                ?.toBooleanStrictOrNull()
        val xcodeDebug =
            providers
                .environmentVariable("CONFIGURATION")
                .orNull
                ?.let { !it.equals("Release", ignoreCase = true) }
        val debugValue: Boolean = metrolistDebugProp ?: xcodeDebug ?: !isReleaseTask
        val lyprioBaseUrl =
            providers
                .gradleProperty("LYPRIO_BASE_URL")
                .filter { it.isNotEmpty() }
                .orElse(providers.environmentVariable("LYPRIO_BASE_URL").filter { it.isNotEmpty() })
                .orElse(providers.provider { localProperties.getProperty("LYPRIO_BASE_URL").orEmpty() }.filter { it.isNotEmpty() })
                .orElse(providers.provider { "" })
                .get()
                .trim()
        val lyprioHmacKey =
            providers
                .gradleProperty("LYPRIO_HMAC_KEY")
                .filter { it.isNotEmpty() }
                .orElse(providers.environmentVariable("LYPRIO_HMAC_KEY").filter { it.isNotEmpty() })
                .orElse(providers.provider { localProperties.getProperty("LYPRIO_HMAC_KEY").orEmpty() }.filter { it.isNotEmpty() })
                .orElse(providers.provider { "" })
                .get()
                .trim()
        require(lyprioBaseUrl.isBlank() == lyprioHmacKey.isBlank()) {
            "LYPRIO_BASE_URL and LYPRIO_HMAC_KEY must be configured together"
        }
        if (lyprioBaseUrl.isNotBlank()) {
            val uri = URI(lyprioBaseUrl)
            require(
                !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null &&
                    (
                        (uri.scheme == "https" && (uri.port == -1 || uri.port == 443)) ||
                            (debugValue && uri.scheme == "http")
                    ),
            ) { "LYPRIO_BASE_URL must use HTTPS on port 443; HTTP is allowed only in debug builds" }
            require(Base64.getDecoder().decode(lyprioHmacKey).size == 32) {
                "LYPRIO_HMAC_KEY must be a base64-encoded 32-byte key"
            }
        }
        inputs.property("lastFmKey", lastFmKey)
        inputs.property("lastFmSecret", lastFmSecret)
        inputs.property("lyprioBaseUrl", lyprioBaseUrl)
        inputs.property("lyprioHmacKey", lyprioHmacKey)
        val sourceRevision =
            providers
                .gradleProperty("metrolist.sourceRevision")
                .orElse(providers.environmentVariable("GITHUB_SHA"))
                .map(String::trim)
                .filter { it.matches(Regex("[0-9a-fA-F]{40}")) }
                .orElse("")
                .get()
        inputs.property("appVersion", appVersion)
        inputs.property("sourceRevision", sourceRevision)
        inputs.property("bundledLanguageTags", bundledLanguageTags.joinToString(","))

        inputs.property("debug", debugValue)

        outputs.dir(outputDir)
        outputs.cacheIf { false }

        doLast {
            val configFile = outputDir.resolve("com/metrolist/shared/BuildConfig.kt")
            configFile.parentFile.mkdirs()
            configFile.writeText(
                """
                package com.metrolist.shared

                import kotlin.experimental.ExperimentalObjCRefinement
                import kotlin.native.HiddenFromObjC

                @OptIn(ExperimentalObjCRefinement::class)
                @HiddenFromObjC
                object BuildConfig {
                    const val LASTFM_API_KEY = "${inputs.properties["lastFmKey"]}"
                    const val LASTFM_SECRET = "${inputs.properties["lastFmSecret"]}"
                    const val LYPRIO_BASE_URL = "${inputs.properties["lyprioBaseUrl"]}"
                    const val LYPRIO_HMAC_KEY = "${inputs.properties["lyprioHmacKey"]}"
                    const val APP_VERSION = "${inputs.properties["appVersion"]}"
                    const val SOURCE_REVISION = "${inputs.properties["sourceRevision"]}"
                    const val BUNDLED_LANGUAGE_TAGS = "${inputs.properties["bundledLanguageTags"]}"
                    const val DEBUG = ${inputs.properties["debug"]}
                }
                """.trimIndent(),
            )
        }
    }

kotlin {
    sourceSets {
        getByName("commonMain") {
            kotlin.srcDir(generateBuildConfig)
        }
    }
}

dependencies {
    add("androidRuntimeClasspath", libs.ui.tooling)
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDirectory: File,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("room.schemaLocation=${schemaDirectory.path}")
}

ksp {
    arg(RoomSchemaArgProvider(layout.projectDirectory.dir("schemas").asFile))
}

val runLiveLyricsTests =
    providers
        .systemProperty("runLiveLyricsTests")
        .orElse(providers.environmentVariable("RUN_LIVE_LYRICS_TESTS"))
        .orElse("false")

val runLiveInnerTubeClientTests =
    providers
        .systemProperty("runLiveInnerTubeClientTests")
        .orElse(providers.environmentVariable("RUN_LIVE_INNERTUBE_CLIENT_TESTS"))
        .orElse("false")

val runLiveInnerTubeClientBenchmarks =
    providers
        .systemProperty("runLiveInnerTubeClientBenchmarks")
        .orElse(providers.environmentVariable("RUN_LIVE_INNERTUBE_CLIENT_BENCHMARKS"))
        .orElse("false")

val runLiveSignedOutPlaybackMatrix =
    providers
        .systemProperty("runLiveSignedOutPlaybackMatrix")
        .orElse(providers.environmentVariable("RUN_LIVE_SIGNED_OUT_PLAYBACK_MATRIX"))
        .orElse("false")

val runLiveInnerTubePlaybackMatrix =
    providers
        .systemProperty("runLiveInnerTubePlaybackMatrix")
        .orElse(providers.environmentVariable("RUN_LIVE_INNERTUBE_PLAYBACK_MATRIX"))
        .orElse("false")

val innerTubeSignedOut =
    providers
        .systemProperty("innerTubeSignedOut")
        .orElse(providers.environmentVariable("INNERTUBE_SIGNED_OUT"))
        .orElse(providers.provider { localProperties.getProperty("innerTubeSignedOut").orEmpty() }.filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("INNERTUBE_SIGNED_OUT").orEmpty() }.filter { it.isNotBlank() })
        .orElse("false")

val failLiveInnerTubeClientFailures =
    providers
        .systemProperty("failLiveInnerTubeClientFailures")
        .orElse(providers.environmentVariable("FAIL_LIVE_INNERTUBE_CLIENT_FAILURES"))
        .orElse("false")

val localInnerTubeBenchmarkIterations =
    providers
        .provider { localProperties.getProperty("innerTubeBenchmarkIterations").orEmpty() }
        .filter { it.isNotBlank() }
val localInnerTubeBenchmarkIterationsUpper =
    providers
        .provider { localProperties.getProperty("INNERTUBE_BENCHMARK_ITERATIONS").orEmpty() }
        .filter { it.isNotBlank() }

val innerTubeBenchmarkIterations =
    providers
        .systemProperty("innerTubeBenchmarkIterations")
        .filter { it.isNotBlank() }
        .orElse(providers.gradleProperty("innerTubeBenchmarkIterations").filter { it.isNotBlank() })
        .orElse(providers.environmentVariable("INNERTUBE_BENCHMARK_ITERATIONS").filter { it.isNotBlank() })
        .orElse(localInnerTubeBenchmarkIterations)
        .orElse(localInnerTubeBenchmarkIterationsUpper)
        .orElse("3")

val innerTubeStreamDelaySeconds =
    providers
        .systemProperty("innerTubeStreamDelaySeconds")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_STREAM_DELAY_SECONDS").filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("innerTubeStreamDelaySeconds").orEmpty() }.filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("INNERTUBE_STREAM_DELAY_SECONDS").orEmpty() }.filter { it.isNotBlank() })
        .orElse("10")

val innerTubeGroupDelaySeconds =
    providers
        .systemProperty("innerTubeGroupDelaySeconds")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_GROUP_DELAY_SECONDS").filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("innerTubeGroupDelaySeconds").orEmpty() }.filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("INNERTUBE_GROUP_DELAY_SECONDS").orEmpty() }.filter { it.isNotBlank() })
        .orElse("20")

val innerTubePlaybackCaseDelaySeconds =
    providers
        .systemProperty("innerTubePlaybackCaseDelaySeconds")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_PLAYBACK_CASE_DELAY_SECONDS").filter { it.isNotBlank() })
        .orElse(
            providers
                .provider { localProperties.getProperty("innerTubePlaybackCaseDelaySeconds").orEmpty() }
                .filter { it.isNotBlank() },
        ).orElse(
            providers
                .provider { localProperties.getProperty("INNERTUBE_PLAYBACK_CASE_DELAY_SECONDS").orEmpty() }
                .filter { it.isNotBlank() },
        ).orElse("20")

val innerTubePlaybackUrls =
    providers
        .systemProperty("innerTubePlaybackUrls")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_PLAYBACK_URLS").filter { it.isNotBlank() })
        .orElse("")

val innerTubePlaybackClientFilter =
    providers
        .systemProperty("innerTubePlaybackClientFilter")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_PLAYBACK_CLIENT_FILTER").filter { it.isNotBlank() })
        .orElse("")

val innerTubePoTokenProviderUrl =
    providers
        .systemProperty("innerTubePoTokenProviderUrl")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_PO_TOKEN_PROVIDER_URL").filter { it.isNotBlank() })
        .orElse("")

val innerTubePoTokenProviderCapabilities =
    providers
        .systemProperty("innerTubePoTokenProviderCapabilities")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_PO_TOKEN_PROVIDER_CAPABILITIES").filter { it.isNotBlank() })
        .orElse("")

val innerTubePlaybackStreamOrdinalFilter =
    providers
        .systemProperty("innerTubePlaybackStreamOrdinalFilter")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_PLAYBACK_STREAM_ORDINAL_FILTER").filter { it.isNotBlank() })
        .orElse("")

val innerTubeClientFilter =
    providers
        .systemProperty("innerTubeClientFilter")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_CLIENT_FILTER").filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("innerTubeClientFilter").orEmpty() }.filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("INNERTUBE_CLIENT_FILTER").orEmpty() }.filter { it.isNotBlank() })
        .orElse("")

val innerTubeContentTypeFilter =
    providers
        .systemProperty("innerTubeContentTypeFilter")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_CONTENT_TYPE_FILTER").filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("innerTubeContentTypeFilter").orEmpty() }.filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("INNERTUBE_CONTENT_TYPE_FILTER").orEmpty() }.filter { it.isNotBlank() })
        .orElse("")

val innerTubeStreamOrdinalFilter =
    providers
        .systemProperty("innerTubeStreamOrdinalFilter")
        .filter { it.isNotBlank() }
        .orElse(providers.environmentVariable("INNERTUBE_STREAM_ORDINAL_FILTER").filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("innerTubeStreamOrdinalFilter").orEmpty() }.filter { it.isNotBlank() })
        .orElse(providers.provider { localProperties.getProperty("INNERTUBE_STREAM_ORDINAL_FILTER").orEmpty() }.filter { it.isNotBlank() })
        .orElse("")

val sabrVideoId = providers.systemProperty("sabrVideoId").orElse("")
val sabrUsePlaybackClock = providers.systemProperty("sabrUsePlaybackClock").orElse("true")
val runLiveDesktopSabrPlaybackTest = providers.systemProperty("runLiveDesktopSabrPlaybackTest").orElse("false")
val signedOutProbeVideoId =
    providers
        .systemProperty("signedOutProbeVideoId")
        .orElse(providers.environmentVariable("SIGNED_OUT_PROBE_VIDEO_ID"))
        .orElse("")

val javaToolchainService = extensions.getByType<JavaToolchainService>()

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    // AVFoundation needs the media services of a booted simulator.
    standalone.set(false)
    testLogging.exceptionFormat = TestExceptionFormat.FULL
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
    javaLauncher.set(
        javaToolchainService.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(jdkVendor)
        },
    )
    inputs.property("runLiveLyricsTests", runLiveLyricsTests)
    inputs.property("runLiveAppleMusicTests", providers.environmentVariable("RUN_LIVE_APPLE_MUSIC_TESTS").orElse("false"))
    inputs.property("appleMusicTestCountry", providers.environmentVariable("APPLE_MUSIC_TEST_COUNTRY").orElse(""))
    inputs.property("appleMusicTestLanguage", providers.environmentVariable("APPLE_MUSIC_TEST_LANGUAGE").orElse(""))
    inputs.property("runLiveInnerTubeClientTests", runLiveInnerTubeClientTests)
    inputs.property("runLiveInnerTubeClientBenchmarks", runLiveInnerTubeClientBenchmarks)
    inputs.property("runLiveSignedOutPlaybackMatrix", runLiveSignedOutPlaybackMatrix)
    inputs.property("runLiveInnerTubePlaybackMatrix", runLiveInnerTubePlaybackMatrix)
    inputs.property("innerTubeSignedOut", innerTubeSignedOut)
    inputs.property("failLiveInnerTubeClientFailures", failLiveInnerTubeClientFailures)
    inputs.property("innerTubeBenchmarkIterations", innerTubeBenchmarkIterations)
    inputs.property("innerTubeStreamDelaySeconds", innerTubeStreamDelaySeconds)
    inputs.property("innerTubeGroupDelaySeconds", innerTubeGroupDelaySeconds)
    inputs.property("innerTubePlaybackCaseDelaySeconds", innerTubePlaybackCaseDelaySeconds)
    inputs.property("innerTubePlaybackUrls", innerTubePlaybackUrls)
    inputs.property("innerTubePlaybackClientFilter", innerTubePlaybackClientFilter)
    inputs.property("innerTubePoTokenProviderUrl", innerTubePoTokenProviderUrl)
    inputs.property("innerTubePoTokenProviderCapabilities", innerTubePoTokenProviderCapabilities)
    inputs.property("innerTubePlaybackStreamOrdinalFilter", innerTubePlaybackStreamOrdinalFilter)
    inputs.property("innerTubeClientFilter", innerTubeClientFilter)
    inputs.property("innerTubeContentTypeFilter", innerTubeContentTypeFilter)
    inputs.property("innerTubeStreamOrdinalFilter", innerTubeStreamOrdinalFilter)
    inputs.property("sabrVideoId", sabrVideoId)
    inputs.property("sabrUsePlaybackClock", sabrUsePlaybackClock)
    inputs.property("runLiveDesktopSabrPlaybackTest", runLiveDesktopSabrPlaybackTest)
    inputs.property("signedOutProbeVideoId", signedOutProbeVideoId)
    systemProperty("runLiveLyricsTests", runLiveLyricsTests.get())
    systemProperty(
        "runLiveSpotifyTests",
        providers
            .systemProperty("runLiveSpotifyTests")
            .orElse(providers.environmentVariable("RUN_LIVE_SPOTIFY_TESTS"))
            .orElse("false")
            .get(),
    )
    systemProperty(
        "liveUploadFile",
        providers
            .systemProperty("liveUploadFile")
            .orElse(providers.environmentVariable("LIVE_UPLOAD_FILE"))
            .orElse("")
            .get(),
    )
    systemProperty("runLiveInnerTubeClientTests", runLiveInnerTubeClientTests.get())
    systemProperty("runLiveInnerTubeClientBenchmarks", runLiveInnerTubeClientBenchmarks.get())
    systemProperty("runLiveSignedOutPlaybackMatrix", runLiveSignedOutPlaybackMatrix.get())
    systemProperty("runLiveInnerTubePlaybackMatrix", runLiveInnerTubePlaybackMatrix.get())
    systemProperty("innerTubeSignedOut", innerTubeSignedOut.get())
    systemProperty("failLiveInnerTubeClientFailures", failLiveInnerTubeClientFailures.get())
    systemProperty("innerTubeBenchmarkIterations", innerTubeBenchmarkIterations.get())
    systemProperty("innerTubeStreamDelaySeconds", innerTubeStreamDelaySeconds.get())
    systemProperty("innerTubeGroupDelaySeconds", innerTubeGroupDelaySeconds.get())
    systemProperty("innerTubePlaybackCaseDelaySeconds", innerTubePlaybackCaseDelaySeconds.get())
    systemProperty("innerTubePlaybackUrls", innerTubePlaybackUrls.get())
    systemProperty("innerTubePlaybackClientFilter", innerTubePlaybackClientFilter.get())
    systemProperty("innerTubePoTokenProviderUrl", innerTubePoTokenProviderUrl.get())
    systemProperty("innerTubePoTokenProviderCapabilities", innerTubePoTokenProviderCapabilities.get())
    systemProperty("innerTubePlaybackStreamOrdinalFilter", innerTubePlaybackStreamOrdinalFilter.get())
    systemProperty("innerTubeClientFilter", innerTubeClientFilter.get())
    systemProperty("innerTubeContentTypeFilter", innerTubeContentTypeFilter.get())
    systemProperty("innerTubeStreamOrdinalFilter", innerTubeStreamOrdinalFilter.get())
    systemProperty("sabrVideoId", sabrVideoId.get())
    systemProperty("sabrUsePlaybackClock", sabrUsePlaybackClock.get())
    systemProperty("runLiveDesktopSabrPlaybackTest", runLiveDesktopSabrPlaybackTest.get())
    systemProperty("signedOutProbeVideoId", signedOutProbeVideoId.get())
    systemProperty(
        "liveInnerTubeBenchmarkReportDir",
        layout.buildDirectory
            .dir("reports/live-innertube")
            .get()
            .asFile.absolutePath,
    )
    environment("RUN_LIVE_LYRICS_TESTS", runLiveLyricsTests.get())
    environment("RUN_LIVE_INNERTUBE_CLIENT_TESTS", runLiveInnerTubeClientTests.get())
    environment("RUN_LIVE_INNERTUBE_CLIENT_BENCHMARKS", runLiveInnerTubeClientBenchmarks.get())
    environment("RUN_LIVE_SIGNED_OUT_PLAYBACK_MATRIX", runLiveSignedOutPlaybackMatrix.get())
    environment("RUN_LIVE_INNERTUBE_PLAYBACK_MATRIX", runLiveInnerTubePlaybackMatrix.get())
    environment("INNERTUBE_PLAYBACK_CLIENT_FILTER", innerTubePlaybackClientFilter.get())
    environment("INNERTUBE_PO_TOKEN_PROVIDER_CAPABILITIES", innerTubePoTokenProviderCapabilities.get())
    environment("INNERTUBE_SIGNED_OUT", innerTubeSignedOut.get())
    environment("FAIL_LIVE_INNERTUBE_CLIENT_FAILURES", failLiveInnerTubeClientFailures.get())
    environment("INNERTUBE_CLIENT_FILTER", innerTubeClientFilter.get())
    environment("INNERTUBE_CONTENT_TYPE_FILTER", innerTubeContentTypeFilter.get())
    environment("INNERTUBE_STREAM_ORDINAL_FILTER", innerTubeStreamOrdinalFilter.get())
    if (
        runLiveInnerTubeClientTests.get() == "true" ||
        runLiveInnerTubeClientBenchmarks.get() == "true" ||
        runLiveSignedOutPlaybackMatrix.get() == "true" ||
        runLiveInnerTubePlaybackMatrix.get() == "true"
    ) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        testLogging.showStandardStreams = true
    }
}

val testServerWithoutReflection =
    tasks.register<Test>("testServerWithoutReflection") {
        group = "verification"
        description = "Runs server integration tests on the reflection-free desktop runtime."
        val desktopTest = tasks.named<Test>("desktopTest").get()
        testClassesDirs = desktopTest.testClassesDirs
        classpath = desktopTest.classpath
        environment(
            "XDG_DATA_HOME",
            layout.buildDirectory
                .dir("test-data/no-reflect/xdg")
                .get()
                .asFile.absolutePath,
        )
        systemProperty("metrolist.test.withoutReflection", "true")
        systemProperty("io.ktor.development", "true")
        filter {
            reflectionFreeTestSuites.forEach(::includeTestsMatching)
        }
    }
tasks.named("desktopTest") { dependsOn(testServerWithoutReflection) }

val desktopJar = tasks.named<Jar>("desktopJar")
val desktopClasses = layout.buildDirectory.dir("classes/kotlin/desktop/main")

tasks.register("verifyDesktopJarIntegrity") {
    group = "verification"
    description = "Verifies every compiled shared class and JAR entry used by the desktop runtime."
    dependsOn(desktopJar)
    inputs.file(desktopJar.flatMap { it.archiveFile })
    inputs.dir(desktopClasses)

    doLast {
        val jarFile = inputs.files.files.single { it.isFile && it.extension == "jar" }
        val classesDirectory = jarFile.parentFile.parentFile.resolve("classes/kotlin/desktop/main")
        val compiledClasses =
            classesDirectory
                .walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(classesDirectory).invariantSeparatorsPath }
                .toSet()
        if (compiledClasses.isEmpty()) {
            throw GradleException("Shared desktop compilation produced no classes at ${classesDirectory.absolutePath}")
        }

        ZipFile(jarFile).use { archive ->
            val entries = archive.entries().asSequence().toList()
            val entryNames = entries.mapTo(mutableSetOf()) { it.name }
            val missingClasses = compiledClasses - entryNames
            if (missingClasses.isNotEmpty()) {
                throw GradleException(
                    "Shared desktop JAR is incomplete (${jarFile.absolutePath}); missing ${missingClasses.size} compiled classes",
                )
            }
            entries.filterNot { it.isDirectory }.forEach { entry ->
                archive.getInputStream(entry).use { it.copyTo(OutputStream.nullOutputStream()) }
            }
        }
    }
}

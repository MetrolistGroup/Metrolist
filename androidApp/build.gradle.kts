@file:Suppress("UnstableApiUsage")

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.testing.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@CacheableTask
abstract class GenerateAndroidStringsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val kotlinReference = Regex("""(?<![\w.])R\.(string|plurals)\.(\w+)""")
        val xmlReference = Regex("""@(string|plurals)/(\w+)""")
        val required = sortedSetOf<String>()
        sources.files.sortedBy { it.path }.forEach { source ->
            val pattern = if (source.extension == "xml") xmlReference else kotlinReference
            pattern.findAll(source.readText()).forEach { match ->
                required += "${match.groupValues[1]}/${match.groupValues[2]}"
            }
        }
        val builder =
            DocumentBuilderFactory
                .newInstance()
                .apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }.newDocumentBuilder()
        val catalogs =
            catalogDirectory
                .get()
                .asFile
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .sortedBy { it.name }
                .mapNotNull { directory ->
                    File(directory, "strings.xml").takeIf(File::isFile)?.let { file ->
                        directory.name to builder.parse(file).documentElement
                    }
                }

        fun entries(root: Element): List<Element> = (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }

        fun key(element: Element) = "${element.tagName}/${element.getAttribute("name")}"
        val base = entries(catalogs.single { it.first == "values" }.second).associateBy(::key)
        require(required.all(base::containsKey)) {
            "Android references missing shared strings: ${required.filterNot(base::containsKey).joinToString()}"
        }
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        val transformer = TransformerFactory.newInstance().newTransformer()

        fun escapeAndroidText(node: Node) {
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                // Android resources drop bare quotes; the shared catalogs store plain ' and " for Compose.
                node.nodeValue = node.nodeValue.replace("'", "\\u0027").replace("\"", "\\u0022")
            }
            for (index in 0 until node.childNodes.length) {
                escapeAndroidText(node.childNodes.item(index))
            }
        }
        catalogs.forEach { (qualifier, root) ->
            val document = builder.newDocument()
            val resources = document.importNode(root, false)
            document.appendChild(resources)
            entries(root).filter { key(it) in required }.forEach {
                val entry = document.importNode(it, true)
                escapeAndroidText(entry)
                resources.appendChild(entry)
            }
            val destination = File(output, "$qualifier/strings.xml")
            destination.parentFile.mkdirs()
            transformer.transform(DOMSource(document), StreamResult(destination))
        }
        logger.lifecycle("Generated Android catalog: ${required.size} keys in ${catalogs.size} resource directories")
    }
}

abstract class VerifyComposeResourcesTask : DefaultTask() {
    @get:InputDirectory
    abstract val apkDirectory: DirectoryProperty

    @get:Input
    abstract val resourcePath: Property<String>

    @TaskAction
    fun verify() {
        val apks =
            apkDirectory
                .get()
                .asFile
                .listFiles { file -> file.extension == "apk" }
                .orEmpty()
        require(apks.isNotEmpty()) { "No APKs found" }
        apks.forEach { file ->
            ZipFile(file).use { apk ->
                require(apk.getEntry(resourcePath.get()) != null) {
                    "Compose string resources are missing from ${file.name}"
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sentry.android)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

val sentryAuthToken = providers.environmentVariable("SENTRY_AUTH_TOKEN").filter(String::isNotBlank)
val appVersion = providers.gradleProperty("appVersion").get()
val bundledLanguageTags =
    rootProject
        .file("shared/src/commonMain/composeResources")
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
        .also { require(it.isNotEmpty()) { "No bundled language resources were found" } }

sentry {
    includeProguardMapping.set(true)
    includeSourceContext.set(sentryAuthToken.isPresent)
    autoUploadSourceContext.set(sentryAuthToken.isPresent)
    autoUploadProguardMapping.set(sentryAuthToken.isPresent)
    uploadNativeSymbols.set(sentryAuthToken.isPresent)
    autoUploadNativeSymbols.set(sentryAuthToken.isPresent)
    additionalSourceDirsForSourceContext.set(
        setOf(
            rootProject.file("shared/src/commonMain/kotlin").absolutePath,
            rootProject.file("shared/src/androidMain/kotlin").absolutePath,
        ),
    )
    autoInstallation {
        enabled.set(false)
    }
    ignoredBuildTypes.set(setOf("debug", "benchmark"))
    tracingInstrumentation.enabled.set(false)
    org.set("metrolistgroup")
    projectName.set("metrolist-kmp")
    authToken.set(sentryAuthToken)
}

android {
    namespace = "com.metrolist.android"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    ndkVersion = libs.versions.androidNdk.get()

    bundle {
        language {
            enableSplit = false
        }
    }

    defaultConfig {
        applicationId = "com.metrolist.music"
        minSdk = 24
        targetSdk = 37
        versionCode = 154
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("Long", "DISCORD_APP_ID", "1447278780795064401L")
        buildConfigField("String", "BUNDLED_LANGUAGE_TAGS", "\"${bundledLanguageTags.joinToString(",")}\"")
    }

    val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""
    val storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
    val keyAliasName = System.getenv("KEY_ALIAS") ?: ""
    val keyPasswordVal = System.getenv("KEY_PASSWORD") ?: ""

    val hasReleaseSigning =
        keystorePath.isNotBlank() &&
            storePassword.isNotBlank() &&
            keyAliasName.isNotBlank() &&
            keyPasswordVal.isNotBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("release")
        }
        val releaseConfig = signingConfigs["release"]
        releaseConfig.storeFile = File(keystorePath)
        releaseConfig.storePassword = storePassword
        releaseConfig.keyAlias = keyAliasName
        releaseConfig.keyPassword = keyPasswordVal
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk.debugSymbolLevel = "FULL"
            optimization.keepRules { ignoreFrom("com.materialkolor:material-kolor-android:5.0.1") }
            manifestPlaceholders["appLabel"] = "Metrolist"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) signingConfigs["release"] else null
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
            isProfileable = true
            matchingFallbacks += "release"
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Metrolist KMP Debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("test").resources.directories +=
            rootProject
                .project(
                    ":shared",
                ).layout.buildDirectory
                .dir("generated/assets/copyAndroidMainComposeResourcesToAndroidAssets")
                .get()
                .asFile.absolutePath
        getByName("benchmark") {
            kotlin.directories += "src/release/kotlin"
            manifest.srcFile("src/benchmark/AndroidManifest.xml")
        }
    }

    packaging {
        dex.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/*kuromoji*"
            excludes += "lib/**"
            excludes += "google/protobuf/*.proto"
        }

        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols +=
                setOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                    "**/libsentry-android.so",
                    "**/libsentry.so",
                )
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        // Android lint cannot resolve Compose Multiplatform variants or generated resource accessors from :shared.
        disable += "MissingTranslation"
        disable += "MissingQuantity"
        disable += "PluralsCandidate"
        disable += "UnusedResources"
    }
}

val generateAndroidStrings =
    tasks.register<GenerateAndroidStringsTask>("generateAndroidStrings") {
        catalogDirectory.set(rootProject.layout.projectDirectory.dir("shared/src/commonMain/composeResources"))
        sources.from(
            fileTree("src") {
                include("**/*.kt", "**/*.xml")
                exclude("test/**", "androidTest/**")
            },
        )
        sources.from(
            rootProject.fileTree("shared/src/androidMain") {
                include("**/*.kt", "**/*.xml")
            },
        )
        outputDirectory.set(layout.buildDirectory.dir("generated/androidStrings/res"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(generateAndroidStrings, GenerateAndroidStringsTask::outputDirectory)
    }
}

val composeStringsResource =
    "assets/composeResources/com.metrolist.shared.generated.resources/values/strings.commonMain.cvr"

listOf("debug", "release", "benchmark").forEach { variant ->
    val verification =
        tasks.register<VerifyComposeResourcesTask>("verify${variant.replaceFirstChar(Char::uppercaseChar)}ComposeResources") {
            apkDirectory.set(layout.buildDirectory.dir("outputs/apk/$variant"))
            resourcePath.set(composeStringsResource)
        }
    tasks.configureEach {
        if (name == "assemble${variant.replaceFirstChar(Char::uppercaseChar)}") finalizedBy(verification)
    }
}

// BuildType.initWith does not inherit variant-specific dependencies.
configurations["benchmarkImplementation"].extendsFrom(configurations["releaseImplementation"])

configurations
    .matching {
        it.name in setOf("debugRuntimeClasspath", "releaseRuntimeClasspath", "benchmarkRuntimeClasspath")
    }.configureEach {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

dependencies {
    implementation(project(":shared"))

    implementation(libs.activity)
    implementation(libs.lifecycle.process)
    implementation(libs.material3)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(project(":opusDecoder"))
    implementation(libs.media3.inspector)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.ui)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.datastore)

    implementation(libs.materialKolor)
    implementation(libs.shimmer)
    implementation(libs.compose.reorderable)

    implementation(libs.timber)
    implementation(libs.coroutines.guava)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.okhttp)
    implementation(libs.splashscreen)

    releaseImplementation(libs.sentry.android.ndk)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.multiplatform.settings.test)
    testImplementation(libs.kotlinx.coroutines.test)

    coreLibraryDesugaring(libs.desugaring)
}

tasks.matching { it.name.endsWith("UnitTestJavaRes") }.configureEach {
    dependsOn(":shared:copyAndroidMainComposeResourcesToAndroidAssets")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
}

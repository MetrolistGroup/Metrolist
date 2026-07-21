import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL
import java.util.Properties
import javax.inject.Inject

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val baseApplicationId = "com.metrolist.music"
val applicationIdOverride = System.getenv("METROLIST_APPLICATION_ID")?.takeIf { it.isNotBlank() }
val appNameOverride = System.getenv("METROLIST_APP_NAME")?.takeIf { it.isNotBlank() }
val debugKeystorePathOverride = System.getenv("METROLIST_DEBUG_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val debugKeystorePassword = System.getenv("METROLIST_DEBUG_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val debugKeyAlias = System.getenv("METROLIST_DEBUG_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "androiddebugkey"
val debugKeyPassword = System.getenv("METROLIST_DEBUG_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "android"
val persistentDebugKeystoreFile = file("persistent-debug.keystore")
val workflowDebugKeystoreFile = debugKeystorePathOverride?.let(::file)

plugins {
    id("com.android.application")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

abstract class GenerateProtoTask : DefaultTask() {
    @get:Input
    abstract val protocUrl: Property<String>

    @get:InputFile
    abstract val protoSourceFile: RegularFileProperty

    @get:Internal
    abstract val generatedSourcesDir: DirectoryProperty

    @get:Internal
    abstract val protocExecutable: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val protoFile = protoSourceFile.get().asFile
        val outputDir = generatedSourcesDir.get().asFile
        val protocFile = protocExecutable.get().asFile

        outputDir.mkdirs()

        if (!protocFile.exists() || protocFile.length() == 0L) {
            val url = protocUrl.get()
            logger.lifecycle("Downloading protoc ${url.substringAfterLast('/')} from $url")
            protocFile.parentFile.mkdirs()
            val connection = URL(url).openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw GradleException("Failed to download protoc: Server returned HTTP response code $responseCode for URL: $url")
            }
            connection.inputStream.use { input ->
                protocFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            protocFile.setExecutable(true)
        }

        logger.lifecycle("Generating protobuf files in $outputDir")
        execOperations.exec {
            executable = protocFile.absolutePath
            args(
                "--java_out=lite:$outputDir",
                "--kotlin_out=$outputDir",
                "-I=${protoFile.parentFile}",
                protoFile.absolutePath,
            )
        }
        logger.lifecycle("Protobuf files generated successfully")
    }
}

android {
    namespace = "com.metrolist.music"
    compileSdk = 37

    defaultConfig {
        applicationId = applicationIdOverride ?: baseApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 150
        versionName = "13.6.1"
        resValue("string", "app_name", appNameOverride ?: "Metrolist")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // LastFM API keys from GitHub Secrets
        val lastFmKey = localProperties.getProperty("LASTFM_API_KEY") ?: System.getenv("LASTFM_API_KEY") ?: ""
        val lastFmSecret = localProperties.getProperty("LASTFM_SECRET") ?: System.getenv("LASTFM_SECRET") ?: ""

        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastFmSecret\"")
        buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        buildConfigField("Long", "DISCORD_APP_ID", "1447278780795064401L")
    }

    flavorDimensions += listOf("variant")
    productFlavors {
        // FOSS - Updater, but no gcast
        create("foss") {
            dimension = "variant"
            isDefault = true
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
        }

        // GMS - Updater and gcast
        create("gms") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "true")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "true")
        }

        // IzzyOnDroid - no gcast, no updater - the ONLY F-droid compliant build
        create("izzy") {
            dimension = "variant"
            buildConfigField("Boolean", "CAST_AVAILABLE", "false")
            buildConfigField("Boolean", "UPDATER_AVAILABLE", "false")
        }
    }

    signingConfigs {
        create("persistentDebug") {
            storeFile = persistentDebugKeystoreFile
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("workflowDebug") {
            storeFile = workflowDebugKeystoreFile ?: persistentDebugKeystoreFile
            storePassword = debugKeystorePassword
            keyAlias = debugKeyAlias
            keyPassword = debugKeyPassword
        }
        create("release") {
            storeFile = file("keystore/release.keystore")
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storePassword = "android"
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (applicationIdOverride == null) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
            if (appNameOverride == null) {
                resValue("string", "app_name", "Metrolist Debug")
            }
            signingConfig =
                if (workflowDebugKeystoreFile != null) {
                    signingConfigs.getByName("workflowDebug")
                } else if (persistentDebugKeystoreFile.exists()) {
                    signingConfigs.getByName("persistentDebug")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
    }

    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols +=
                listOf(
                    "**/libandroidx.graphics.path.so",
                    "**/libdatastore_shared_counter.so",
                )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

val protocVersion = libs.versions.protobuf.get()

fun getProtocUrl(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()

    val osName = when {
        os.contains("linux") -> "linux"
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("windows") -> "windows"
        else -> "linux"
    }

    val archName = when {
        arch.contains("x86_64") || arch.contains("amd64") -> "x86_64"
        arch.contains("aarch64") || arch.contains("arm64") -> "aarch_64"
        arch.contains("x86") -> "x86_32"
        else -> "x86_64"
    }

    return "https://repo1.maven.org/maven2/com/google/protobuf/protoc/$protocVersion/protoc-$protocVersion-$osName-$archName.exe"
}

val protoDir = rootProject.file("metroproto")
val protoFile = protoDir.resolve("listentogether.proto")

val generateProto = if (protoFile.exists()) {
    val protocUrl = getProtocUrl()
    val protocFileName = URL(protocUrl).path.substringAfterLast('/')

    tasks.register<GenerateProtoTask>("generateProto") {
        group = "build"
        description = "Generate Kotlin protobuf files"

        protoSourceFile.set(protoFile)
        generatedSourcesDir.set(file("src/main/java"))
        this.protocUrl.set(protocUrl)
        protocExecutable.set(layout.buildDirectory.file("protoc/$protocFileName"))
    }
} else {
    logger.warn("Proto file not found at $protoFile. Skipping protobuf generation.")
    null
}

tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("assemble")) {
        generateProto?.let { dependsOn(it) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
        )
        suppressWarnings.set(false)
    }
}

// Android provides org.json as a platform API (/apex/com.android.art/javalib/core-libart.jar).
// The standalone org.json:json artefact bundles an older Apache Harmony copy of JSONArray that
// contains an internal `myArrayList` field absent from the platform class.  Without obfuscation
// R8 inlines against this internal field; at runtime the platform class is resolved instead,
// producing a NoSuchFieldError.  Excluding the artefact globally ensures only the platform
// class is ever referenced.
configurations.configureEach {
    exclude(group = "org.json", module = "json")
}

dependencies {
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.reorderable)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.material3)
    implementation(libs.palette)
    implementation(libs.materialKolor)

    implementation(libs.appcompat)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.browser)

    implementation(libs.ucrop)

    implementation(libs.shimmer)

    implementation(libs.media3)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)

    // Google Cast - only included in GMS flavor (not available in F-Droid/FOSS builds)
    "gmsImplementation"(libs.media3.cast)
    "gmsImplementation"(libs.mediarouter)
    "gmsImplementation"(libs.cast.framework)

    implementation(libs.room.runtime)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.tinypinyin)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    implementation(libs.hilt)
    implementation(libs.jsoup)
    ksp(libs.hilt.compiler)

    implementation(project(":innertube"))
    implementation(project(":kugou"))
    implementation(project(":lrclib"))
    implementation(project(":lastfm"))
    implementation(project(":betterlyrics"))
    implementation(project(":shazamkit"))
    implementation(project(":paxsenix"))

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.encoding)
    implementation(libs.ktor.serialization.json)

    // Protobuf for message serialization (lite version for Android)
    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
}

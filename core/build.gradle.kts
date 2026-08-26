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
import java.net.URI
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.room)
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
            protocFile.parentFile.mkdirs()
            val connection = URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.inputStream.use { input ->
                protocFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            protocFile.setExecutable(true)
        }

        execOperations.exec {
            executable = protocFile.absolutePath
            args("--java_out=lite:$outputDir", "--kotlin_out=$outputDir", "-I=${protoFile.parentFile}", protoFile.absolutePath)
        }
    }
}

android {
    namespace = "com.metrolist.music.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(localPropertiesFile.inputStream())
        }
        val lastFmKey = localProperties.getProperty("LASTFM_API_KEY") ?: System.getenv("LASTFM_API_KEY") ?: ""
        val lastFmSecret = localProperties.getProperty("LASTFM_SECRET") ?: System.getenv("LASTFM_SECRET") ?: ""
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastFmKey\"")
        buildConfigField("String", "LASTFM_SECRET", "\"$lastFmSecret\"")
        buildConfigField("String", "BASE_VERSION_NAME", "\"13.6.3\"")
        buildConfigField("String", "VERSION_NAME", "\"13.6.3\"")
        buildConfigField("int", "VERSION_CODE", "152")
        buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        buildConfigField("Boolean", "CAST_AVAILABLE", "false")
        buildConfigField("Long", "DISCORD_APP_ID", "1447278780795064401L")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val protocVersion = libs.versions.protobuf.get()
fun getProtocUrl(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osName = when { os.contains("win") -> "windows"; os.contains("mac") -> "osx"; else -> "linux" }
    val archName = when { arch.contains("64") -> "x86_64"; else -> "x86_32" }
    return "https://repo1.maven.org/maven2/com/google/protobuf/protoc/$protocVersion/protoc-$protocVersion-$osName-$archName.exe"
}

val protoDir = rootProject.file("metroproto")
val protoFile = protoDir.resolve("listentogether.proto")
val generateProto = if (protoFile.exists()) {
    val protocUrl = getProtocUrl()
    val protocFileName = URI.create(protocUrl).path.substringAfterLast('/')
    tasks.register<GenerateProtoTask>("generateProto") {
        protoSourceFile.set(protoFile)
        generatedSourcesDir.set(file("src/main/java"))
        this.protocUrl.set(protocUrl)
        protocExecutable.set(layout.buildDirectory.file("protoc/$protocFileName"))
    }
} else {
    null
}

tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("assemble")) {
        generateProto?.let { dependsOn(it) }
    }
}

dependencies {
    api(libs.guava)
    api(libs.coroutines.guava)
    api(libs.concurrent.futures)
    api(libs.activity)
    api(libs.hilt.navigation)
    api(libs.datastore)
    api(libs.compose.runtime)
    api(libs.compose.foundation)
    api(libs.compose.ui)
    api(libs.compose.ui.util)
    api(libs.compose.ui.tooling)
    api(libs.compose.animation)
    api(libs.compose.reorderable)
    api(libs.viewmodel)
    api(libs.viewmodel.compose)
    api(libs.lifecycle.process)
    api(libs.material3)
    api(libs.materialKolor)
    api(libs.media3)
    api(libs.media3.session)
    api(libs.media3.okhttp)
    api(libs.media3.cast)
    api(libs.cast.framework)
    api(libs.mediarouter)
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    api(libs.room.ktx)
    api(libs.hilt)
    ksp(libs.hilt.compiler)
    api(libs.timber)

    api(libs.appcompat)
    api(libs.browser)
    api(libs.jsoup)
    api(libs.apache.lang3)
    api(libs.kuromoji.ipadic)
    api(libs.tinypinyin)
    api(libs.shimmer)
    api(libs.palette)
    api(libs.coil.core)
    api(libs.coil.network.okhttp)
    api(libs.ucrop)

    api(project(":innertube"))
    api(project(":kugou"))
    api(project(":lrclib"))
    api(project(":lastfm"))
    api(project(":betterlyrics"))
    api(project(":shazamkit"))
    api(project(":paxsenix"))

    api(libs.ktor.client.core)
    api(libs.ktor.client.cio)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.client.encoding)
    api(libs.ktor.serialization.json)
    api(libs.protobuf.javalite)
    api(libs.protobuf.kotlin.lite)
    coreLibraryDesugaring(libs.desugaring)
}

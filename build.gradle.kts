plugins {
    alias(libs.plugins.hilt) apply (false)
    alias(libs.plugins.kotlin.ksp) apply (false)
    alias(libs.plugins.kotlin.serialization) apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
    }
    dependencies {
        classpath(libs.gradle)
        classpath(kotlin("gradle-plugin", libs.versions.kotlin.get()))
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// subprojects {
//     tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
//         compilerOptions {
//             if (project.findProperty("enableComposeCompilerReports") == "true") {
//                 arrayOf("reports", "metrics").forEach {
//                     freeCompilerArgs.add("-P")
//                     freeCompilerArgs.add("plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics")
//                 }
//             }
//         }
//     }
// }

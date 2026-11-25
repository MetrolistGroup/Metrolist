import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.sentry.android) apply false
    alias(libs.plugins.sentry.jvm) apply false
}

val minimumJavaVersion = Runtime.Version.parse("25.0.5.1")
require(Runtime.version().feature() == 25 && Runtime.version() >= minimumJavaVersion) {
    "Java $minimumJavaVersion or a newer Java 25 update is required to fix CVE-2026-114938. Update the Gradle JVM."
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        version.set("1.8.0")
        filter {
            exclude { element -> element.file.invariantSeparatorsPath.contains("/build/generated/") }
        }
    }

    tasks.withType<BaseKtLintCheckTask>().configureEach {
        exclude { element ->
            element.file.invariantSeparatorsPath.let { path ->
                path.contains("/build/generated/") || path.contains("/build/kspCaches/")
            }
        }
    }
}

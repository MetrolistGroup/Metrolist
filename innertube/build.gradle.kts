plugins {
    alias(libs.plugins.kotlin.serialization)
    kotlin("multiplatform")
}

kotlin {
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.encoding)
            implementation(libs.kotlinx.datetime)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.newpipeextractor)
            implementation(libs.brotli)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

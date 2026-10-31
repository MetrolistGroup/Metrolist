// :ultra-wiring - Hilt multibindings + androidx.startup initializer that fill UltraHooks.
//
// Feature modules contribute by adding `@Binds @IntoSet` bindings (in their own Hilt module) for
// the contributor interfaces from :ultra-hooks; this module declares the (initially empty) sets and
// folds them into the UltraHooks registry before Application.onCreate. Like every ultra module it
// must never depend on :app.
plugins {
    id("com.android.library")
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ultra.wiring"
    compileSdk = 37

    // Resources live in the app's merged namespace (android.nonTransitiveRClass=false), so a
    // prefix keeps them from ever colliding with upstream resource names.
    resourcePrefix = "ultra_"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":ultra-hooks"))

    // :ultra-hooks declares the types in its contracts compileOnly, so the module that wires
    // them declares what it uses (same catalog entries, hence same versions, as :app).
    implementation(ultraLibs.androidx.annotation)
    implementation(libs.media3)
    implementation(libs.ktor.client.okhttp)

    implementation(ultraLibs.startup.runtime)

    implementation(libs.hilt)
    ksp(libs.hilt.compiler)

    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.material3)
}

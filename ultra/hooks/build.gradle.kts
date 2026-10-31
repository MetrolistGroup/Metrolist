// :ultra-hooks - the seam contracts and the UltraHooks registry.
//
// Dependency rule: this module (and every other ultra module) must never depend on :app.
// :app and :innertube depend on it, which is what keeps upstream code compilable on its own
// and lets Gradle enforce the isolation.
//
// The compose compiler plugin is applied because UltraDestination exposes a @Composable
// lambda; without the plugin the lambda would compile to a different JVM signature than the
// one :ultra-wiring and :app (both compiled with the plugin) expect.
plugins {
    id("com.android.library")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "ultra.hooks"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        // android.util.Log is called by UltraHooks' fault isolation; stub it out on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Types that appear in the public contracts, deliberately compileOnly. :innertube depends on
    // this module (seam S4); with `api` it would inherit ExoPlayer, Compose and Guava on its own
    // runtime classpath, which a pure network library must not. The cost is that a module that
    // implements a contract declares the dependency it uses (which is correct hygiene anyway);
    // :app already has all of them at the same versions, since they come from upstream's catalog.
    compileOnly(ultraLibs.androidx.annotation) // @StringRes/@DrawableRes in the UI contracts
    compileOnly(libs.compose.runtime)          // @Composable in UltraDestination
    compileOnly(libs.media3)                   // AudioProcessor in AudioProcessorsContributor
    compileOnly(libs.ktor.client.okhttp)       // OkHttpClient.Builder in the client contributors

    testImplementation(ultraLibs.androidx.annotation)
    testImplementation(libs.compose.runtime)
    testImplementation(libs.media3)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.junit)
}

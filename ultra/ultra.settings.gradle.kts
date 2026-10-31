/*
 * Ultra layer: settings seam (S1).
 * Applied from the last line of the root settings.gradle.kts.
 *
 * Register each module below by its path under ultra/. The project name is "ultra-" plus the
 * path with "/" replaced by "-" (ultra/core/model -> :ultra-core-model), so a name can never
 * collide with an upstream module.
 *
 * The list is explicit on purpose. A directory scan is invisible to the configuration cache
 * (enabled in gradle.properties), so a newly added module would be silently ignored by
 * command-line builds until the cache happened to be invalidated.
 */

val ultraModules = listOf(
    "hooks",   // seam contracts + UltraHooks registry (no-op defaults); depends on nothing ultra
    "wiring",  // Hilt multibindings + androidx.startup initializer that fill UltraHooks
    // later: "core/model", "feature/oled", ...
)

ultraModules.forEach { path ->
    val moduleDir = settingsDir.resolve("ultra/$path")
    require(moduleDir.resolve("build.gradle.kts").isFile) {
        "Ultra module '$path' is registered in ultra.settings.gradle.kts but $moduleDir has no build.gradle.kts"
    }
    val projectName = "ultra-" + path.replace('/', '-')
    include(":$projectName")
    project(":$projectName").projectDir = moduleDir
}

// Ultra-only version catalog (accessor `ultraLibs`), kept apart from upstream's `libs`.
dependencyResolutionManagement {
    versionCatalogs {
        create("ultraLibs") {
            // files() in an applied script resolves against the script's directory, so anchor explicitly.
            from(files(settingsDir.resolve("ultra/gradle/ultra.versions.toml")))
        }
    }
}

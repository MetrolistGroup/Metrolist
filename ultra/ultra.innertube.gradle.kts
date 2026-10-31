/*
 * Ultra layer: :innertube seam (S3).
 * Applied from the last line of innertube/build.gradle.kts.
 *
 * :innertube may depend on ultra modules only through the contracts in :ultra-hooks; it must never
 * see :ultra-wiring or any feature module. Use string configuration names (no type-safe accessors
 * in script plugins).
 */

dependencies {
    // Seam S4 (InnerTube.createClient) calls UltraHooks.configureApiClient.
    "implementation"(project(":ultra-hooks"))
}

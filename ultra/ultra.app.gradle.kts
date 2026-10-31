/*
 * Ultra layer: :app seam (S2).
 * Applied from the last line of app/build.gradle.kts, after android {} and dependencies {},
 * so every flavor configuration (gmsImplementation, ...) already exists when this runs.
 *
 * Script plugins get no type-safe accessors, so use string configuration names:
 *   dependencies { "implementation"(project(":ultra-wiring")) }
 */

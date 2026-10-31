/*
 * Ultra layer: :innertube seam (S3).
 * Applied from the last line of innertube/build.gradle.kts.
 *
 * This is the only place :innertube may learn about the ultra layer, and only through
 * :ultra-hooks, which must never depend on :app or :innertube (that would be a cycle).
 * Script plugins get no type-safe accessors, so use string configuration names:
 *   dependencies { "implementation"(project(":ultra-hooks")) }
 */

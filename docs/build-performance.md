# Build performance

## CI path selection

`.github/scripts/ci_paths.py` owns the path rules for Android, desktop, iOS, repository checks and Arch packaging. The entry workflows run a small Linux selection job. Common sources, common tests, Compose resources and documentation select one Linux x64 JVM build with shared tests. Android and iOS source sets select their own platform. JVM shared sources select Android and desktop. Platform workflows select their platform. Gradle scripts, catalogs, wrapper files, shared actions, release workflows and unknown paths select everything. Deleted paths and both sides of renames count. An unreadable diff selects everything. Arch packaging runs for recipe or global build changes, not for every desktop edit.

Main pushes run the affected platform's unit tests. Shared-only changes run the shared JVM tests. iOS targets require Apple SDKs, so iOS compiles and tests only on macOS.

## Release verification gate

A version-only bump and its release metadata skip automatic platform verification. Before any packaging or staging, the release gate checks the equivalent source revision. It accepts only successful GitHub Actions check runs from the expected main-branch workflows, including the actual verification jobs. Missing, failed, pending and skipped required checks block the release. The gate retries transient API failures twice. Workflow display names do not affect it; renaming a workflow file requires updating the tested `CHECKS` mapping in `.github/scripts/release_verification.py`. See [releasing](releasing.md#reuse-verification) for recovery.

Release callers pass `run-verification: false` only after the gate passes. `-Pmetrolist.verifyPackaging=false` then drops verification-only Gradle dependencies from the task graph. Manual package builds keep all verification.

| Work skipped in release packaging | Where it runs |
| --- | --- |
| Desktop JVM tests and Windows login-completion tests | Linux desktop verification and manual Windows packages |
| Packaged launch smoke tests | Manual Linux, Windows and macOS packages |
| iOS simulator backend tests | iOS verification and manual IPA builds |
| Windows manager tests and payload installation | `build-desktop.yml` with `windows-manager=true` |
| Shared JAR integrity and iOS dictionary checks | Linux verification; manual packaging also checks final distributables |

Packaging jobs always keep the macOS architecture inspection, APK signature checks, archive existence and version checks, provenance, Sentry uploads, staging and publication. The stable Windows manager installer builds in parallel with portable packaging.

## Caches

Pull requests restore caches without saving them.

Room's schema argument is an `InputDirectory` with relative path sensitivity, following the [Room schema export example](https://developer.android.com/training/data-storage/room/migrating-db-versions#export-schemas), so KSP does not treat the checkout's absolute path as an input. Compare every file under `shared/schemas` after generation. Android string catalogs and shared Compose accessors are cacheable Gradle tasks.

Gradle setup restores dependencies and the local task cache, including the Windows FFmpeg and WebView outputs. Configuration-cache keys include the runner, cache scope, a configuration fingerprint and the commit SHA; restores fall back only within the same fingerprint. Credential-bearing jobs never persist the configuration cache, and release packaging runs with `--no-configuration-cache`. Kotlin/Native keeps separate test and package caches keyed by OS, architecture, Kotlin, Xcode and a dependency fingerprint. Fallback restores keep the full OS, architecture, Kotlin, Xcode and task-graph prefix. NuGet keys use the SDK configuration and project dependencies; compiled helper outputs are not cached. Linux WebView keys use the actual distribution and native library versions.

Compose disables jlink caching because it does not declare the JDK tools as inputs. The runtime task fingerprints the full packaging JDK with relative paths, OS and host architecture, plus Compose's module and option inputs and the CDS JVM flags, so the linked runtime and base CDS archive can use the task cache. Verification builds use an empty source revision to avoid rebuilding on every commit; published binaries carry the real revision. Do not substitute timestamps or credentials to get cache hits.

[Blacksmith's cache documentation](https://docs.blacksmith.sh/blacksmith-caching/dependencies-actions) states that upstream cache actions already use its colocated backend. Keep the pinned upstream actions. [Sticky disks](https://docs.blacksmith.sh/blacksmith-caching/dependencies-sticky-disks) are not used because they allow PR writes unless dashboard branch protection is enabled, which workflow code cannot enforce.

## Runners

Android release packaging uses 8 vCPUs for compilation and R8; debug verification uses 4. Linux packaging uses 4 vCPUs on x64 and 8 on ARM64. Windows x64 packaging uses 4. Windows ARM64 packages natively on GitHub's `windows-11-arm` runner; the `WINDOWS_ARM64_RUNNER` repository variable selects a larger native runner. macOS packaging and iOS use 6, with bounded iOS workers. iOS release links the device framework in its own step before Xcode packaging; the simulator and device frameworks target different architectures and cannot share a link.

## Native Image opt-in runners

Native tester jobs run only on manual dispatch. Linux x64 and Windows x64 use 8 vCPUs for the roughly 10 GiB builder peak, without increasing existing JVM runner sizes. Native compilation keeps two compiler threads and a 12 GiB heap. Linux holds the Gradle lock and uses a 16 GiB scope; all platforms finish Gradle before starting Native Image. Linux ARM64 and macOS ARM64 jobs are prepared but disabled by repository variables. Native jobs add no push, PR or scheduled matrix. Measure hosted time and cost before enabling more targets or scheduling them.

Only checksum-verified GraalVM archives are cached by the native workflow. Its Gradle restore is read-only, with build/configuration caches disabled during input preparation. Native images, traces and profiles are not cached. See [Native Image](desktop-native-image.md) for the complete build and test path.

## macOS x64 packaging on Apple Silicon

The x64 DMG job defaults to `blacksmith-6vcpu-macos-15`, the same Apple Silicon runner as the arm64 job. Gradle, Kotlin compilation and ProGuard use the runner's native JDK. The job passes `-Pmetrolist.desktop.arch=x86_64` to select x64 SQLite, FFmpeg, JNA, WebView and Skiko natives, and `-Pmetrolist.desktop.javaHome=<x64 JDK>` to select the packaging JDK. Compose's [application `javaHome`](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html#specifying-jdk-version) supplies jlink, jpackage and the bundled runtime, which run under Rosetta. The build validates the packaging JDK's architecture instead of requiring an x64 Gradle JVM.

Compose filters Skiko natives by the daemon's architecture. For macOS packages built on another architecture, the task that trims release JARs selects Skiko's target libraries and checksums, preserves metadata and prefixes the JAR name with `metrolist-` so Compose packages those natives intact.

Gradle User Home cache keys include the runner OS, runner architecture and job matrix. The x64 job uses strict matching so it never restores the arm64 job's cache from the same host architecture. Its configuration-cache scope is `desktop-macos-x64`, with persistence disabled.

CI mounts the final x64 DMG read-only and runs `file` and `lipo -archs` on its launcher, bundled `libjvm.dylib`, every loose `.dylib`/`.jnilib` and every such library inside packaged JARs. The launcher, runtime and loose libraries must contain only x86_64. A JAR may keep an arm64 native in an explicit architecture folder only when the same JAR contains the matching x64 path and that counterpart passes verification.

`PatchDmgAppearanceTask` applies the background, Finder layout and volume icon in one mounted image. It keeps the UDRW and ULMO formats, detach retries and mount-retention behavior.

Set the repository variable `MACOS_X64_USE_INTEL_RUNNER=true` to package x64 releases on `macos-15-intel`. The reusable workflow also accepts `x64-use-intel-runner`. [Manual desktop verification](desktop.md#macos-package-verification) offers the same fallback without publishing.

## Local edit regression in Kotlin 2.5

The build pins Kotlin and the Compose compiler to 2.4.21. Kotlin 2.5 [disables](https://github.com/JetBrains/kotlin/blob/v2.5.0-Beta2/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/plugin/PropertiesProvider.kt#L631-L666) `kotlin.jvm.enableIncrementalCompilationOfCommonSources` by default, so any `commonMain` edit reports `Incremental compilation might be incorrect (KT-62686)` and rebuilds all shared sources. Enabling that option can fail common ABI edits with `EXPECT_ACTUAL_IR_MISMATCH`. Kotlin 2.5 keeps the old `kotlin.internal.incremental.enableUnsafeOptimizationsForMultiplatform` name only for a [deprecation error](https://github.com/JetBrains/kotlin/blob/v2.5.0-Beta2/libraries/tools/kotlin-gradle-plugin/src/common/kotlin/org/jetbrains/kotlin/gradle/plugin/diagnostics/checkers/GradleDeprecatedPropertyChecker.kt#L138-L145). Do not use Kotlin 2.5 language features such as unnamed local variables.

`gradle.properties` does not set the internal property because KGP reports its use. Do not disable `kotlin.internal.jvm.enableKmpClasspathMetadataForIncrementalCompilation`; [Kotlin PR 7638](https://github.com/JetBrains/kotlin/pull/7638) explains why common fragments need that metadata. Fresh CI checkouts compile non-incrementally. Clean `:shared` if a local build misbehaves after changing an expect/actual pair.

Room 3 requires KSP with Kotlin code generation ([Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3#3.0.3)). [KSP2](https://github.com/google/ksp/blob/2.3.13/docs/ksp2.md) runs separately from the Kotlin compiler. Room KSP runs only in shared production source sets; `androidApp` does not apply KSP.

Kotlin's [release-cache options](https://kotlinlang.org/docs/native-improving-compilation-time.html#enable-caches-for-release-binaries) trade some compiler optimizations for build speed.

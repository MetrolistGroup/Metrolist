# Releasing

Change `appVersion` only with release authorization. Workflows own signing, checksums, publication and cleanup. Package names and download channels are in [installation](install/README.md#get-the-package).

## Reuse verification

Keep release commits to `appVersion`, `versionCode` and release metadata. `.github/scripts/release_verification.py` follows first-parent history to find the source commit whose tree differs only in `gradle.properties` version assignments, `docs/changelog.md` and `fastlane/metadata/android/*/changelogs/*.txt`. Any other Gradle property or source change ends that search. Manual retries with release fixes must verify those fixes too.

Before creating staging or starting platform builds, releases query GitHub for the applicable Android, desktop, iOS and repository jobs in CI. The gate uses the same path rules as push/PR verification. It requires successful, completed GitHub Actions check runs from the expected main-branch workflows. A successful workflow with a skipped required job is insufficient. Android verification must include the debug checks, not just release APK assembly. A failed CI run blocks reuse even if the last commit did not touch that platform, because one push can contain several commits.

The gate first tries the release revision, then each metadata-equivalent ancestor through the source commit. If it cannot prove verification, it fails before packaging. It does not start a second test matrix or wait for another run. Finish or rerun verification, then retry the failed release run. To verify the current main revision explicitly, run the needed commands below and wait for success.

```bash
gh workflow run ci.yml --ref main
```

Once the gate passes, release jobs build, sign and upload. They skip unit tests, lint, JVM launch smoke tests, verification-only Gradle dependencies and the duplicate iOS simulator job. They still inspect archive versions and native architectures and verify APK signatures. `metrolist.verifyPackaging=false` removes the Gradle verification dependencies only in these gated jobs; normal builds keep them. Manual package dispatches keep final distributable inspection. `build-desktop.yml` with `windows-manager=true` builds and tests the pinned Windows installer against the native portable ZIP, including install, repair and uninstall. Run it when changing installer or payload behavior. Stable releases run the same installer test against the native Windows x64 ZIP before publishing, and the native Linux x64 and Windows x64 jobs run the strict launch smoke. See [release verification gate](build-performance.md#release-verification-gate) for where skipped checks run.

The prepare jobs have read-only Actions and checks permissions for this gate. Release credentials stay in the platform and publication jobs. Credential-bearing Gradle configuration caches are never persisted.

## Native desktop builds

Stable releases and nightly prereleases ship Linux x64 and Windows x64 as native images. Linux ARM64, Windows ARM64 and both macOS packages stay on the JVM. The release renames the native packages to the existing stable asset names, `Metrolist-Desktop-windows.zip` and `metrolist-desktop-linux-x64-<version>.AppImage`, so the in-app updater, the Windows installer, WinGet, Homebrew, AUR and Nix recipes keep their downloads. Native release jobs upload Sentry source context under the same bundle ID as the JVM build for that OS. Dispatch `build-desktop-native.yml` to build one target as a workflow artifact. See [Native Image](desktop-native-image.md#native-builds) for pinned builders, strict smoke coverage and rollback criteria.

## Nightly prereleases

[`nightly.yml`](../.github/workflows/nightly.yml) runs after pushes to main, except changes limited to docs, Markdown, fastlane metadata or assets. GitHub's `[skip ci]` commit marker skips push runs. Maintainers can also dispatch it on main. New runs cancel an unfinished nightly. CI runs the affected platform's ktlint and unit tests alongside Nightly, without debug APK assembly or a separate desktop compile task. Native launch probes run on manual builds only.

All three builds must succeed before publication. The `nightly` tag stays where it was first created, because moving it pushes a ref that the repository's push webhooks announce as a new commit. The release's `target_commitish` records the built commit for update checks instead, and the "Metrolist Nightly" prerelease replaces its assets with exactly one signed Android release APK, one native Linux x64 AppImage and one native Windows x64 portable ZIP. The assets have fixed names without a version, so `https://github.com/MetrolistGroup/Metrolist/releases/download/nightly/<name>` always points at the latest nightly: `Metrolist.apk`, `Metrolist-Desktop-linux-x64.AppImage` and `Metrolist-Desktop-windows.zip`. The release notes and the app show the version. Publication keeps the release in draft while replacing assets, then publishes it as a prerelease without marking it latest. A failed publication can leave a draft; rerun the workflow to finish it.

Notes link the built commit and cover two ranges: first-parent commits since the previous nightly, read back from the commit its published notes link, and since the latest stable release's publish date. Release tags need not be ancestors of main after a history rewrite, hence the date. A separate read-only job writes the notes. With an `OPENROUTER_API_KEY` secret it summarises each range through OpenRouter's `z-ai/glm-5.3-flash` and credits the model in the notes. Without the key, or when a request fails, the range lists up to 50 commit subjects. Nightly builds override `appVersion` with the commit as semver build metadata, such as `14.0.0+abc1234`, so the app and release notes show the exact commit. Build metadata keeps update checks treating a nightly as equal to its base release, where a `-suffix` would mark it older and offer the stable release as an update. The reusable Android and native workflows take this through their `app-version` input, which sets `ORG_GRADLE_PROJECT_appVersion`. Android uses the same signing, Last.fm and Lyprio secrets as stable releases; signing must be available to push runs. Signing secrets stay in the Android job. Nightlies do not send Sentry reports, offer Sentry consent, or upload or finalize Sentry releases. Saved reporting consent is preserved for stable releases. Mapping files and native build evidence remain workflow artifacts.

Apps on the nightly update channel, the default for nightly builds and a switch in Updates settings, read the nightly from the same hourly release list request. A nightly counts as new when its platform asset's SHA-256 digest differs from the one the app last confirmed as installed; a nightly built from the running commit confirms its digest. If the nightly is rebuilt between the check and the Android download, the package no longer matches the cached size or digest; the app then fetches the nightly release again and retries once with its current digest. The signing-key check still applies. After a crash, Android and desktop also offer the nightly on the stable channel when no newer release exists. Desktop opens the platform's nightly download, since the in-app installer only handles stable Windows releases.

## Stable releases

`release.yml` runs on `v*.*.*` tags. The tag must be exactly `vMAJOR.MINOR.PATCH` and equal the suffix-free `appVersion`.

Android 14.0.0 uses `versionCode = 154` in `androidApp/build.gradle.kts`, one above legacy 13.7.0's 153. Raise it above the previous stable release before later tags. Legacy v13 apps update from `MetrolistGroup/Metrolist` `releases/latest`: they read the release title as a dotted version and download `Metrolist.apk` or, for Google Cast builds, `Metrolist-with-Google-Cast.apk`. Keep the title as the bare version and both asset names.

IzzyOnDroid takes `Metrolist-izzy.apk`, built from the `izzy` build type in its own Gradle invocation. It has no Sentry, no in-app updater or install permission, and receives no Lyprio credentials; shared `BuildConfig` leaves Lyprio empty whenever an Izzy task is requested.

1. Run the affected [checks](checks.md) before tagging.
2. Supply Android signing, Last.fm and Lyprio credentials. The workflow checks the APK with `apksigner`.
3. Let platform jobs produce Android, desktop, the pinned Windows manager installer and unsigned iOS packages.
4. Publication validates expected assets and checksums in a draft, then publishes. A rerun cannot modify a published release; it succeeds only when all expected assets already exist.
5. Sentry finalization and intermediate-artifact cleanup run after successful publication. Failures keep artifacts for retry within their retention period.

Android release builds preserve class/method names but optimize line numbers. Use the published `Metrolist-KMP-mapping.txt` with `retrace` to restore source lines. The redacted crash trace keeps its R8 map identifier.

After publication, the workflow downloads assets and generates the `distribution-recipes` artifact. External draft PRs require separate configuration; see [distribution](distribution.md#optional-external-proposals).

## macOS runners

Both architectures normally package on Apple Silicon; x64 runtime tools run under Rosetta. Set `MACOS_X64_USE_INTEL_RUNNER=true` before a new release run to select Intel for x64. Asset names and architecture checks stay the same. See [manual package verification](desktop.md#macos-package-verification).

## Release notes and metadata

`docs/changelog.md` supplies stable release notes. `.github/scripts/parse_changelog.sh vX.Y.Z` reads it by default and extracts the exact `---vX.Y.Z` entry up to the next version marker. It rejects missing or empty entries and TODO markers, ignoring case. Preserve released entries and contributor credits.

The stable prepare job validates that entry and requires a nonempty `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, using the value in `androidApp/build.gradle.kts`. These checks run before the verification gate and all packaging jobs. Publication uses the extracted notes for new releases and refreshes existing drafts; it never falls back to generated notes or changes a published release. Tester notes keep their separate generation flow.

The 14.0.0 Android store notes are in `fastlane/metadata/android/en-US/changelogs/154.txt`. Keep each store note in plain text, at most 500 characters, and derive it from the corresponding release entry. The first KMP release links to the published tag's commit history because the standalone v13.7.0 tag is not in this repository's history.

Android store metadata lives in `fastlane/metadata/android/`, with per-version notes under `changelogs/<versionCode>.txt`. The English listing describes KMP; translated listings come from the original app. Keep source provenance. Before store distribution, provide current phone screenshots and `featureGraphic.png` in the English metadata tree. This does not authorize Google Play publication.

# Releasing

Change `appVersion` only with release authorization. Workflows own signing, checksums, publication and cleanup. Package names and download channels are in [installation](install/README.md#get-the-package).

## Reuse verification

Keep release commits to `appVersion`, `versionCode` and release metadata. `.github/scripts/release_verification.py` follows first-parent history to find the source commit whose tree differs only in `gradle.properties` version assignments, `docs/changelog.md` and `fastlane/metadata/android/*/changelogs/*.txt`. Any other Gradle property or source change ends that search. Manual retries with release fixes must verify those fixes too.

Before creating staging or starting platform builds, releases query GitHub for the applicable Verify Android, Verify Desktop, Verify iOS and Verify repository checks runs. The gate uses the same path rules as push/PR verification. It requires successful, completed GitHub Actions check runs from the expected main-branch workflows. A successful workflow with a skipped required job is insufficient. Android verification must include the debug checks, not just release APK assembly. A failed platform run blocks reuse even if the last commit did not touch that platform, because one push can contain several commits.

The gate first tries the release revision, then each metadata-equivalent ancestor through the source commit. If it cannot prove verification, it fails before packaging. It does not start a second test matrix or wait for another run. Finish or rerun verification, then retry the failed release run. To verify the current main revision explicitly, run the needed commands below and wait for success.

```bash
gh workflow run build-android.yml --ref main -f variant=debug
gh workflow run build-desktop.yml --ref main
gh workflow run build-ios.yml --ref main
gh workflow run verify-repository-checks.yml --ref main
```

Once the gate passes, release jobs build, sign and upload. They skip unit tests, lint, launch smoke tests, verification-only Gradle dependencies and the duplicate iOS simulator job. They still inspect archive versions and native architectures and verify APK signatures. `metrolist.verifyPackaging=false` removes the Gradle verification dependencies only in these gated jobs; normal builds keep them. Manual package dispatches keep final distributable inspection. `build-desktop.yml` with `windows-manager=true` builds and tests the pinned Windows installer against its portable ZIP, including install, repair and uninstall. Run it when changing installer or payload behavior. See [release verification gate](build-performance.md#release-verification-gate) for where skipped checks run.

The prepare jobs have read-only Actions and checks permissions for this gate. Release credentials stay in the platform and publication jobs. Credential-bearing Gradle configuration caches are never persisted.

## Native tester builds

Native packages are never published to users. Dispatch `build-desktop-native.yml` for one target; the package and its evidence archive are uploaded as workflow artifacts. Linux x64 is the first native test target; Windows x64 is available for manual coverage. Releases ship only the JVM/mobile packages. See [Native Image](desktop-native-image.md#opt-in-native-tester-builds) for pinned builders, strict smoke coverage, disabled ARM jobs and rollback/promotion criteria.

## Stable releases

`release.yml` runs on `v*.*.*` tags. The tag must be exactly `vMAJOR.MINOR.PATCH` and equal the suffix-free `appVersion`.

Android 14.0.0 uses `versionCode = 154` in `androidApp/build.gradle.kts`, one above legacy 13.7.0's 153. Raise it above the previous stable release before later tags. Legacy v13 apps update from `MetrolistGroup/Metrolist` `releases/latest`: they read the release title as a dotted version and download `Metrolist.apk` or, for Google Cast builds, `Metrolist-with-Google-Cast.apk`. Keep the title as the bare version and both asset names.

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

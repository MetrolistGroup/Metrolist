# Japanese romanization

## Android and desktop

Kuromoji 0.9.0 supplies tokenizer classes. The build strips dictionary `.bin` resources from the engine JAR; `:shared:verifyKuromojiPackaging` requires an engine below 1 MiB with no dictionary payload.

The first request downloads the pinned Maven Central artifact over HTTPS. Verify its SHA-256 before opening it, extract only the eight required dictionary files, validate the complete set and install by atomic directory rename. Never load classes from the downloaded JAR.

Store the dictionary in Android's `noBackupFilesDir/japanese-dictionary` or desktop's `$XDG_DATA_HOME/metrolist/japanese-dictionary`, outside cache directories. Discard partial or invalid downloads and retry later. Without a verified dictionary, leave kanji unchanged; kana-only text can use the existing converter.

## iOS

iOS bundles the browserify engine from npm `kuromoji@0.1.2` and runs it in JavaScriptCore off the UI thread. It must not download or evaluate remote engine code. The source is pinned to Git commit `d0357c1427acb912815fc8ab59f284dd08bad0fa`; `:shared:verifyIosKuromojiPackaging` checks the vendored engine/bootstrap hashes and rejects bundled dictionaries.

Download the complete 12-file IPADIC payload from pinned HTTPS `raw.githubusercontent.com` URLs only when kanji needs readings. Disable redirects and enforce file-size, total-size, timeout and SHA-256 checks. Serialize installation through a staging-directory rename under Application Support, excluded from device backups. A failed attempt must not leave a usable partial install.

The XMLHttpRequest shim may serve only the twelve verified local dictionary names. Keep it isolated from general network/filesystem access. Clear the temporary base64 map after tokenizer initialization. Feed readings through the existing kana converter so segmentation, punctuation and provider-supplied romanization remain consistent.

## Attribution

Kuromoji is Copyright 2010-2015 Atilika Inc. and contributors. Kuromoji.js is Copyright 2014 Takuya Asano and contributors. Both use Apache License 2.0. The IPADIC data is `mecab-ipadic-2.7.0-20070801`.

Retain the upstream licenses and notices in the iOS app, including the NAIST disclaimer and ICOT terms in `shared/src/iosMain/resources/kuromoji-NOTICE.md`. The reduced JVM JAR retains `META-INF/LICENSE.md`, `META-INF/NOTICE.md` and `META-INF/CONTRIBUTORS.md`. The iOS engine and dictionary files match the published npm tarball and pinned source commit. Verify replacements against the same sources.

## Verification

```bash
node scripts/check-ios-kuromoji.mjs
./gradlew --max-workers=2 :shared:verifyIosKuromojiPackaging :shared:compileKotlinIosArm64
./gradlew --max-workers=2 :shared:desktopTest
./gradlew --max-workers=2 :shared:verifyKuromojiPackaging
./gradlew --max-workers=2 :androidApp:assembleDebug :desktopApp:build
```

The Node check runs the vendored engine/bootstrap in isolation and validates dictionary downloads and failure handling. Linux can run the Node check. iOS cinterop requires Apple SDKs, so compile iOS targets on macOS. Testing the actual app bundle and iOS runtime requires macOS/Xcode and a simulator or device.

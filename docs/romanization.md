# Romanization and language detection

Metrolist uses [glyphmind 0.1.0](https://github.com/nyxiereal/glyphmind) for common Kotlin romanization and local language detection. Android consumes its JVM variant through Gradle module metadata. Desktop and iOS use the same rules, dictionary formats and model. There is no JavaScript engine or platform transliteration API in this path.

## Display and timing

`lyricsRomanizationEnabled` remains the generation and display gate. The Larger text setting, original-script toggle, enabled language list and `cyrillicByLine` retain their behavior. Provider romanization remains authoritative. Glyphmind aligns generated text with the original word boundaries; the adapter preserves all timestamps, background flags and spacing flags.

Japanese uses the NORMAL-mode Kuromoji 0.9.0 lattice algorithm ported to Kotlin. Kana rules and sokuon splitting retain Metrolist's output. Mandarin uses the former JVM MPY1 table on every platform. Arabic, Thai, Hangul, Devanagari, Gurmukhi and Cyrillic retain the bundled rules.

Han-only lines use the local detector when confident. Short or uncertain lines retain the lyric's Japanese/Chinese context heuristic. This can change routing for mixed Japanese/Chinese lyrics. iOS now shares JVM Mandarin phrase readings and Japanese tokenization, including kana-only segmentation.

## Downloads and storage

Only an enabled language used by the lyrics triggers a pack download. Japanese preparation includes kana-only lines to preserve the existing JVM tokenizer output. Chinese now needs a first-use download, including on Android and desktop. Without a verified pack, kanji and Mandarin stay unchanged; kana can still use the bundled converter. Plain lyrics remain available during preparation and failures retry on a later request.

The host supplies Ktor downloads with a byte limit and timeout. Glyphmind pins release URLs under `nyxiereal/glyphmind` tag `dict-v1`, byte sizes and SHA-256. It verifies downloads and staged files, serializes installs, and renames a complete staging directory atomically.

Storage paths are:

- Android: `noBackupFilesDir/glyphmind`.
- Desktop: `$XDG_DATA_HOME/metrolist/glyphmind`, resolved by `XdgUtils`.
- iOS: `Application Support/metrolist/glyphmind`, excluded from backup.

After successful preparation, Metrolist removes the retired `japanese-dictionary` directory on that platform. Packs stay outside evictable caches. Glyphmind's [pack documentation](https://github.com/nyxiereal/glyphmind/blob/main/docs/packs.md) describes generation, formats and notices.

## Local detection and translation

Glyphmind checks scripts and a lazily loaded character n-gram model trained on attributed Tatoeba and FLORES-200 data. It returns an unknown result when uncertain or when no letters are present. The model covers the translation catalog's language families; regional tags share their base language. Hindi written in Latin script is not a separately trained class.

Automatic translation checks each main/background vocal locally. It tests the whole line, script runs and overlapping three-word passages for a confident foreign language. No paid detection request or separate detector model setting remains. Translation still uses the selected provider, and forced requests still bypass detection. Unknown text does not trigger automatic translation.

This is statistical classification, so short interjections, names and short phrases in a shared script can be missed or misclassified. It does not reproduce an AI model's semantic judgment. The library README reports held-out accuracy and unknown rates; those sentence scores are not a lyric benchmark. Manual translation remains available for uncertain lyrics.

Completed cached translations survive detector-policy changes when source, target, translation mode and provider configuration match. Negative decisions carry the new local-detection policy and cannot suppress forced translation.

## Verification

```bash
./gradlew --max-workers=2 :shared:desktopTest :shared:testAndroidHostTest
./gradlew --max-workers=2 :androidApp:assembleDebug :desktopApp:build
./gradlew --max-workers=2 :androidApp:dependencyInsight --configuration debugRuntimeClasspath --dependency glyphmind
```

Run shared iOS compilation and simulator tests on macOS because Metrolist's other native interop dependencies require Apple SDKs. Glyphmind itself publishes all Apple klibs on Linux. Its repository owns the tokenizer oracle, romanizer regression tests, pack installation failure tests and held-out model check.

Metrolist's `GlyphmindDictionaryWiringTest` runs on desktop and Android host tests with temporary storage and Ktor MockEngine. It covers HTTP status and size checks, a verified install, cancellation and cleanup of the legacy directory.

The bundled upstream `NOTICE.md` and `LICENSE` stay at `files/licenses/glyphmind/`; the retained third-party texts sit in its `licenses/` subdirectory, matching the notice. Shared JAR verification checks those references and packaged files; release verification checks the retained files in the final package.

JitPack serves the library as `com.github.nyxiereal.glyphmind:glyphmind:0.1.0`. The JitPack repository in `settings.gradle.kts` is scoped to that group.

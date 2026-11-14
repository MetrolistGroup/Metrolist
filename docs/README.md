# Contributing to Metrolist

To install the app, use the [installation guides](install/README.md): [Android](install/android.md), [iOS](install/ios.md), [Windows](install/windows.md), [macOS](install/macos.md) and [Linux](install/linux.md).

Start with [development.md](development.md) to build the app and find the code for your change. Then run the relevant [checks](checks.md). Read [strings.md](strings.md) when adding UI text or changing translations.

Contributor rules are in [AGENTS.md](../AGENTS.md).

## References for specific changes

Read these when working on the corresponding feature. They are not prerequisites for a first contribution.

| Area | Reference |
| --- | --- |
| Desktop packaging, login, logs and runtime | [Desktop](desktop.md) |
| Desktop Native Image experiment and measurements | [Native Image experiment](desktop-native-image.md) |
| Package-manager recipes and distribution automation | [Distribution](distribution.md) |
| Artwork, animated covers and system colors | [Rendering](rendering.md) |
| Media menus, action ownership and platform differences | [Media menus](media-menus.md) |
| YouTube integration, pagination and live tests | [InnerTube](innertube.md) |
| Cache, downloads and storage safety | [Audio caching](audio-cache.md) |
| Crash recovery, saved reports and downgrade safety | [Crash recovery](crash-recovery.md) |
| Android service binding and startup | [Android startup](android-startup.md) |
| Device pairing and remote playback | [Device Connect](device-connect.md) |
| Shared rooms, codecs and invitation links | [Listen Together](listen-together.md) |
| Playback failures and automatic error skipping | [Playback errors](playback.md) |
| Audio processing and equalizer profiles | [Audio processing](audio-processing.md) |
| iOS Opus playback | [iOS Opus](ios-opus.md) |

## Release and CI

- [Releasing](releasing.md) covers version changes and releases.
- [Build performance](build-performance.md) covers release build bottlenecks, caching and compiler settings.
- [Running without kotlin-reflect](kotlin-reflect.md) lists restrictions on the embedded server.
- [Test fixtures](test-fixtures.md) records provenance and regeneration commands.
- [Japanese romanization](japanese-romanization.md) and [Android and desktop Mandarin data](desktop-mandarin.md) cover dictionary sources, packaging and compatibility.
- [Translation attribution](translation-attribution.md) records imported translation credits and revisions.

Stable release history is in [changelog.md](changelog.md).

# Nucleus metadata and macOS native support

These configurations come from [NucleusFramework/Nucleus](https://github.com/NucleusFramework/Nucleus/tree/67e9920f171a6d569e520d95b53c8e594b44a4d4/plugin-build/plugin/src/main/resources/nucleus/graalvm), commit `67e9920f171a6d569e520d95b53c8e594b44a4d4`.

| Directory | Upstream file |
| --- | --- |
| `linux` | `platform-metadata/linux-reachability-metadata.json` |
| `windows` | `platform-metadata/windows-reachability-metadata.json` |
| `macos` | `platform-metadata/macos-reachability-metadata.json` |
| `jna` | `library-metadata/jna.json` |
| `jna-win32` | `library-metadata/jna-platform-win32.json` |
| `awt` | `library-metadata/jdk-awt.json` |
| `graphics2d` | `library-metadata/jdk-graphics2d.json` |
| `webview` | `library-metadata/compose-webview-wry.json` |
| `fonts` | `library-metadata/jdk-fonts.json` |
| `skiko` | `library-metadata/skia-skiko.json` |

Only formatting and the plugin's `_meta` selector field changed. Native Image receives the configurations directly, without the Nucleus packaging plugin. The agent configuration beside this directory covers Metrolist startup, Home, local playback and YouTube. These additional packs cover desktop JNI paths that the traces did not exercise.

The upstream license, third-party notices and referenced license texts are preserved here.

The macOS AWT port also adapts code from the same commit, copyright 2026 Elie Gambache, under the retained [MIT license](LICENSE):

| Metrolist file | Upstream source |
| --- | --- |
| `scripts/desktop-native-image/macos-awt.c` | `plugin-build/plugin/src/main/kotlin/dev/nucleusframework/desktop/application/internal/configureGraalvmApplication.kt`, default cursor and encoding stubs |
| `scripts/desktop-native-image/java/macos/MacosAwtFeature.java` | `graalvm-runtime/src/main/java/dev/nucleusframework/graalvm/encoding/DarwinPlatformEncoding.java`, `PlatformEncodingInitializer.java`, and `graalvm-runtime/src/main/kotlin/dev/nucleusframework/graalvm/GraalVmInitializer.kt` |
| `scripts/desktop-native-image/native_image.py` and `package.py` | `configureGraalvmApplication.kt`, macOS stub compilation, deployment target, AWT dylib selection, JAWT layout and executable rpath |

Metrolist registers a macOS-only startup hook instead of calling Nucleus's initializer from main. The encoding shim opens the bundled `libjava.dylib` directly and reports a missing initializer instead of returning silently. The deployment target comes from the environment or the bundled AWT dylib. Metrolist keeps `-march=compatibility`, preserves generated shims, validates architectures and signs nested code before the executable and bundle. It does not port Nucleus's SDK/minimum-version rewriting or stripping tasks.

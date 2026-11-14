# Nucleus metadata

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

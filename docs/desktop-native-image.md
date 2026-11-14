# Desktop Native Image

## Opt-in native tester builds

The supported path keeps Serial GC, `-Os`, a 32 MiB initial heap and a 384 MiB maximum heap. GraalVM 25.0.4 downloads and SHA-256 hashes live in [graalvm-pins.json](../scripts/graalvm-pins.json). The hashes come from Oracle's adjacent `.sha256` URLs. Local Linux builds use `/mnt/work/tools/graalvm-25`; do not download or unpack a builder under RAM-backed `/tmp`.

| Target | Build and package | CI state |
| --- | --- | --- |
| Linux x64 | Native app directory and AppImage | Manual dispatch |
| Windows x64 | Native app directory and portable ZIP | Manual dispatch on Windows, without cross-compilation |
| macOS ARM64 | Native `.app` and ZIP | Disabled until `ENABLE_NATIVE_MACOS_ARM64=true` |
| Linux ARM64 | Native app directory and AppImage | Disabled until `ENABLE_NATIVE_LINUX_ARM64=true` |

Windows ARM64 and macOS Intel keep their JVM packages. Every existing JVM package stays the default. The native Linux artifact has a separate `metrolist-desktop-native-linux-x64-<version>.AppImage` name. Native Windows uses `Metrolist.exe`, the existing portable install-manifest schema, and `resources/windows` login helper/checksum layout. Its ZIP has a separate native filename. The native path validates the launcher/helper/DLL architecture and helper checksum.

### Build locally

Prepare with the opt-in Gradle init script. This adds native entry points only for that invocation, exports original dependency jars and copies the existing platform sidecars. ProGuard output remains unsuitable for this build because it removes OkHttp's build feature. Use the lane wrapper on this machine.

```sh
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true \
  /mnt/work/projects/Metrolist-KMP-lanes/bin/gradlew-lane \
  -I scripts/desktop-native-image/prepare.init.gradle \
  -PnativeExperimentDir=/tmp/r6-native-image/supported \
  :desktopApp:prepareNativeImage :desktopApp:compileKotlinJvm \
  --no-configuration-cache --no-build-cache \
  > /tmp/r6-native-image/prepare-supported.log 2>&1
python3 scripts/desktop-native-image/native_image.py \
  --work /tmp/r6-native-image/supported --graalvm /mnt/work/tools/graalvm-25 \
  > /tmp/r6-native-image/build-supported-driver.log 2>&1
python3 scripts/desktop-native-image/package.py \
  --image /tmp/r6-native-image/supported/image --platform linux-x64 \
  --output /tmp/r6-native-image/supported/packages \
  > /tmp/r6-native-image/package-supported.log 2>&1
```

On Linux the builder holds `/tmp/metrolist-gradle.lock` and enters a 16 GiB user scope with 2 GiB swap and CPU weight 50. Native Image gets a 12 GiB heap, four active processors and two compiler threads. Gradle and Native Image never overlap. Native Image arguments use a file, which also avoids Windows' batch command-line length limit. Failed builds leave the last good image intact.

For Windows use the same prepare task on a Windows x64 host, build the existing `metro-wv2-helper` for `win-x64` first, then pass `--platform windows-x64` to the builder and packager. GraalVM requires Visual Studio 2022 17.6 or newer with C++ and the Windows SDK. The [GraalVM Windows guide](https://www.graalvm.org/jdk25/getting-started/windows/) describes those requirements. The CI job builds the helper with the same SDK and publish options as JVM Windows packaging. The executable uses the Windows GUI subsystem and `mainCRTStartup`, matching [Nucleus's linker options](https://github.com/NucleusFramework/Nucleus/blob/67e9920f171a6d569e520d95b53c8e594b44a4d4/plugin-build/plugin/src/main/kotlin/dev/nucleusframework/desktop/application/internal/configureGraalvmApplication.kt#L1198). The builder rejects other host/target combinations.

One native-only substitution replaces `configureDesktopJvmErrorFile`. Native Image has neither HotSpot's `ErrorFile` option nor `_JAVA_OPTIONS` processing, so the JVM AppImage relaunch check otherwise loops. The substitution opens a per-process `native_err_pid*.log` under the same XDG state log directory and redirects native stderr there. It uses GraalVM's bundled C interop SDK and platform C functions, with wide paths on Windows. It changes no JVM source, crash-log setup or restart command. Ordinary application exceptions keep the existing app logger and crash-report flow. Native VM-fatal logs use a native format, so tester reports must include `native_err_pid*.log`; automatic import of those files as HotSpot reports is not claimed.

### CI, metadata and caches

Run `build-desktop-native.yml` manually with one target. Linux x64 and Windows x64 use Blacksmith 8-vCPU runners for builder memory, rather than adding CPUs to existing JVM jobs. Linux ARM64 also uses 8; macOS ARM64 uses the existing 6-vCPU Apple Silicon tier. The ARM jobs require their enable variables and manual dispatch. No native build runs on push, PR or the cold-build schedule. These jobs add manual-run cost only; check actual hosted time before enabling more targets or scheduling builds.

Releases never include native packages. The native package and its separate evidence archive are uploaded as workflow artifacts of the dispatched run.

The toolchain cache holds only the original builder archives. Its key covers OS, architecture, the complete pin manifest and installer source. Every restore rechecks SHA-256 and extracts afresh. A missing or corrupt cache triggers a verified download. Native images, profiles, traces and compiled application outputs are never cached. Native jobs restore Gradle caches read-only, disable the Gradle build cache and do not persist configuration caches, so generated credentials cannot enter new caches. Every native invocation performs a full build from current classes, metadata, resources and sidecars.

Windows loads pinned Nucleus Windows, AWT, font, graphics and JNA/COM metadata alongside Skiko's Direct3D entries. Wry metadata is adapted to the app's current `io.github.kdroidfilter` namespace, with class names checked against `wrywebview:1.0.0-beta-02`. Metrolist's own window procedures, User32 interface, MENUITEMINFO and the JDK's DirectSound mixer/control types have additional Windows metadata. Windows DLL resources and JavaCPP's platform natives are retained. When an interactive desktop exists, the CI tracing agent merges a Windows JVM startup trace before compilation. On service runners without a desktop, the code-derived metadata remains the input.

### Test and inspect

Linux CI gives the packaged AppImage a private Xvfb display. Every smoke run uses an isolated profile. Native executables default to warning on a missing registration and falling back as the JVM would, because an English trace cannot see every locale's resources and a tester build must not exit for that. The smoke runner passes strict mode explicitly and runs with a French locale (`-Duser.language=fr`), so CI still fails on any missing registration, including localized JDK resources. The GUI smoke checks startup, screenshot, shutdown and logs. On Windows service runners without a desktop, the native executable instead runs a strict backend probe for AWT/Skia fonts, Java Sound providers, FFmpeg and JNA/COM initialization. Its result explicitly says GUI behavior is unverified. The smoke collector also checks redirected native stderr, so a missing-registration error there cannot count as a pass. JVM packages retain their existing unavailable-desktop handling.

```sh
xvfb-run -a -s '-screen 0 1440x1000x24' \
  python3 scripts/desktop_smoke.py <native.AppImage> --native \
  --output /tmp/r6-native-image/supported/evidence/smoke
python3 -m unittest discover -s scripts/desktop-native-image -p 'test_*.py'
python3 scripts/test_desktop_smoke.py
python3 scripts/test_build_appimage.py
python3 -m unittest discover -s .github/tests
actionlint
```

CI evidence includes source revision, platform, GraalVM pin, flags, artifact sizes/hashes, builder timing, build log and smoke logs. It includes no profile directory or application credentials. Backend-only Windows evidence does not verify the window frame, taskbar interaction, WebView2 login, Direct3D hardware or actual audio output. Those need a real Windows desktop before Windows promotion. macOS signing/notarization and ARM64 runtime behavior also remain unverified while those jobs are disabled.

### Supported path verification

On 2026-11-13, the supported Linux x64 path passed on base `29b042129e79545c580a0ef407e7554c20f4f386` with this branch's lock and build changes. The full native build took 298.45 seconds, with 8.89 GiB peak builder RSS and 9.79 GiB peak scope memory. The executable is 299.63 MiB, the application directory is 303.84 MiB, and the AppImage is 107.11 MiB. These packaging measurements include the broader JNA/Wry metadata and native crash-log substitution. They do not replace the earlier runtime comparisons below.

The packaged AppImage passed strict startup, live Home content, screenshot and normal shutdown checks on a private Weston/Xwayland display. This host lacks `scrot`, so the local driver supplied an Xlib screenshot to the same smoke runner. CI uses its installed `scrot` on private Xvfb. A separate strict backend run passed font enumeration, Java Sound provider discovery and FFmpeg loading. No physical audio output or GPU was used for these packaging checks. The profile deliberately had no session bus, so Secret Service and MPRIS remained unavailable.

`actionlint`, all 54 `.github/tests`, 17 native build/launcher tests, 13 smoke-runner tests and seven AppImage tests passed. Changed Kotlin was formatted. Metadata JSON, local documentation links, Bash syntax and `git diff --check` passed. The lane wrapper passed desktop compilation, shared tests and ktlint, Android assembly and Android unit tests. The results contain 3,443 desktop tests with five skipped and 803 Android tests, without failures. Existing coverage still includes 60 SongLikeState tests and 20 playback-reporter tests.

Windows metadata and packaging have backend checks, and the Windows C-interop Java sources compile against GraalVM's SDK. No Windows native compilation or hosted workflow has run from this local branch. No push or dispatch was performed. The macOS ARM64 and Linux ARM64 paths are prepared and disabled, without native build evidence. The fetched main has no `cold-build.yml`; this change does not restore or alter scheduled workflows.

### Roll back and promote

To roll back, leave the tester native input off and use the unchanged JVM package. Remove either ARM enable variable to disable its native job. No version bump, default artifact replacement or automatic switch is part of this path. Profile schemas and settings code stay shared with the JVM build; use the same application version when comparing or rolling back.

Before changing a default, the maintainer must record the tester cohort, start date and agreed test period. That duration is still a maintainer decision. Require no Linux tester regressions or missing-registration crashes for the whole agreed period, including launch, persistence, playback, settings, resources and normal desktop integrations. Repeat paired cold/warm startup and idle/local/streaming RSS/PSS measurements on representative hardware; the startup and memory gains must hold and playback CPU must remain acceptable. Test real GPU/audio output and recovery/restart paths. Fix a regression before restarting the agreed observation period. Each later platform needs its own strict CI result and real-machine checks. Windows ARM64 and macOS Intel remain JVM targets.

## Research result

The Linux x64 experiment reaches stage 4. It opens Home, loads real guest recommendations and artwork over TLS, persists settings and a Room database, and plays local Opus and a YouTube stream through FFmpeg/JavaCPP and Java Sound. Playback uses an isolated, clocked PipeWire null sink. Physical speakers and GPU rendering remain untested.

The Serial collector with `-Os` gives the best resident-memory result in this experiment. It also paints Home faster than the processed release JVM control. Final playback CPU roughly matches the JVM. An earlier pass used more YouTube CPU, so this experiment does not establish a CPU benefit. Keep the shipped JVM packaging unchanged and continue Linux testing before considering a native release.

| Stage | Evidence |
| --- | --- |
| 1. Window and Home | Private Weston/Xwayland screenshots show the native Home screen. |
| 2. Database, settings, network, resources and fonts | The native profile contains a SQLite database with 27 tables and saved settings. Home shows live recommendations, fonts and downloaded artwork. |
| 3. Local playback and YouTube | Both runtimes advance playback at real time, report no load error and link their audio streams to the private stereo sink. |
| 4. Comparison | Two cold/warm launch pairs and one local/YouTube pair per runtime in each of three comparisons, 36 successful runs. Exact samples and build metrics are in [desktop-native-image-results.json](desktop-native-image-results.json). |

The starting point was the r2 report on local branch `research/r2-jvm-startup-runtime`. Read its report with `git show research/r2-jvm-startup-runtime:docs/desktop-jvm-runtime-research.md`. Original dependency jars preserve `okhttp3.internal.graal.OkHttpFeature`, which ProGuard had removed while retaining metadata that named it. A 12 GiB builder heap passes the earlier 2 GiB heap limit.

## Measurements

These are screening results on a shared Ryzen 9 7950X3D host with 32 logical CPUs and 30.22 GiB RAM, Linux x64. The native toolchain is Oracle GraalVM 25.0.4, GCC 16.2.1, with the default `x86-64-v3` target and no PGO. Older x64 CPUs need a compatibility build and their own checks. The control uses the release package's processed jars, linked Temurin 25.0.5 runtime, packaged C1/G1 options and dynamic AppCDS. Warm controls mapped their dynamic CDS archives. Both runtimes use a 32 MiB initial heap and 384 MiB maximum heap.

The Serial/`-Os` comparison uses base `eeb29db1413acf0a8d6d57e20361c3cdd86f1318` plus the two app lock commits. The following values are means across the two idle runs or the single playback run. First-paint cells retain both samples. RSS and PSS are MiB. CPU is a percentage of one logical core, so 100% means one busy core.

| Workload | Native first paint, seconds | JVM first paint, seconds | Native RSS / PSS | JVM RSS / PSS | Native / JVM CPU |
| --- | --- | --- | --- | --- | --- |
| Cold idle | 0.714, 0.756 | 1.754, 1.697 | 281.0 / 256.1 | 320.3 / 298.7 | 0.22% / 0.45% |
| Warm idle | 0.662, 0.656 | 0.788, 1.381 | 264.7 / 240.1 | 319.1 / 299.7 | 0.17% / 0.35% |
| Local Opus | 0.494 | 1.596 | 344.0 / 315.8 | 445.5 / 417.4 | 17.66% / 18.16% |
| YouTube | 0.490 | 1.445 | 484.4 / 395.6 | 644.6 / 555.9 | 26.65% / 26.75% |

The final pass has about 20% lower warm idle PSS, 24% lower local-playback PSS and 29% lower YouTube PSS. Native cold-idle PSS varies from 212 to 300 MiB across the two runs. An earlier Serial pass on base `294132e3c` recorded YouTube CPU of 32.72% against 28.42% for its JVM control. Its full twelve observations remain in the JSON as `serial_os_initial`. Source and host conditions differ, and CPU gains are not consistent.

G1/`-O2` paints quickly but uses much more memory during playback. It is a separate comparison with its own JVM controls on base `294132e3cd1c644decef7f78f6f6ce088933fa14`. It is not a repeat on the final rebased source. Collector, optimization and source revision differ, so this does not isolate the effect of the collector alone.

| Workload | Native first paint, seconds | JVM first paint, seconds | Native RSS / PSS | JVM RSS / PSS | Native / JVM CPU |
| --- | --- | --- | --- | --- | --- |
| Cold idle | 0.864, 0.640 | 1.696, 1.544 | 418.1 / 390.7 | 348.9 / 317.5 | 0.20% / 0.42% |
| Warm idle | 0.656, 0.549 | 1.180, 0.690 | 332.5 / 301.8 | 340.0 / 310.5 | 0.17% / 0.37% |
| Local Opus | 0.549 | 1.446 | 716.0 / 685.9 | 464.5 / 434.3 | 16.52% / 18.80% |
| YouTube | 0.548 | 1.809 | 807.6 / 731.2 | 509.9 / 476.1 | 27.96% / 29.55% |

| Build or size | Serial / `-Os` | G1 / `-O2` | JVM release, Serial comparison |
| --- | --- | --- | --- |
| Native builder time | 5m 03s | 9m 30s | Not measured |
| Builder peak RSS | 8.85 GiB | 12.22 GiB | Not measured |
| Builder scope peak memory | 9.66 GiB | 13.48 GiB | Not measured |
| Executable | 286.50 MiB | 518.00 MiB | Uses a linked JVM |
| Full unpacked directory | 290.72 MiB | 522.22 MiB | 247.56 MiB |
| Full directory, tar with gzip level 6 | 112.40 MiB | 184.92 MiB | 141.32 MiB |

Builder time uses a monotonic clock around `native-image`, inside the scope. It excludes preparation, waiting for the Gradle lock and copying loose libraries. Peak RSS comes from `getrusage(RUSAGE_CHILDREN)`. Scope peak comes from cgroup v2 `memory.peak`. Directory sizes sum regular-file lengths, without double-counting symbolic links. Compressed sizes use the same tar/gzip method, after runtime measurements. They are not signed installers or AppImages.

### Method and limits

The runner creates a private HOME and XDG profile under `/tmp/r6-native-image`, with onboarding complete and an empty guest library. It disables Sentry and Discord and supplies an unavailable session bus. It never uses the real user profile, display, audio server or an Android device. Each run gets headless Weston with pixman, rootful Xwayland and Skiko software rendering. Xwayland uses an inherited loopback listener with TCP_NODELAY, with global X11 sockets and lock files disabled.

The benchmark resets the same profile path between runtimes and keeps it for each warm launch. It alternates runtime order across two cold/warm pairs. Cold means fresh application, extracted-library and CDS caches. It does not mean a flushed OS page cache or cold disk boot. Native binaries and measurement files reside on RAM-backed `/tmp` as requested. The final Serial benchmark holds the shared Gradle lock, so lane builds and native builders cannot overlap it.

First paint runs from process creation to the first observed nonblank Compose window interior, polled every 50 ms through X11. This measures the first painted Home shell, not completion of network recommendations. Each run saves first-paint and final screenshots. This is an external experiment observer, not an app UI test.

The runner samples the app process every five seconds. Idle samples cover about seconds 15 through 35. Playback starts after 15 seconds; playing samples cover about seconds 40 through 70. RSS and PSS are the mean samples. CPU is the change in process user plus system time divided by elapsed wall time. Display, observer and audio-server CPU are excluded equally for both runtimes. Both runtimes set `MALLOC_ARENA_MAX=2`. Each complete run has a separate 2 GiB scope with swap disabled. The app heap limit remains 384 MiB.

Playback requires at least 25 healthy observations in the last 30 seconds, at least 20 seconds of media advancement and a rate between 750 and 1250 media milliseconds per probe second. The runner also checks audio links. The local fixture is a 180-second stereo 48 kHz Opus sine wave. The public YouTube fixture is `https://music.youtube.com/watch?v=IgxYypxZDTQ`, already used by repository URL tests. YouTube uses the app's normal stream negotiation rather than a pinned codec/bitrate. Live results depend on network and service behavior.

Raw `smaps_rollup` Swap includes swapped underlying tmpfs shared pages. It is large for the native ELF on this host even when process anonymous swap is zero. The recorded `SwapPss`, `VmSwap` and run-scope swap are zero in these comparisons. Do not add raw Swap to RSS or PSS. See the [kernel proc documentation](https://docs.kernel.org/filesystems/proc.html). Resident PSS here measures the resident working set, not total reserved memory or all shared-host costs.

The sample counts are small. Home content and shared-host scheduling vary, and all rendering and audio use headless software paths. These numbers justify further Linux work, not a release-wide performance claim.

## Changes needed

The original research needed no custom substitutions or dependency upgrades. The supported AppImage path above adds one native-only crash-log substitution. The opt-in init script adds an experiment entry point and exports original jars. Normal builds do not include that entry point or this metadata. Default JVM packaging and application behavior remain unchanged, apart from the separately committed lock implementation changes authorized by the maintainer.

| Component | Failure and resolution | Evidence |
| --- | --- | --- |
| OkHttp / Ktor | ProGuard removes `OkHttpFeature`; the builder uses original jars. Dependency metadata then registers the feature. TLS Home requests and YouTube resolution work. | `dependencies.txt`, successful build logs and native Home/playback logs. |
| Atomicfu / Kotlin bytecode | Graal reports `unexpected non-null predecessor in new successor (... MonitorExit)` in `SongLikeState.refreshLikesFromRemote`, then in `YouTubePlaybackReporter.consume`. Replace only these classes' monitor blocks with atomicfu `ReentrantLock.withLock`. | `build-oracle.log:30`, `build-playback.log:30`; separate app commits. |
| AWT / Skiko / fonts | Preserve the generated `libjava.so` and `libjvm.so` shims. Copy loose JAWT libraries and set native `java.home` to the image directory. Full JVM libraries overwrite those shims and break loading. Add pinned Nucleus Linux/font/Skiko metadata to cover AWT-thread JNI registrations missed by startup tracing. | `build-shims.log`, early runtime logs, Nucleus provenance below; final runs use strict missing-registration exit mode. |
| Compose resources | Add `composeResources/**` and the `composeResources` directory itself (the desktop language list enumerates it) in dedicated application metadata, plus the romanization data, all ICU data and the kuromoji dictionary so non-English locales and lyric romanization never hit a missing registration. The build includes locale resources and charsets. | Home text, fonts and artwork in final screenshots. |
| Room / sqlite-jdbc | Keep sqlite-jdbc's own build feature and the agent's reflected Room constructors. The native profile opens SQLite and contains 27 tables. | Build feature registration and profile database inspection. |
| FFmpeg / JavaCPP | Trace local and YouTube playback to include loader, callback and JNI paths. Keep dependency-provided metadata and embedded native resources. | Agent metadata, real-time playback observations and private audio links. |
| Java Sound / ALSA / PipeWire | The test server has no session manager. Configure its stream ports from the reported audio format, then link stereo output to a clocked null sink. This is experiment setup only. | `audio/server.log`, `audio/links.log` in each playing run. |

The tracing agent ran on the unshrunk JVM application for Home, local playback and YouTube. Its dedicated [resource directory](../scripts/desktop-native-image/resources/META-INF/native-image/com.metrolist/desktop-experiment/) contains 562 reflection entries and 310 resource entries, without absolute build or profile paths. Dependency jars supply their own metadata. Tracing does not cover every application path.

Additional metadata comes from Nucleus commit `67e9920f171a6d569e520d95b53c8e594b44a4d4`. The [provenance file](../scripts/desktop-native-image/resources/META-INF/native-image/com.metrolist/desktop-experiment/nucleus/README.md) lists the exact upstream files and retained licenses. The experiment uses these packs directly without changing the app's Nucleus plugin or dependencies. The working references were [compose-graal-vm](https://github.com/kdroidFilter/compose-graal-vm), [Nucleus GraalVM guidance](https://nucleusframework.dev/en/docs/performance/graalvm/), [Nucleus automatic metadata](https://nucleusframework.dev/en/docs/performance/graalvm/automatic-metadata/), [sqlite-jdbc Native Image support](https://github.com/xerial/sqlite-jdbc/blob/master/README.adoc#graalvm-native-image-support) and [JavaCPP releases](https://github.com/bytedeco/javacpp/releases).

Oracle GraalVM 25.0.4 and Liberica NIK Full 25.0.2 both reproduced the original monitor assertion. Disabling Kotlin bytecode optimization did not fix it. [Graal issue 12694](https://github.com/oracle/graal/issues/12694) reports a similar graph error with ProGuard output; this experiment uses original jars, so it does not establish the same cause. All successful measured binaries use the installed Oracle toolchain on disk. No downloaded JDK or NIK archive remains under `/tmp`.

### Earlier fixes preserved

No animation code changes. Native packaging also preserves the AppImage checksum/cache behavior from `cec60ccc5`, the source-date environment handling from `dab5f3a85`, the cursor probe timeout from `d9e23d4a9`, the Windows unavailable-desktop handling from `d7138960f`, and the prior image-publication/JVM-shim fixes from this experiment. The native crash-log substitution keeps the XDG log location from `85dc749f3`; that commit's HotSpot setup remains unchanged for JVM packages. The lock changes keep the same critical sections, reentrancy and lock order, without adding suspension inside a lock. The separate generation helper retains atomic latest-call checks.

History inspection used `git log -L`, `git log -S` and blame before replacement. The changed SongLikeState monitor lines trace to `d2dd5b30f`, `64713b39f`, `aec44f776`, `0a877178d`, `859a3d6a6`, `74d7a15e2` and `6efec1601`. Their account-scoped likes, background persistence, native-platform parity, shared scans, visible refresh state and Favorites/import behavior remain covered by existing tests. The reporter retains account-isolated history/local-play handling from `7c76a8918`, tracking from `8fc9961aa`, device reporting ownership from `574d4f49b` and iOS integration from `dd2b79d5c`.

## Reproduce

Use Linux x64 with the normal repository toolchain, Python 3 with Pillow and python-xlib, Weston, Xwayland, PipeWire tools, the ALSA PipeWire plugin, flock and a user systemd manager. Keep toolchains on disk. The default GraalVM is `/mnt/work/tools/graalvm-25`. Large outputs belong under `/tmp/r6-native-image`; remove failed image directories and unused profiles when space is tight.

Prepare both native inputs and the release JVM control through the lane wrapper. The init script affects this invocation only.

```sh
mkdir -p /tmp/r6-native-image
JAVA_TOOL_OPTIONS=-Djava.awt.headless=true \
  /mnt/work/projects/Metrolist-KMP-lanes/bin/gradlew-lane \
  -I scripts/desktop-native-image/prepare.init.gradle \
  -PnativeExperimentDir=/tmp/r6-native-image -Pmetrolist.sourceRevision= \
  :desktopApp:prepareNativeImageExperiment :desktopApp:createReleaseDistributable \
  :desktopApp:compileKotlinJvm --no-configuration-cache \
  > /tmp/r6-native-image/prepare.log 2>&1
tail -n 8 /tmp/r6-native-image/prepare.log
```

Generate a local fixture and collect agent metadata. Choose new run names each time. The three traces merge into the agent directory. Run commands to completion before inspecting logs.

```sh
ffmpeg -y -v error -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=180' \
  -ac 2 -c:a libopus -b:a 96k /tmp/r6-native-image/tone.opus \
  > /tmp/r6-native-image/ffmpeg.log 2>&1
python3 scripts/desktop-native-image/run.py trace \
  --java /mnt/work/tools/graalvm-25/bin/java \
  --name trace-home --profile tracing --seconds 50 \
  > /tmp/r6-native-image/trace-home.log 2>&1
python3 scripts/desktop-native-image/run.py trace \
  --java /mnt/work/tools/graalvm-25/bin/java \
  --name trace-local --profile tracing --seconds 75 \
  --media /tmp/r6-native-image/tone.opus \
  > /tmp/r6-native-image/trace-local.log 2>&1
python3 scripts/desktop-native-image/run.py trace \
  --java /mnt/work/tools/graalvm-25/bin/java \
  --name trace-youtube --profile tracing --seconds 75 \
  --media 'https://music.youtube.com/watch?v=IgxYypxZDTQ' \
  > /tmp/r6-native-image/trace-youtube.log 2>&1
cp /tmp/r6-native-image/agent/reachability-metadata.json \
  scripts/desktop-native-image/resources/META-INF/native-image/com.metrolist/desktop-experiment/
```

Review regenerated metadata before committing it. Build outside the lane's 6 GiB scope. The script takes `/tmp/metrolist-gradle.lock`, then runs the builder in a user scope with `MemoryMax=16G`, `MemorySwapMax=2G` and `CPUWeight=50`. It uses `-J-Xmx12g`, two compiler threads, no JVM fallback and exact reachability metadata. A failed build preserves the previous good image and returns failure. Successful builds publish `/tmp/r6-native-image/image`.

```sh
bash scripts/desktop-native-image/build.sh \
  > /tmp/r6-native-image/build-driver.log 2>&1
tail -n 12 /tmp/r6-native-image/build.log
python3 -m json.tool /tmp/r6-native-image/builder-time.json
```

The benchmark also holds the shared Gradle lock for the whole comparison. The default native build is Serial/`-Os`. To compare G1/`-O2`, set `NATIVE_IMAGE_GC=G1 NATIVE_IMAGE_OPTIMIZATION=-O2` for that command. Preserve the image, `build.log` and `builder-time.json` between variants. Do not overlap compilation and runtime measurements.

The release package has a linked runtime without the command-line launcher. Clone that runtime into a new destination for the control and add `java` from the exact JDK version used to create it. This leaves the packaged runtime untouched. On this machine the matching JDK is `/usr/lib/jvm/java-25-temurin`.

```sh
cp -a desktopApp/build/compose/binaries/main-release/app/Metrolist/lib/runtime \
  /tmp/r6-native-image/jvm-runtime
mkdir -p /tmp/r6-native-image/jvm-runtime/bin
cp /usr/lib/jvm/java-25-temurin/bin/java /tmp/r6-native-image/jvm-runtime/bin/java
python3 scripts/desktop-native-image/benchmark.py \
  --java /tmp/r6-native-image/jvm-runtime/bin/java \
  --jvm-config desktopApp/build/compose/binaries/main-release/app/Metrolist/lib/app/Metrolist.cfg \
  --local-media /tmp/r6-native-image/tone.opus \
  --youtube 'https://music.youtube.com/watch?v=IgxYypxZDTQ' \
  --name comparison-serial \
  > /tmp/r6-native-image/comparison-serial.log 2>&1
tail -n 12 /tmp/r6-native-image/comparison-serial.log
```

The runner reads the packaged classpath and JVM options, expands only the app-directory and private CDS/error-path macros, and rejects unknown macros. It does not measure jpackage launcher overhead, AppImage extraction or installer startup. Native runs terminate on missing reachability registrations. Each run saves its command, stdout, process samples, screenshots, display logs and audio logs under its name. A nonzero runner result, missing first paint or failed playback invalidates that run.

## Checks and remaining work

Performance observations retain their measured source revisions above. Final compilation and native smoke checks use rebased base `e1b8ef1fe37b3578d3b484f97edbdda7cafa6489` plus the two lock commits. They do not replace the measured comparisons. The post-rebase native build passed in 5m 07s. Both 75-second playback smoke runs exited normally with strict missing-registration handling and real-time audio progress. Their shared private profile contains two songs, two history events, two history snapshots and 133,230 bytes of persisted settings.

`:desktopApp:compileKotlinJvm`, `:shared:desktopTest`, `:shared:ktlintCheck`, `:androidApp:assembleDebug` and `:androidApp:testDebugUnitTest` passed after the app lock changes. The final run has 3,443 desktop tests with five skipped and 803 Android tests, without failures. Existing coverage includes 60 SongLikeState tests and 20 YouTubePlaybackReporter tests. The experiment has nine backend launcher/build tests. Changed Kotlin was formatted with `ktlint --format`; Bash syntax, metadata JSON, local documentation links and `git diff --check` pass.

The post-rebase Gradle check passed in 4m 30s. An earlier check stopped because the daemon received a stop command, then passed on retry. An earlier Android run failed `PlaylistAutoDownloadTest` with `UncaughtExceptionsBeforeTest` after a closed Room database. A baseline check and repeated runs passed. No unrelated fix was made, and that transient failure is not evidence of a proven base-branch defect.

Evidence stays under `/tmp/r6-native-image`, including builder logs and metrics, benchmark result files, per-run screenshots, CDS logs and audio links. The checked-in JSON keeps the numeric observations and binary hashes without local profiles or media. The G1 image is under `variants/g1-o2/image`; `native-serial-os.tar.gz` preserves the measured Serial build. The `image` directory holds the post-rebase Serial verification build. Archives use gzip level 6. Unused trace/debug profile caches were removed after capture. Logs, metadata, results and the final proof profile remain. No multi-GB JSON or JFR export is retained.

Unverified paths include physical GPU/audio hardware, signed-in accounts, Secret Service, DBus integrations, tray/MPRIS, full navigation, imports/downloads, long playback and suspend/resume. Other codecs, seeking and track transitions also need checks. Some of those paths will need broader agent traces. Linux cannot compile the iOS Kotlin/Native target. Android compilation and unit tests cover the common lock changes, but no connected device was used. Device checks should cover concurrent liked-song refresh/edit/persistence, account switches and playback-history draining on Android and iOS. macOS, Windows and ARM64 have no native build or runtime evidence here.

Continue with the opt-in Linux x64 Serial/`-Os` tester path above. Budget roughly two to four days for broader Linux feature and hardware checks, CPU profiling and repeat measurements before starting the agreed observation period. Windows needs its first native CI build and desktop checks. macOS ARM64 and Linux ARM64 need their own native builds and measurements before enabling their jobs. Keep JVM packages as the default through that work.

## QUESTIONS

What tester cohort and observation period should gate Linux promotion?

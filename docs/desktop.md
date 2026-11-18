# Desktop

## Page chrome

The desktop content surface has no tonal elevation. An elevated parent tints nested Scaffold surfaces while top bars draw their raw theme color, leaving a dark band above the tinted body. Keeping the parent at zero elevation makes page bars and bodies use the same theme surface. This applies to every page in the desktop content host. Settings overlays and dialogs keep their own surfaces. Listen Together's lobby omits its top bar when it has no title, navigation or actions; an active room keeps its toolbar.

Desktop account buttons keep their avatar visible while the popup transforms. Popup bounds use window coordinates measured outside the animated layer. Dismissal waits for the return animation before navigation. Player overflow rows use the same Material menu item shapes as other desktop menus, with rounded ends and small inner corners.

## Backup file dialogs

Linux backup, restore and playlist-file imports keep FileKit when the XDG file-chooser portal is available, so Flatpak can grant access to files outside its sandbox. When the portal is unavailable, Swing and AWT fallback dialogs run on the Swing event dispatch thread. Onboarding restore uses the same picker. Other platforms keep FileKit. File reads and backup work run off the UI thread.

## Fullscreen player

The fullscreen player's exit button restores the previous window placement and closes the expanded player. F and Escape keep the expanded player open when leaving native fullscreen. Artwork and video use the same expanded-player state, so switching between them keeps the window fullscreen. The video controls include a video button to return to artwork.

Windows keeps its native borderless style and placement snapshot separate from Compose's placement. Exit restores the saved style, frame, bounds and maximized state. A window restored in native fullscreen exits to its last normal placement instead of saving fullscreen as the restore target. Fullscreen changes the existing window's style and placement; video does not embed a native surface.

On Linux and macOS, restoring a maximized window first clears native fullscreen, then reapplies the saved placement. The player button and macOS menu use the same placement setter.

Windows paints behind the maximized frame insets and applies the theme background to the AWT and Skiko backing components during startup and normal use.

Windows caption hit testing takes its own snapshot of Compose scene layers. Native window callbacks must not use Compose's reusable layer-copy cache, because rendering can traverse that cache at the same time. Popup order, focused-popup blocking and passive popup-source observers keep their existing hit-test behavior.

Connected action groups use the shared Material shapes and spacing, with fixed corners during press and selection. The fullscreen artwork player's action buttons are 40 dp tall, and its transport buttons share one height. Overflow sizing includes minimum touch targets and the gap before the overflow button so the last visible action has the trailing corners. The bottom volume button stays round beside overflow, with a 12 dp gap before the volume segment.

## Miniplayer

The Linux miniplayer and player top bar use the main window's X11 drag handler, which avoids Compose's JBR window-move lookup. When Java does not export its X11 peer APIs, the handler falls back to pointer-based window positioning. The player top bar uses it with both system and custom window frames. Both JVM and native packages use this handler. Linux keeps the miniplayer window opaque, because some backends stop drawing transparent undecorated windows, so both layouts use square corners there.

Check both layouts on a Linux desktop with the packaged JVM AppImage and native AppImage. Verify dragging, playback buttons, seeking, opening and closing, focus-mode control fades and pressed-button springs. Repeat on X11 and XWayland with OpenGL and software rendering.

## Windows taskbar

The thumbnail toolbar keeps its COM object on one STA thread and pumps native messages while waiting for updates, as required by [COM's STA message loop](https://learn.microsoft.com/en-us/windows/win32/com/single-threaded-apartments). Registration waits for `TaskbarButtonCreated`, as [Win32 requires](https://learn.microsoft.com/en-us/windows/win32/shell/taskbar-extensions). Registration, updates and cleanup share one background thread and use the captured HWND without asking AWT for the peer from that worker.

The [Nucleus JNI implementation](https://github.com/NucleusFramework/Nucleus/blob/8801e033d0080f595611d95dc3790e79f915b330/launcher-windows/src/main/native/windows/nucleus_launcher_windows.cpp) calls COM synchronously on its caller's thread. The app's window procedure receives thumbnail clicks and posts playback actions to the EDT, so Nucleus does not install a competing subclass from the worker. The window procedure accepts a null settings-group pointer in `WM_SETTINGCHANGE`.

With logging enabled, `WindowsWindow` records native minimize and restore sizes and whether restore reaches the EDT.

## System tray

On Linux and Windows, Metrolist uses `ComposeNativeTray` to integrate with native system notification areas (StatusNotifierItem/D-Bus on Linux and Shell_NotifyIcon on Windows). Left-clicking the tray icon toggles window visibility when `desktopTrayToggleOnClick` is enabled, and right-clicking opens the tray context menu.

On macOS, Metrolist uses Compose Multiplatform's stock `Tray` backed by OpenJDK's `java.awt.SystemTray` (`CTrayIcon`) to avoid AWT/AppKit event loop deadlocks. On macOS, left-clicking opens the native status bar menu, and right-clicking triggers the window toggle action when `desktopTrayToggleOnClick` is enabled. Template rendering is explicitly enabled via the `apple.awt.enableTemplateImages` system property so macOS automatically adapts icon contrast to the menu bar appearance. Native macOS images register `CTrayIcon` and `CMenuItem` callbacks for JNI accessibility.

The tray menu toggles miniplayer visibility independently of the main window. Closing the main window to the tray does not stop miniplayer controls.

## Linux audio setup

Desktop uses bundled FFmpeg to decode audio and Java Sound to send PCM through ALSA. Automatic output selection tries named `default`, `pipewire` and `pulse` mixers when the host exposes them. Java Sound's default line is used only when none of those system routes is listed, which supports ALSA devices with other mixer names without bypassing an available system route through a hardware or loopback output.

Linux FFmpeg JARs exclude ALSA, PulseAudio, JACK and PipeWire libraries. JVM distributions, AppImages and native-image builds use the host's audio libraries, configuration and plugins. No Linux target keeps a bundled ALSA fallback. `verifyLinuxAudioLibraries` checks JVM package directories and JARs after packaging, including when other packaging checks are disabled. Native builds check their input classpath before compilation and their output directory before packaging. To check a directory, JAR or tarball directly, run `python3 scripts/desktop-native-image/verify_linux_audio.py <path>`.

PipeWire and PulseAudio hosts need their distribution's ALSA integration. On Arch with PipeWire, install `pipewire-alsa`. If playback progresses silently, check the default ALSA route and restart Metrolist after changing it. An explicit `javax.sound.sampled.SourceDataLine` property delegates selection to Java Sound.

For microcuts, export the playback event log immediately. Output-underrun records describe the buffer and time between writes; they do not identify the cause by themselves.

At the end of a track, desktop waits for the remaining PCM buffer duration plus 250 ms. Pausing suspends this wait. If a mixer never reports an empty buffer, the track still completes so normal and gapless playback can advance.

Crossfade starts the prepared next player muted while the outgoing track keeps playing. Both gains follow the outgoing playback clock, with user volume, normalization and sleep-timer gain applied to each track. Pausing stops both players and holds the fade. The next track becomes current when the fade completes or the outgoing track ends; promotion then closes the outgoing player. Cancelling the fade stops the incoming player and restores the outgoing gain. Same-album gapless playback skips the fade.

## Linux login

Embedded login requires WebKitGTK. Linux credentials require libsecret, `secret-tool`, `dbus-send` and a running Secret Service. Install these runtime dependencies through your distribution.

Linux launchers default `WEBKIT_DISABLE_DMABUF_RENDERER` and `WEBKIT_DISABLE_COMPOSITING_MODE` to `1` to avoid black-window and GBM failures. Ordinary launchers preserve exported overrides; Flatpak forces both values.

## PO tokens

The hidden PO-token WebView is created on first use and reused until shutdown. Idle release navigates it to `about:blank`; the next request loads a fresh attestation document. This bounds native views and contexts because Wry 0.54.2 can retain them after disposal. One idle WebKit process can remain resident. Login and Last.fm views keep their own lifecycles.

On Windows, native WebView creation failures show the error dialog and disable embedded browsers until restart. Pending PO-token requests fail without waiting for the browser timeout, and playback can continue without a PO token. WebView2 error `0x80070002` means the runtime could not be found. Install or repair the [Microsoft Edge WebView2 Runtime](https://developer.microsoft.com/en-us/microsoft-edge/webview2/). The PO-token browser profile uses `%LOCALAPPDATA%/Metrolist/WebView2`, outside the portable ZIP or installation directory. Backup restore imports settings before startup; it does not create a WebView or validate login. Windows YouTube login runs in `metro-wv2-helper`, not the hidden PO-token WebView.

## Discord login

Hiding the window to the tray keeps Discord presence, because playback continues. Quit, SIGTERM and the JVM shutdown hook clear presence before closing the connection. A fresh connection sends an empty presence before publishing the current track. A forced process kill cannot run cleanup; Discord controls how long the disconnected Gateway session remains visible. Track-end timestamps describe playback progress and do not guarantee presence expiry.

Desktop Discord login opens the default browser with a PKCE authorization URL and the `metrolist-auth://discord/callback` redirect. The waiting dialog offers Cancel and Open again. Callbacks use the saved two-minute session, require the exact redirect and state, and consume the verifier before token exchange. macOS open-URI events and second-launch arguments on Windows and Linux enter the desktop URL handler. A callback can restore a pending session after an app restart.

Windows registers `metrolist-auth` under `HKCU\Software\Classes` for the running packaged executable, including portable ZIPs. The macOS bundle declares the scheme beside `metrolist`. AppImage, Arch, Nix and Flatpak desktop entries advertise `x-scheme-handler/metrolist-auth`. Linux reuses an associated package desktop entry when available. Otherwise login writes a hidden per-user desktop entry and verifies its `xdg-mime` association. AppImage registration uses the persistent AppImage path; native Linux packages use the launcher wrapper. Installed Flatpak uses its exported desktop entry.

The embedded login is used when registration fails, when a macOS launch lacks a bundle or open-URI support, or when an unpackaged JVM launch has no native launcher. Linux registration requires working `xdg-mime` and writable user application data. Browser launch failure keeps the waiting dialog available for retry.

## Matugen colors

Appearance's Matugen colors switch replaces the app's color scheme with the one [Matugen](https://github.com/InioX/matugen) generates from the wallpaper. It is off by default, and nothing reads or watches the palette file while it is off. While it is on, Material You, the theme color, song artwork colors and palette style are disabled. Light, dark and pure black still follow the theme setting.

Copy [`matugen-template.properties`](matugen-template.properties) into Matugen's templates directory and add it to Matugen's `config.toml`:

```toml
[templates.metrolist]
input_path = "~/.config/matugen/templates/metrolist.properties"
output_path = "~/.config/metrolist/matugen.properties"
```

The output path follows `XDG_CONFIG_HOME` on every platform, like `settings.properties`. Flatpak reads `~/.var/app/com.metrolist.desktop/config/metrolist/matugen.properties`. Run `matugen image /path/to/wallpaper`; the app reloads the file when Matugen rewrites it. A file with a missing or malformed color is logged and ignored, keeping the last valid palette. Deleting the file restores the normal theme.

## Display density

Linux JVM and native launchers preserve `XCURSOR_THEME` and `XCURSOR_SIZE`. In KDE sessions they read Plasma's `kcminputrc` through `kreadconfig6` or `kreadconfig5`, without applying GNOME cursor defaults. Missing or automatic cursor sizes stay unset for the toolkit to resolve.

Appearance's Display density scales text, icons and controls on top of the system scale. Desktop uses the same 55% to 200% presets and saved preference as Android. Changes apply live to the main window, mini player, Compose dialogs and popups. Library grid columns controls only the library column count.

Linux uses X11 or Xwayland by default. Scale setup runs before AWT window identity, URL-handler registration and Swing dispatcher initialization. In Wayland sessions, scale detection checks the compositor before toolkit environment variables, so a neutral `GDK_SCALE=1` cannot hide KDE's fractional output scale. Without compositor tools, detection uses `QT_SCALE_FACTOR`, the product of `GDK_SCALE` and `GDK_DPI_SCALE`, `XDG_SCALE`, Xft DPI from `xrdb -query`, or `[KScreen] ScaleFactor` in `kdeglobals`. Configuration follows `XDG_CONFIG_HOME`. X11 sessions also use these fallbacks.

KDE's Xwayland clients use one global scale, the largest output scale. When `[KScreen] XwaylandClientsScale=false`, KWin scales legacy windows itself, so Metrolist leaves client density at 100% before applying the user's preset. See [KWin's Xwayland scale selection](https://github.com/KDE/kwin/blob/master/src/workspace.cpp). GNOME, Hyprland and Sway use per-monitor correction and monitor refresh. Native Wayland, enabled with `METROLIST_USE_WAKEFIELD=1`, and Windows and macOS use the toolkit's density before applying the user's preset.

Disable Wayland scale fix bypasses Linux's automatic scale probes and per-window correction in Wayland sessions. It requires a restart because startup also configures the JVM and Skiko scale. It does not disable the user's Display density preset. X11 system-scale changes require a restart; Wayland monitor changes refresh while the app runs.

X11 detection leaves automatic AWT scaling intact so saved window sizes, minimum sizes and fixed-size dialogs keep their native geometry. The user's density preset multiplies the toolkit density.

## Scrolling

Desktop scroll containers use Compose Foundation's built-in wheel animation. Startup leaves `compose.scrolling.smooth.enabled` at its enabled default, including on Windows. The shared carousel modifier lets the container handle wheel input, so lists, rows, grids and scroll pages share Foundation's tick accumulation and precise-input handling. Foundation also settles carousel and pager snapping after wheel input. Android uses its own wheel handler and settling delay.

In Compose 1.13.0-rc01, Foundation recognizes fractional wheel rotation as precise input on Linux and macOS, but its Windows config classifies all AWT wheel events as non-precise. `DesktopScrollConfig.kt` applies the same fractional-rotation check on Windows and delegates all other behavior to Foundation. The config API is internal in this pinned version; check it when upgrading Compose. See the pinned [Foundation sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.13.0-rc01/foundation-desktop-1.13.0-rc01-sources.jar), `DesktopScrollable.desktop.kt`.

Mouse drags continue scrolling at the release velocity with the container's fling behavior. This also applies to Linux touchscreens that report touches as mouse input. Lists, grids and scroll columns use Compose's default deceleration; carousels and pagers keep their snap behavior. Movement thresholds and direction locking preserve taps and gestures handled by child controls. Precise touchpad input must track the gesture directly.

### Horizontal scrolling

Horizontal Home rows show arrow buttons at each edge that has more content. The buttons fade at the scroll limits and animate scrolling. Artwork and video shelves use Material carousel snapping; Speed Dial and song grids scroll freely. Home chips and moods use the same controls. Android and iOS do not show these buttons.

Compose 1.13.0-rc01's `CarouselState` inherits scroll flags that always return true. The controls read its internal pager's measured limits so arrows follow scrolling, window resizing and content changes. Check this accessor when upgrading Compose.

Desktop category pages such as Moods & genres fit as many columns as the available width allows, with a minimum chip width of 240 dp. Chips in the final row share its full width. Category-only pages use 6 dp gaps in both directions.

Scrolling changes need checks with physical wheel, touchpad and touchscreen input, including Shift+wheel and rows nested in vertical pages, on each desktop platform.

## Hyprland rendering

`desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/window/HyprlandRenderVisibility.kt` tracks each window's workspace visibility. Before hiding a GL layer, it copies the retained frame to Java-owned pixels for compositor transitions. Live rendering resumes when visible or when compositor state is unknown. Pinned windows and windows visible on another monitor keep rendering.

Run `HyprlandRetainedFrameTest` for retained-image changes. Native compositor and driver behavior need a Hyprland session.

## Linux ARM64

Build on ARM64 with ARM64 Temurin 25. Gradle builds the Compose WebView native from pinned Rust sources and packages it at JNA's `linux-aarch64` resource path. Renaming an x64 package cannot produce it.

Ubuntu build prerequisites include `build-essential`, `pkg-config`, `libgtk-3-dev`, `libwebkit2gtk-4.1-dev`, `libxdo-dev` and the Rust toolchain pinned by `scripts/build_linux_webview.sh`.

## Windows ARM64

Build on Windows ARM64 with Microsoft Build of OpenJDK 25 for ARM64, Visual Studio's MSVC ARM64 build tools and the Rust toolchain pinned by `scripts/build_windows_webview.ps1`. CI uses GitHub's `windows-11-arm` runner; cross-building from x64 cannot produce the jpackage launcher or the base CDS archive. Adoptium publishes no Windows ARM64 JDK 25, so the Gradle toolchains select Microsoft's build on that host only, and the daemon JVM criteria accept any JDK 25 vendor.

Bytedeco publishes no `windows-arm64` FFmpeg natives. `scripts/build_windows_arm64_ffmpeg.ps1` downloads a pinned BtbN FFmpeg 8.1 LGPL shared build and compiles the JNI libraries with JavaCPP from the same preset classes the app loads. When the preset version changes, pin a BtbN monthly build of the matching FFmpeg branch. Gradle also builds the Compose WebView DLL for `win32-aarch64`. The PO token sidecar has no Windows ARM64 binary, so tokens come from the WebView minter only.

`metro-wv2-helper/build_helper.bat` builds x64 only. For ARM64, publish the helper with `--runtime win-arm64` as the Windows workflow does.

## Native packages

```sh
./scripts/build_appimage.sh
./gradlew :desktopApp:packageReleaseDmg
```

The AppImage script writes `dist/Metrolist.AppImage` and accepts `GRADLE` to select the Gradle command. The DMG task requires macOS and writes under `desktopApp/build/compose/binaries/main-release/dmg/`. The bundle declares macOS 15.0 as its minimum because Bytedeco's FFmpeg dylibs target macOS 15; older systems could open the app but failed to load FFmpeg at playback. Packages include their own runtime.

The AppImage script and Windows and macOS release workflows pass `-Pmetrolist.debug=false` so release packages use the release title and settings.

On launch, JVM and native AppImages install a per-user `Metrolist.desktop` with the persistent AppImage path and a copied icon. Pinned launchers can reopen it after the temporary mount disappears. An installed system `Metrolist.desktop` takes precedence. Moving the AppImage requires launching it once from its new location to update the entry.

The Arch recipe is in [`aur/`](../aur/). `make aur-install` builds from the local tree. Standalone recipes fetch remote source. `bash aur/test-recipe.sh` checks recipe behavior without building the app.

Windows login uses `metro-wv2-helper`. With the .NET SDK selected by `metro-wv2-helper/global.json`, run `metro-wv2-helper/build_helper.bat`. It copies the self-contained x64 helper into desktop resources. Run `dotnet run --project metro-wv2-helper/tests/LoginCompletion.Tests.csproj` for backend checks.

Desktop Settings > Content > Account includes Switch YouTube channel for signed-in users. If Windows cannot load the channel list, Sign in again opens the helper and retries discovery with fresh cookies before saving a selected channel. Cancelling the helper returns to the picker without replacing the saved session. Account-list logs record HTTP status, exception class and parsed/selectable counts, without response bodies or credentials. The helper deletes its browser cookies after extraction, so stored sessions cannot rotate those cookies.

On Android, iOS, Linux and macOS, an unavailable channel list falls back to browser selection. The page explains how to select a channel from the profile picture menu before choosing Complete sign-in. The parser keeps channels with blank optional IDs distinct and tolerates null optional fields.

Sidecars under `desktopApp/src/desktopMain/resources/{linux,macos,windows}/` are gitignored. `generateSidecarChecksums` records the files present during packaging.

### macOS package verification

x64 packaging uses `-Pmetrolist.desktop.arch=x86_64` for natives and `-Pmetrolist.desktop.javaHome=<x64 JDK>` for jlink, jpackage and the runtime. Cross-architecture macOS builds unpack the target Skiko dylib and checksum into the packaging inputs because Compose selects Skiko natives for the Gradle host. CI verifies the mounted DMG's launcher, JRE and native architectures, and requires a loose Skiko dylib when the launcher sets `skiko.library.path`. Packages are unsigned and unnotarized.

A manual `build-desktop.yml` run verifies Linux and builds both DMGs as artifacts. `macos-x64-use-intel-runner=true` selects Intel instead of Apple Silicon with Rosetta. The repository variable `MACOS_X64_USE_INTEL_RUNNER` also controls that choice.

Linux cannot validate DMG packaging or Rosetta. Before release, inspect Finder background, icon placement and volume/file icons, then test launch, playback and login on a Mac.

### Flatpak

The local manifest is `flatpak/com.metrolist.desktop.yaml`, with app ID `com.metrolist.desktop`. It builds the committed `main` branch with its submodules, so commit changes before building.

```sh
make flatpak-deps
make flatpak-install
make flatpak-run-installed
make flatpak-bundle
```

`make flatpak-run` uses the build directory without installation. Keep the manifest's GNOME runtime version and `FLATPAK_RUNTIME_VERSION` in `Makefile` synchronized. The [distribution reference](distribution.md#flatpak) covers flatpark, which ships the Flatpak users install.

## Runtime and CDS

`desktopApp/build.gradle.kts` defines launcher options. Packaging generates a Java 25 base CDS archive with the target jlinked VM, without running the app. [AutoCreateSharedArchive](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html#creating-dynamic-cds-archive-file-with--xxautocreatesharedarchive) saves an optional `appcds-<bundle-id>.jsa` dynamic archive on normal exit and reuses it on later launches.

JVM packages include `jdk.accessibility` so Windows can load Java Access Bridge when assistive technology is enabled. Native-image builds use the full GraalVM JDK and the existing AWT AccessBridge metadata rather than a jlink module list.

| OS | CDS cache |
| --- | --- |
| Linux | `$XDG_CACHE_HOME/metrolist`, default `~/.cache/metrolist` |
| macOS | `~/Library/Caches/com.metrolist.desktop` |
| Windows | `%LOCALAPPDATA%/Metrolist/cache` |

Archives stay outside read-only app mounts and installation directories. On Windows, both portable ZIP and installer launchers use `%LOCALAPPDATA%`, which [JDK 25's launcher expands](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/jdk.jpackage/share/native/applauncher/CfgFile.cpp). Close the app before removing a corrupt archive. An unwritable cache can cause a nonzero exit when HotSpot saves it at shutdown. Use `-Xlog:cds=info` to check archive mapping.

AppImage packaging uses `createReleaseRuntimeImage`. A second jpackage runtime link would omit the base CDS archive and `jdk.localedata`, so the launcher finalizer compares the packaged module image and archive with the configured runtime before compression.

[JavaCPP caches extracted libraries](https://github.com/bytedeco/javacpp/blob/1.5.14/src/main/java/org/bytedeco/javacpp/Loader.java) under `~/.javacpp/cache`, with JAR/resource subdirectories and size/timestamp checks, so native libraries are not extracted on every launch.

### Startup

`Main.kt` runs crash-report handoff, JVM crash-file configuration, allocator setup, CDS cache creation, platform/display checks and the single-instance lock before the window. Pending backup restore and settings/credential migration finish before database access or logging. Saved locale, geometry, window frame and Wayland scale also apply before creating the window. Secure-store reads stay in this settings step; they must not race restore or credential migration.

Windows startup enables the [JDK system proxy selector](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/doc-files/net-properties.html) before creating network clients. This lets shared HTTP clients use the Windows proxy configuration, including a local proxy configured by Clash/Mihomo. Explicit JVM proxy properties take precedence, and an explicit `java.net.useSystemProxies` value is preserved. Test Home, search, sign-in and playback on Windows with the reporter's proxy mode and port before treating a TLS connection reset as resolved.

The CDS directory is created before crash recovery or a JVM relaunch can exit. Otherwise HotSpot fails with `Unable to create shared archive file` followed by an unrecoverable archive-loading error. The Linux wrapper resolves the HotSpot error-file path from an absolute `XDG_STATE_HOME` before starting the VM, which avoids a crash-path relaunch for custom profiles. Other launcher overrides keep the relaunch guard. Windows uses `$USERPROFILE/.local/state/metrolist/logs` by default and guards custom profiles.

Initial Linux scale detection has a shared 2 s command-probe budget before AWT, including `xrdb`, `gsettings` and compositor queries through `gdbus`. These results configure JVM/Skiko scaling; deferring them would initialize AWT at the wrong scale. The Swing application scope stays lazy until after this step. Monitor refresh runs on a background watcher. Both the loading frame and full window use `DesktopDensityProvider`, so the user's density preset also applies to the first frame. Linux URL-handler registration waits for the full app frame.

The loading window draws the Home shimmer under the saved theme and locale-aware system font. `DesktopStartup` waits for a draw and a visible window before initializing SQLite, Koin, Room and its downgrade check on IO. The same worker binds song metadata, restores scrobble accounts and content region, and constructs playback, download, cache and Home state. This keeps catalog reads, file reconciliation and client construction off the EDT. The normal app frame appears without waiting for Home's network response.

The Linux loading shell and full app share the custom caption controls. Minimize, maximize/restore and close stay available during initialization when system decorations are disabled. The shell frame must not access `PlayerState`. Fullscreen hides the custom controls.

After that frame, consent-controlled Sentry on stable releases, enabled Discord integration, native media controls, tray and miniplayer start. Nightlies never initialize Sentry and preserve the saved consent. Windows registry registration runs after the shell and must finish before SMTC attaches. The process AppUserModelID applies before any UI, as [Microsoft requires](https://learn.microsoft.com/en-us/windows/win32/api/shobjidl_core/nf-shobjidl_core-setcurrentprocessexplicitappusermodelid). Taskbar icons fill their cache on IO; taskbar registration uses its COM worker and delayed retry. The Rust WebView/WebView2 paths load on demand for login, token minting or Last.fm browsing, as [Microsoft advises for WebView2](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/performance). FFmpeg loads when playback or a download needs decoding. Downloaded fonts load on IO; the first frame uses the locale-matched system font.

`METROLIST_STARTUP_TRACE=1` with detailed logging enabled writes process ID and JVM uptime for `main`, `settings`, `shell-frame`, `runtime`, `database`, `backend` and `app-frame` to stdout/app.log. Frame markers mean Compose draw completion, not display presentation. Leave this variable unset for normal launches.

## Credentials and settings files

`shared/src/desktopMain/kotlin/com/metrolist/shared/settings/PlatformSettings.desktop.kt` owns settings persistence. Public preferences use `$XDG_CONFIG_HOME/metrolist/settings.properties`, defaulting to `~/.config/metrolist/settings.properties`. A complete pending snapshot takes precedence after failed replacement. See [corrupt-file recovery](crash-recovery.md#settings-recovery).

Windows protects credentials with DPAPI. macOS uses AES-GCM with a Keychain key. An unavailable Keychain is retried; never replace an existing encrypted file with plaintext. Authentication failure preserves a unique encrypted backup. Only the original key can decrypt it.

Linux reads and writes Secret Service through libsecret. Missing tools, locked collections, timeouts and failed reads leave recovery pending. They do not imply sign-out. The account menu explains that a running, unlocked keyring and the package providing `secret-tool` are needed instead of showing an indefinite loading indicator. Recovery retries automatically without restart. macOS shows an unlock message while its Keychain is unavailable.

Linux records pending mutations in owner-only `secrets.properties` before acknowledging them. The journal keeps values and deletion markers through restart. Keyring I/O stays outside the settings lock. Remove the journal only after writing and reading back matching values. Truncated writes and unreadable duplicates must leave it intact.

Select the newest readable matching keyring item. Unlock a locked match if it could outrank that item. A clear marker prevents an old duplicate from restoring cleared data. Do not overwrite unread entries or log credential values.

### Isolated Linux credential verification

`scripts/verify_linux_credentials.py` runs production settings code in fresh JVMs against a throwaway Secret Service. It uses fake credentials and temporary profile directories. Requirements are `dbus-run-session`, `dbus-send`, `secret-tool`, `gnome-keyring-daemon`, Python with PyGObject and the project JDK.

Create `/tmp/credential-probe.init.gradle` to export the test runtime classpath:

```groovy
gradle.projectsEvaluated {
    def shared = gradle.rootProject.project(':shared')
    shared.tasks.register('writeCredentialProbeClasspath') {
        dependsOn(shared.tasks.named('desktopTestClasses'))
        doLast {
            new File('/tmp/credential-probe.classpath').text = shared.tasks.named('desktopTest').get().classpath.asPath
        }
    }
}
```

Set `GRADLE` to the Gradle command, such as `./gradlew`, then run:

```sh
"$GRADLE" --no-configuration-cache -I /tmp/credential-probe.init.gradle :shared:writeCredentialProbeClasspath
python3 scripts/verify_linux_credentials.py --classpath-file /tmp/credential-probe.classpath --java "$JAVA_HOME/bin/java"
```

A successful run prints `PASS isolated Secret Service`. KDE Wallet, macOS Keychain and Windows DPAPI need checks on their platforms.

## Render backend

The hidden desktop Devtools page has a Render backend setting and a Restart app action. Auto keeps the platform defaults, including OpenGL on Linux and Metal on macOS. Launchers and Gradle run tasks leave `SKIKO_RENDER_API` unset unless the user supplies it, so their defaults do not block the preference. Direct3D appears only on Windows, Metal only on macOS, and OpenGL is hidden on Windows ARM64. Software is available on each desktop platform. Skiko maps `SOFTWARE` to `SOFTWARE_COMPAT` on macOS and `SOFTWARE_FAST` elsewhere. In Linux playback measurements, COMPAT costs about three times the CPU per frame because it copies each frame through Java2D.

The preference takes effect on restart, before AWT and Skiko initialize. An explicit `SKIKO_RENDER_API` or `-Dskiko.renderApi` takes priority. Skiko uses its own fallback when a selected renderer cannot initialize. With logging enabled, the first painted window logs the requested and actual APIs as `Skiko render API: requested=... actual=...`. Include that line when reporting backend comparisons.

## Logs and profiling

Sanitized warnings and errors go to `$XDG_STATE_HOME/metrolist/logs/app.log`, defaulting to `~/.local/state/metrolist/logs/app.log`. On Windows the default is `%USERPROFILE%\.local\state\metrolist\logs\app.log`. Detailed logging adds lower severities. Rotation keeps five 10 MiB archives.

With detailed logging enabled before launch, Skiko logs its selected renderer and GPU when it initializes a rendering context. An explicit `skiko.hardwareInfo.enabled` property overrides this behavior. After the first visible frame, `Runtime` records the JVM vendor, build, architecture, VM mode, selected JIT/CDS arguments and requested Skiko backend. HotSpot's `emulated-client` mode describes its C1 configuration, not CPU emulation. Requested options do not prove which backend or CDS archives are active. Use Skiko's `Renderer info` for the active backend and `-Xlog:cds=info` for archive mapping. These diagnostics leave rendering and JVM defaults unchanged.

The logger buffers writes. Export or normal shutdown flushes them; native termination can lose pending records. Playback exports use a separate in-memory buffer. Capture browse failures from `app.log` and its archives after retrying the page.

Packaged JVMs write fatal HotSpot reports as `hs_err_pid<PID>.log` in the log directory. A termination outside HotSpot's fatal handler may produce no file.

Desktop playback installs FFmpeg's native log callback. Warnings and errors go to both `app.log` and the playback event log; the file logger flushes these records immediately. FFmpeg runs inside the JVM, so it has no separate process exit code or stderr pipe. Unexpected demuxer errors and video decoder errors include the native return code. Desktop tempo and pitch processing uses float samples and combines both settings into one `atempo` ratio; it skips WSOLA when the ratio is one, as in varispeed. FFmpeg decodes video into Skia bitmap storage. Video teardown joins decoder threads and uses draw locking; keep both, along with padded scaler rows.

Animated covers keep one decoder at a time per artwork slot. Track changes wait for the previous load, decoder and draw to finish before creating the next cover player. Failed loads or ended playback clear the animated frame so static artwork remains visible. Cover cancellation aborts pending downloads and waits for native cleanup off the UI thread.

Player artwork keeps its video drawing node mounted when switching between static and animated covers. With animated covers enabled, shared-element bounds and player fades still animate, but artwork draws in its owning tree rather than the shared transition overlay. Static artwork keeps the overlay when animated covers are disabled. Video paints and shader builders belong to each draw, so composition disposal cannot close them before a queued draw runs. The renderer copies mutable decoder bitmaps into Skia images under the frame lock; recorded draws retain those images after decoder reuse or teardown. Backend tests cover deferred shader replay and disposal ordering, not Windows GPU rendering. Retest rapid expansion, collapse and track changes with Canvas and music video enabled on Windows. Native packages write fatal diagnostics to `native_err_pid*.log` in the same log directory; see [Native Image diagnostics](desktop-native-image.md#test-and-inspect).

The Spotify sign-in button in player artwork settings opens the same login window as playlist imports. Successful authentication saves the Spotify session for Canvas artwork; failures leave the login window open for retry.

`session-running.txt` records an active desktop session. Normal app shutdown removes it. On the next start, a remaining marker or an unreported `hs_err_pid` file produces a diagnostic in both logs. On stable releases, when crash reporting is enabled and an unreported crash file exists, the Sentry client sends a previous-exit event with `previous-desktop-exit.txt` attached. The attachment contains the session description and a bounded, redacted summary of each crash header and native stack. It omits VM arguments, environment variables and memory dumps. The original crash files stay on disk; `.reported` files prevent repeated uploads. An unfinished shutdown alone also covers forced termination and power loss, so it does not prove a native crash. Sentry events include `desktop.os`, `desktop.os_version` and `desktop.arch` tags.

`scripts/metromem --help` lists profiling modes. Keep heap dumps and traces private; they can contain account data.

### Crash reports

After a crash, restart the app once so the previous-exit diagnostic is written. Then collect `app.log`, its recent archives, any `hs_err_pid<PID>.log`, and `session-running.txt` if present. On Windows these are in `%USERPROFILE%\.local\state\metrolist\logs`, or `%XDG_STATE_HOME%\metrolist\logs` when `XDG_STATE_HOME` is set. After a video-load failure, export the playback event log. Include the build ID, exact failure time, track link, OS version, GPU and driver version. On Windows, also include the Event Viewer Application Error entry.

### Account sync failures

Android, desktop and iOS show YouTube Music sync failures through a snackbar. Library, playlist, account, liked-song and history reads offer Retry. Confirmed account rejection and explicit logged-out responses use the expired-session prompt. A browse-only HTTP 403 must pass account revalidation before it can expire a session.

Partial library scans keep cached items, skip removal reconciliation and leave the successful-refresh timestamp unchanged. They show an error even when some items remain visible. The desktop playlist picker keeps its previous remote list after a failed refresh. Playlist membership repair also reports failures. Optional playlist-count lookups do not determine sync success.

Desktop player titles resolve missing or invalid album links through the shared resolver. Album pages keep the first track page and offer Retry if a continuation fails. Successful loads prepare the complete track list before enabling playback.

For an empty library, capture startup, one library refresh and one title click in `app.log`. `DesktopAuth` records credential availability, the account index and whether a channel identity exists. `Account browse` records the endpoint, HTTP status, continuation stage and auth availability. `LibrarySync` records completeness and item counts. `Song album` and `Album browse` record the supplied/resolved IDs and the failing parse or track stage. These records omit cookies, visitor data, account names, emails and response bodies. Collect the log after normal shutdown or export so buffered records reach disk.

A useful report also includes the app version, the song's YouTube Music URL, the album URL, the selected channel, whether the official YouTube Music library contains those items, and whether signing in again changes the result. Android can contain local library entries that the remote account does not contain.

## Updates and restore

Desktop checks the public Metrolist release list and keeps cached notes after failures. Registered Windows installations can download and verify stable installers. Portable installations, other systems and prereleases open the release page.

Restore accepts `.zip` and `.backup` with the same validation. Preserve original databases, sidecars and settings until installation succeeds or rollback completes. Failed restart-helper launch disarms restore and reports the error. Use disposable profiles for recovery checks.

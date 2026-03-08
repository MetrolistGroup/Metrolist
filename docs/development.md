# Development

## Set up a checkout

Use Eclipse Temurin 25, including for the IDE's Gradle JVM. The build requires the `ADOPTIUM` vendor. Java and Kotlin bytecode target 21; the desktop runtime is Java 25. For a custom JDK location, set Gradle's `org.gradle.java.installations.paths`.

JVM tests and desktop launchers enable native access for Skiko, JNA, SQLite and Conscrypt. They allow the Unsafe memory access that protobuf and DataStore use on Java 25.

`gradle.properties` caps the Gradle daemon at 4 GB of heap and 1 GB of metaspace, runs Kotlin in that daemon, limits builds to 4 parallel workers and stops idle daemons after 30 minutes. Android lint and Kotlin/Native compilation also run inside the daemon, so a full Android, desktop and iOS check with more workers or less heap can fail with `GC overhead limit exceeded` even when the machine has free memory. Override these values in `~/.gradle/gradle.properties` instead of editing the shared file. If combined checks exhaust the heap, increase `org.gradle.jvmargs` for that invocation and use `--no-parallel`.

Kotlin and the Compose compiler share the catalog's `kotlin` version. Common-source edits may compile non-incrementally, and fresh CI checkouts always do. KLIB cross compilation is disabled because the iOS cinterop requires Apple SDKs; build iOS targets on macOS. If a local build misbehaves after changing an expect/actual pair, clean `:shared`. Read [build-performance.md](build-performance.md) before changing compiler or heap settings. A no-op build does not measure performance after a source edit.

To diagnose a slow build, add `--profile --no-scan -Pkotlin.build.report.output=file` to collect task times and Kotlin rebuild reasons. Compare an unchanged second invocation with an edit to common sources, and note the command, RAM, whether it followed a clean or a source edit, and which file changed.

From the repository root:

```bash
git submodule update --init --recursive
./scripts/setup-hooks.sh
```

`metroproto` supplies the shared protocol definitions; coordinate schema changes in its upstream repository. `opusDecoder` needs its `libopus` submodule for native builds.

Install the Android SDK required by `androidApp/build.gradle.kts`, accept its licenses and set `sdk.dir` in the untracked `local.properties`. `./scripts/deps.sh` can provision Temurin 25 and an Android SDK on Linux and macOS. On Windows, install both manually; the script rejects Windows. With SDK command-line tools available, `buildOpusJni` installs its pinned NDK and CMake when missing.

## Build and run

### Android

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Use a connected device or booted emulator running Android 7 or later. Releases use one universal APK; ABI splits are unsupported.

Debug APKs include unshrunk code and debugging dependencies, so compare release APKs when measuring shipped size. Incremental ZIP packaging can retain unused bytes; delete the APK output directory and package again before comparing sizes.

### Desktop

```bash
./gradlew :desktopApp:run
./gradlew :desktopApp:build
```

The second command runs desktop checks but not shared tests. Linux login needs system WebKitGTK libraries. Audio decoding uses the bundled FFmpeg libraries. See [desktop.md](desktop.md) for platform prerequisites, packaging and troubleshooting.

### iOS

Use macOS with Xcode, its command-line tools and an installed iOS Simulator runtime. Boot a simulator before using `--run`.

```bash
./scripts/build_ios.sh --sim --run
```

The script builds the shared Kotlin framework through the Xcode project and copies the simulator app to `dist/Metrolist.app`. Open `iosApp/iosApp.xcodeproj` to debug the Swift host or configure signing. Direct Xcode builds and the script both derive bundle version metadata from `appVersion` through `scripts/ios_version.sh`.

```bash
./scripts/build_ios.sh --ipa
```

This produces an unsigned `dist/Metrolist.ipa`. It needs signing or a sideloading tool before device installation. Linux can compile shared iOS sources but cannot build or run the Xcode app.

## Find the code for a change

See [AGENTS.md](../AGENTS.md#modules) for modules and source sets. Put reusable logic in `shared`; keep platform APIs and native lifecycle handling in their platform source sets. Desktop Kotlin is under `desktopApp/src/jvmMain/kotlin/`, but its resources belong in `desktopApp/src/desktopMain/resources/`. The iOS Swift host is under `iosApp/`; its shared Compose entry point is `shared/src/iosMain/kotlin/com/metrolist/shared/MainViewController.kt`.

| Change | Start here |
| --- | --- |
| Screens and components | `androidApp/src/main/kotlin/com/metrolist/android/ui/`, `desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/ui/`, and `shared/src/commonMain/kotlin/com/metrolist/shared/ui/` |
| Screen state and loading | `shared/src/commonMain/kotlin/com/metrolist/shared/viewmodels/`; Android also has wrappers under `androidApp/.../viewmodels/` |
| YouTube requests and parsing | `shared/src/commonMain/kotlin/com/metrolist/shared/api/YouTubeRepository.kt` and the adjacent API operations; stream extraction is under `shared/.../youtube/` |
| Database entities and queries | `shared/src/commonMain/kotlin/com/metrolist/shared/db/`, starting with `MusicDatabase.kt` |
| Preferences | `shared/.../constants/PreferenceKeys.kt` and `shared/.../settings/Settings.kt` |
| Playback | Shared `ui/playback/PlaybackController.kt`, Android `playback/PlayerConnection.kt`, desktop `state/playback/`, and shared `iosMain/.../playback/` |
| Casting | `shared/src/jvmSharedMain/kotlin/com/metrolist/shared/cast/` |

For example, Android's `ui/screens/HomeScreen.kt` uses the Android `HomeViewModel`, which wraps the shared `HomeViewModel`. The shared class loads API data through `YouTubeRepository` and reads local state through `MusicDatabase`. Follow a screen's imports rather than assuming every platform uses the same wrapper. Koin registrations are in the platform `di/` directories and shared `youtube/di/SharedModule.kt`.

## Make and check a change

- Follow the surrounding implementation. For a preference, define its key in `PreferenceKeys.kt`, expose it through `Settings` and read it through `Settings.shared`.
- Add UI text to the shared catalog and follow [strings.md](strings.md). Derive content locale from settings or the system, never a hardcoded language fallback.
- Update tests for changed backend behavior. Shared tests live under `shared/src/commonTest/` and `shared/src/desktopTest/`; Android and desktop app tests are under their `src/test/` and `src/jvmTest/` directories. Do not add UI tests.
- Run the affected platform's [checks](checks.md). Format changed Kotlin with `ktlint --format` or the module's `ktlintFormat` task, then review the result.
- Use Conventional Commits and `<type>/<short-kebab-case-description>` branch names. Do not bump `appVersion` in a feature or fix; it triggers releases.

## Icons

Gradient app icon sources live in `assets/app-icons/gradient/`. Its foreground, monochrome and full SVGs center the glyph in a 52 dp box on the 108 dp adaptive canvas, matching the visual size of Android's Original glyph. Render `foreground.svg` and `full.svg` with `rsvg-convert -w 512 -h 512`, then encode them as lossless WebP for Android's `drawable-nodpi` resources. Keep the Android monochrome vector's transform in sync with `monochrome.svg`. Render `full.svg` at 1024 px and convert it to an RGB PNG for iOS's `GRADIENT.appiconset/AppIcon.png`. Gradient is an optional Android and iOS icon; desktop has no alternate-icon selector.

Android's Original icon matches the README icon (`fastlane/metadata/android/en-US/images/icon.png`). The picker, the adaptive icon and the legacy launcher icon share `ic_launcher_foreground.xml` and `app_icon_original_background.xml`; themed icons use `ic_launcher_monochrome.xml`. Below API 31 it is a white glyph on black. On API 31+ `values-v31/colors.xml` uses the wallpaper's primary container and on-primary-container colors, like the About screen logo. Desktop's `logo.*` files and iOS's `AppIcon.appiconset` use separate artwork.

Shared UI glyphs in `shared/src/commonMain/kotlin/com/metrolist/shared/ui/icons/` come from Google's [Material Symbols SVGs](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web) under the [Apache 2.0 license](https://github.com/google/material-design-icons/blob/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/LICENSE), included at `assets/material-symbols/LICENSE`. `scripts/generate_material_symbols.py` pins that revision and maps property names to SVG names. It uses weight 400, grade 0 and optical size 24. Filled uses the Outlined family with `fill1`; Rounded keeps each entry's fill. `FavoriteBorder` always stays hollow. `Movie` and `PictureInPicture` map to `smart_display` and `picture_in_picture_alt`. The script also covers auto-mirrored entries and the Explicit, Saved, Liked and Downloaded status badges, keeping their mirroring, sizes and colors.

Run `python3 scripts/generate_material_symbols.py regen`, then `verify`. To refresh upstream, pass `--revision <full-commit-sha>`, update `UPSTREAM_REVISION` and record that SHA in the commit body. Paths use the 960-unit grid and `materialSymbol` translates them down by 960. The script normalizes Google's 24-unit SVGs with fontTools. Run `python3 scripts/test_generate_material_symbols.py` and the shared desktop icon test after changing the generator.

The `taskbar` command refreshes Windows' four 75 px ICO glyphs under `desktopApp/src/jvmMain/resources/` with `rsvg-convert` and Pillow, keeping their black outlines, white interiors and PNG encoding. Windows title-bar glyphs in `WindowsWindowFrame.kt` use platform-native artwork. Tray and launcher logos, the eight `MetrolistIconsBrand` marks, iOS app-icon assets and Android notification and widget drawables are separate artwork.

## Navigation

Android's standard and retained-root navigation hosts scope Playlist, Album, Artist, Browse and About Artist ViewModels, and the built-in history playlist's HistoryViewModel, to their entries. Back keeps their stores and saved UI state for Forward. New navigation discards forward history and clears abandoned stores after outgoing content leaves composition. Root and player ViewModels use the activity scope. Forward history saves each route's source tab across recreation. Discarding a detail entry cancels loading and collectors. Playlist and history edits, artist subscriptions and automatic download preparation run in the application scope; their result callbacks stop when the ViewModel clears. Clearing a playlist entry commits its pending drag reorder. Queued downloads belong to the download manager.

Desktop uses the same navigator with a separate page host. Neither desktop nor iOS calls `toEntries`. iOS artist, album and browse loaders belong to live back-stack entries, so Back reuses loaded pages; popping an entry cancels its work, and session or content-locale changes replace retained detail state. Desktop reuses a retained album only when its ID and session match; explicit refresh reloads it.

Android requires a second completed Back within two seconds to exit from the start destination. In-app navigation and overlay dismissal take one Back. Leaving the start destination, expanding the player or pausing the activity clears a pending exit confirmation.

Appearance settings offer Home, Search, Library, Tracks, Favorites, Artists, Albums and Playlists as launch destinations on Android and desktop. Favorites opens the liked-song collection above Library in the navigation stack. Launching a library filter starts with that filter; restoration within a running session keeps the current destination.

Slim bottom navigation hides every tab label, including the selected tab, and uses a 56 dp content height above system insets. Screen readers keep each tab's name and selected state. Wide navigation rails keep their labels.

## Input and focus

Android and shared mobile click targets use `FocusOutlineProvider` and `Modifier.focusOutline` for keyboard and D-pad focus. The outline uses the theme primary color with a contrasting stroke, stays inside the target and disappears in touch input mode. Custom rows and player controls reuse the same renderer; Material controls use their own indications. Carousel focus groups keep shelf items together during directional navigation.

The shared secondary-click detector reads unconsumed pointer-down events directly, because Compose's desktop `awaitFirstDown` filters for the primary mouse button. Verify right-click menus on Library grid and list items and playlist song rows, including under XWayland.

Android media menus follow the invoking input, not the navigation layout: mouse and trackpad presses open cursor-positioned menus, touch presses open sheets.

Song and artwork carousels have no horizontal scrollbars. Only desktop Home rows show previous/next buttons. Desktop rows support Shift+wheel, horizontal touchpad scrolling, mouse dragging and keyboard navigation; unmodified vertical input scrolls the page. See [desktop scrolling](desktop.md#scrolling) for platform checks.

Android selects desktop mode for desk/DeX windows and for large windows with a mouse. Touchpad-only keyboard covers keep tablets on the mobile layout. Desktop mode uses the desktop player bar when the window's smallest width reaches Material's 600 dp medium-width breakpoint. Landscape phones keep the mobile mini-player even when Android reports a pointer device.

Android 15+ windows with a mouse or touchpad place search and the account menu in the transparent system caption bar at every width; narrow captions use a search icon. Touch-only floating windows keep the account menu in the app bar and place content below the caption. Home and Library count the caption inset once. The layout reserves the system-reported app-menu and window-control bounds; only interactive app controls exclude caption drag gestures.

Player-driven keyboard suppression in Android search uses actual expansion; transient sheet progress during keyboard resizing must not dismiss the keyboard. TV navigation focus stays blocked while the player is expanding.

### Android TV

Long-press a queue track and choose **Move**. Up/Down moves the grabbed occurrence; OK places it, and OK on another row picks that row up without leaving movement mode. Back places a grabbed track, then exits movement mode. **Multi-select** enables row selection; Right or a long press opens a side menu with playlist, remove, download and like actions. Sleep timer, shuffle and repeat are in the queue menu as well as the main player. TV uses these menus instead of the touch queue lock and floating toolbars. Verify long queues, duplicates, first and last rows and bulk actions with a remote.

The TV queue takes D-pad focus after its opening animation settles and returns focus to Play/Pause after closing.

The lyrics **Sources** search dialog opens the keyboard only when a field is activated with OK. Up/Down moves through the title, artist, online search and Search controls. The keyboard's Search action submits the same validated query as the Search button.

## Player

Android and the shared iOS host keep the mini player and full player in one persistent `BottomSheet`, using the theme's default spatial spring for expansion, collapse and drag settling. The iOS scaffold reserves the mini player's measured slot, including navigation and safe-area spacing. Expressive moves its player clearance from outer route padding into scroll content padding. Android draws its mini player over content and reports clearance through list padding. The sheet draws one opaque container from the mini player surface's measured bounds, color, border and shape, including Expressive corner smoothing. Bounds and corners interpolate with sheet progress; contents fade through using the theme's fast effects spring. Expanded content is not placed while transparent. The hidden mini player keeps its layout for measurement inside an empty clip with drawing, pointer hits, focus and accessibility disabled.

Android and iOS full players show Cast in the top bar when available and Queue and Lyrics at the bottom. Android TV hides Cast and player Share controls in every layout. Song menus on TV omit Share and divide the primary row between the remaining actions. Standard and Vintage put Sleep timer, Shuffle and Repeat in connected bottom buttons. Classic puts Repeat, Previous, Play/Pause, Next and Like in its main row, Sleep timer between Queue and Lyrics, and Shuffle in the queue. Tablet layouts and the narrow pane beside a landscape queue use the connected buttons, omitting Shuffle for Classic. All timer buttons open the same dialog and control the same session state. The iOS host disables casting.

Touch windows at least 600 dp wide and 320 dp tall use the two-pane player when wider than tall, including tablets whose height exceeds 600 dp. Artwork and bottom actions occupy the left pane; metadata, the seek bar, transport controls and overflow occupy the right. Artwork fits the space above the action row. Transport controls sit halfway between the seek timestamps and bottom actions. The phone sheet background fills the cutout area, with safe insets on content and the mini player. TV keeps its existing layout selection. Landscape and tablet layouts reserve the bottom row outside the scrolling player content. Connected buttons overflow when the row is too narrow; Classic measures its labels and fits an active countdown into the remaining space. Rotation resets layout-dependent visibility offsets, and cancelled sheet drags return to the current anchor dimensions.

The portrait player moves one animated album-cover renderer between the artwork and lyrics thumbnail inside the shared element. Static and animated artwork keep the same bounds transition. Lyrics thumbnails use `ComponentShapes.SmallThumbnailShape`, and lyrics action buttons have transparent containers. The collapsed phone queue toolbar keeps its drag and tap region without a visible handle.

The sleep timer uses a fixed-size dialog window on touch platforms. Its surface fills the available width with 24 dp margins, up to 560 dp, and follows predictive Back. Advanced options keep the theme spatial and fade animations inside the window. TV keeps its previous dialog width.

Landscape queues use the same sheet state and vertical motion as portrait, docked to the end side at up to 480 dp and half the window width. The player stays interactive in the remaining space. Narrow panes show lyrics above the controls.

Standard play/pause labels use titleLarge and shrink to fit longer translations. Full-player primary-control heights match across Standard, Classic and Vintage in each layout. Android's Vintage mini-player uses 8 dp artwork corners and a filled secondary-container Next button.

Each artist name in the shared Now Playing line opens that artist. Separators and names without a usable artist ID are not clickable. Each linked name has its own screen-reader action and focus target. On desktop, selecting an artist closes the expanded player.

The shared mobile player shows only the song/video toggle spinner during a visible video-mode transition; the play button stays disabled while playback loads. When the toggle is hidden, loading shows on the playback controls or overlay.

The shared full player, Android desktop-mode bar and desktop fullscreen-video controls hold a released seek target until both the sampled and rendered positions are closer to the target than to the pre-seek position. This accepts overshoot and keyframe snapping without accepting the old position. Timers show the same target. A new drag, track, duration or seek-permission change clears the hold; a five-second timeout releases it if the backend rejects the seek.

Android video preload promotion updates video mode before publishing the new song, including manual Next, end of file and crossfade. Queue and recovery resources keep the audio-to-video counterpart map, so switching back to Song uses the matching audio timestamp.

### iOS playback

iOS playback uses AVPlayer time observers and KVO for readiness, rate, buffering and output-volume changes. Progress samples run every 100 ms of media time while the mobile UI is resumed and every second otherwise; paused or stalled players need no polling loop. Media-time boundaries trigger preparation, silence skips, crossfade entry and corrected endings independently of that cadence. Only active gain ramps get fine updates (20 ms for crossfade, 100 ms for end-of-song sleep fades), without publishing progress to the UI. Volume and sleep-timer gain changes read the native position to keep crossfade progress between samples. Sync, seeks and persistence read the native position on demand. The `playbackObservation` cinterop protocol exposes the Objective-C KVO callback to Kotlin; observers belong to each player or item and are removed on replacement or release.

iOS screen privacy covers app content while the app is inactive and during screen recording or mirroring. It cannot prevent ordinary screenshots.

## Queue

Queue rows show the title, artist and duration without source subtitles. Source metadata still controls recommendations, auto-mix and the suggested section. Rows fill missing durations from the song database, including Cache, Downloaded and incomplete restored or transferred queues; known incoming durations take precedence.

The shared queue header and rows use a 12 dp horizontal inset, 12 dp corners and 4 dp gaps. The desktop header's top corners match the panel's 16 dp radius. When unlocked, rows without a reorder handle reserve its space so artwork and text align.

Legacy shuffle is on by default. It puts the current occurrence first and randomizes every other occurrence, including played songs and generated recommendations, matching legacy Metrolist's [`MusicService.applyShuffleOrder`](https://github.com/MetrolistGroup/Metrolist/blob/e6fea3b36c68841bad21daa6252e1d69ecd3afbe/app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt#L2876). The queue shows the reordered list directly instead of a Media3 shuffle permutation. With Legacy shuffle off, shuffling keeps the current occurrence and played history, randomizes only upcoming explicit entries and keeps generated and auto-mix entries afterward in order. Both modes preserve duplicate occurrences and active playback. Disabling shuffle restores the canonical order of unplayed entries. Changing the setting applies the next time shuffle is enabled.

Playlist and album **Shuffle** actions put a random song first and shuffle every other occurrence into the upcoming queue, including through Device Connect. With **Persistent shuffle**, selecting a song in a new queue keeps shuffle active and starts that occurrence; Android Auto follows the same policy.

Tapping a search result inside a playlist, album or stored collection queues the whole collection in its current sort order and starts at that occurrence. Search only filters visible rows; Downloaded-playlist filters still limit the collection. Album Play all, Shuffle, selection controls and album menus use the same initialization as tapping an album track. On iOS, album queues keep a radio seed so playback can continue into recommendations. Android built-in collections, including Downloaded, start finite queues; with automatic loading off, playback stays within the collection.

**Keep paused when changing songs** is off by default. When on, local skips and selections keep an existing pause; a first song still plays. End-of-track transitions, Cast and remote commands are unaffected.

Start radio for the current song keeps its audio, position and play state while replacing the queue. A different seed starts from the beginning. On Android, Start radio turns repeat off so recommendations can populate the queue.

Desktop manual Next can promote an already prepared successor with the chosen play/pause intent. Active crossfades and podcasts use ordinary advancement so cancellation and resume positions apply.

Android and desktop **Add to queue** and **Play next** respect **Prevent duplicate tracks in queue** when starting an empty or dismissed queue: the first occurrence of each track is kept in batch order. The first track is prepared paused.

Dragging a selected queue or playlist row moves all selected occurrences together in order. Playlist reorder mode keeps the selection. Playlist database and YouTube edits commit the final order at drag end; queue edits keep the current playing occurrence. On TV, menu moves scroll to the moved occurrence; other platforms keep the viewport fixed.

**Pause when media is muted** (Android and iOS) responds only to an unmuted-to-muted media-volume transition during playback. Starting or resuming at zero volume keeps playing. Unmuting resumes playback this setting paused; disabling the setting cancels that pending resume. Desktop does not implement it.

## Android Auto and system controls

Android Auto lists **Shuffle** as the first row of playlists, albums, Liked songs and Downloads. It starts at a random song with shuffle on; song IDs keep their collection positions.

**Add to queue** appears on songs in Home, History, Library and playlists when the host supports browse actions. It uses the queue handler, filters unavailable offline content and respects Listen Together guest locks. Browse actions require a positive `ControllerInfo.maxCommandsForMediaItems`, which a legacy host supplies through `BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT`. Media3 publishes the action definitions on the root and supported action IDs on song rows; the app does not gate them by Auto version. Each root request logs `browsableOnly` and `maxCommandsForMediaItems` to `PlaybackEventLog`. Play next is not advertised. See [custom browse actions](https://developer.android.com/training/cars/media/create-media-browser/custom-browse-actions) and [Media3 item commands](https://developer.android.com/media/media3/session/serve-content#command-buttons-for-media-items).

The **Quick-add playlist** setting selects an editable saved playlist for the now-playing **Add to playlist** button, which uses the shared playlist mutation path with YouTube sync and duplicate checks. An unset or deleted destination prompts for configuration. Android Auto settings are available on phones too.

Auto playback requests keep their resolved metadata until the session applies them, even if the metadata cache evicts it. Indexed queue selections cancel pending Auto requests so a late result cannot override them. Queue selection accepts occurrence-specific media IDs. Transport-only controllers can select an existing queue entry but cannot replace the queue or browse.

The YouTube playlists folder reads saved remote playlists without a network request while offline. If an online listing fails before returning anything, it falls back to stored playlists; a successful empty listing stays empty.

Android voice playlist requests skip unavailable or blocked songs and page until a playable occurrence is found or the cursor ends, keeping duplicates and the unfiltered cursor.

System media controls place Like beside Previous and Repeat beside Next. Repeat cycles off, all and one. Shuffle follows Repeat in overflow and Android Auto actions.

Verify Android Auto changes on a head unit or the Desktop Head Unit.

## Account

Android YouTube sign-in keeps trusted HTTPS redirects and popup handoffs inside its login WebView. Untrusted hosts and external schemes such as `intent://` and `market://` are blocked. Verify sign-in with Chrome and YouTube Music installed.

Switch Channel waits for an explicit choice before saving credentials. Multiple discovered channels use the app's channel picker. With zero or one channel, or after a discovery failure, the login browser stays open: choose the channel in YouTube Music, then press **Complete sign-in**. Canceling leaves the stored session unchanged.

The shared account popup opens Switch Channel from the profile picture. The account chip holds the logout button and its confirmation. A token-login row sits below the chip. Android, desktop and iOS share one plain-text token editor; the Show token switch starts off each time and hides the editor. Save changes parses the text with the token parser and verifies the session before replacing stored credentials. The sensitive-data warning can be dismissed permanently.

Android authentication and session secrets use an Android Keystore key. If the key is permanently invalidated, secure settings replace it and treat undecryptable values as absent. Android Discord credentials also use encrypted settings; startup, backup and restore migrate the old `discord_token` preferences, restored credentials take precedence and logout removes legacy credentials.

Debug and release builds use the same TLS-only Android network security configuration.

## Home, search and history

When no Home chip is selected, the main feed and its Moods and genres fallback load first. The end-of-list trigger then appends a random unused chip page and its continuations. Only one page request runs at a time. Shelves with the same title or browse ID stay when their items differ; only identical canonical item sets are skipped. Appended shelves keep their chip identity, More parameters and cursors. Failures keep the chip and cursor for Retry. Selecting a chip or refreshing clears appended feeds. Desktop filters appended shelves to exclude library and local sections and library browse links.

Speed Dial tiles resolve song titles through `SongMetadataState`, so metadata edits show without a refresh. Forgotten favorites uses the standard single-row artwork cards; Quick picks uses its compact song grid.

Artist and podcast menus offer Hide from Home. It removes them from Home shelves and Speed Dial suggestions, along with tracks and albums whose primary artist is hidden and episodes of hidden podcasts. Explicit Speed Dial pins stay visible. Search, library and account state are unaffected. Privacy settings list hidden items, and settings backups include them.

Android Home's top app bar links to History, which is also in the account menu.

History shows the latest eligible play per song. Select all uses displayed rows across pages. Deletion removes only those plays, so an older play of the same song can appear afterward. The search field stays visible while a query is active, including empty, loading and failed results; the no-history state applies only to an empty history without a query.

Search song rows, including the Top result song, use the swipe actions: left adds to queue, right plays next. They require the song swipe setting and are disabled for locked Listen Together guests on Android and desktop. The Top result Save action opens the playlist picker. Search album rows keep their browse destination even when the thumbnail has a playback endpoint.

Deleting a synced search-history entry refreshes the list. The session ignores stale copies of deleted queries until the server omits them or the query is recorded again. Remote deletion waits for YouTube confirmation and keeps the entry if it fails. Privacy settings can hide **From recent searches** without deleting entries.

## Library, playlists and artists

Library filter chips are Tracks, Playlists, Artists, Albums and Podcasts. A saved Owned selection opens Playlists. Episodes, Channels and Downloaded are podcast subfilters. Tracks lists saved local and synced songs, excluding podcast episodes and collection shortcuts. Playlist subtitles show the known song count before the author and omit unknown counts.

Library observes database changes only while Library or a history playlist is visible and the host lifecycle is started. Returning to Library reloads saved items and aggregates. Opening or playing a history playlist refreshes its period first.

Library measures its list/grid switcher before the flexible sort and search controls, so long sort labels ellipsize. Desktop Display density uses the same saved column count as Appearance and Android pinch resizing. Downloaded collections place queue status and the playlist filter above the sort/search row. Playlist, album and Library search keep separate scroll states, so clearing a query restores the unfiltered position.

Library Custom sort uses a drag handle over grid artwork or at the end of list rows. Holding elsewhere opens the context menu. Tapping the handle opens movement commands for keyboard, TV and screen readers. The saved order preserves hidden items and other categories; search results and automatic shortcuts cannot be reordered.

In playlist reorder mode, dragging the handle moves a song immediately; holding the row also starts a drag. Tapping the handle opens movement commands. Keep playlist scrolling enabled during a reorder gesture: on desktop, disabling it removes the mouse-scroll pointer node and cancels the child drag.

Playlist sort and direction are shared across ordinary local and remote playlists (authored order, ascending by default). Liked songs (newest first), downloaded and cached collections have their own saved sorting. Re-liking moves a song to the top of Date Added. Remote refreshes update like recency while preserving likes made during the refresh. Local Date Added uses the membership row ID as its insertion sequence; position updates must preserve it.

Android playlist removals publish the remaining occurrences before refreshing remote contents. Queued removals revalidate membership under the mutation lock so a deleted row cannot resend its old item ID.

Android and iOS share the add-to-playlist sheet. **Add** submits the song batch to each selected destination; completed ones stay marked and failed ones keep their progress for retry. Duplicate prompts apply per occurrence and destination; **Skip** continues without it. The chooser shades playlists that already contain all selected songs, checking stored membership first and remote membership when the cache is incomplete. Lookup failures leave a playlist selectable.

All add-to-playlist pickers share a sort preference independent of library sorting: name, date added, last updated or song count, either direction. Missing metadata sorts last.

Playlist membership lookups and complete playlist loads share an account-bound five-minute cache. Acknowledged adds update it without extending expiry; removals, copies and failed edits invalidate it. Offline or incomplete results cannot establish that a song is absent. Local additions commit at most 100 songs at a time and report each committed prefix. Appends use the highest stored position, so gaps are valid.

Song editor Save and Reset keep the editor open until persistence succeeds and disable dismissal while saving. Reset clears title and artist overrides, restores stored original metadata offline and preserves artist links.

Artist Top tracks shows the first five songs of the songs shelf; the header arrow opens the full list. The library shelf uses a Material 3 gallery carousel. Videos use a carousel of masked 16:9 cards, shared by Home, browse and search shelves when every item is a video. Classification uses item metadata plus the artist page's section hints. Artist carousels apply each artist outline to the changing carousel mask rather than intersecting it with a fixed outline.

The artist header shows the subscriber count under the name, Subscribe on the left and connected Radio, Shuffle and Play all on the right. The row budgets whole pixels in the space left by the menu button and shrinks gaps and widths for narrow windows. Subscribe updates immediately, ignores taps during the request and rolls back with an error on failure. A server-confirmed subscription triggers a one-second confetti burst, skipped when the motion duration scale is zero. Artist browse requests that overlap a subscription change cannot replace its state.

About Artist shares `AboutArtistContent`, `AboutArtistViewModel` and `LastFmArtistCache`. Android and desktop supply `ArtistPhotoActions` for the full-screen viewer; iOS is gallery-only. See [rendering.md](rendering.md) for the gallery.

## Statistics

Statistics shares one overview and ranked detail layout. Periods are Continuous windows of 7, 30, 90, 180 or 365 local days, Calendar periods from the current week to year, All, or a custom inclusive range. Calendar weeks use the system locale's first weekday. Queries use local boundaries across daylight-saving changes. Summaries label the selected period, and the exported PNG uses the same wording and totals. Desktop saves the image through a file chooser; Android and iOS use the share sheet. Export is unavailable while loading, after a failure or for an empty period.

## Settings

To unlock Devtools, open Settings, About and tap the logo three times. Devtools then appears in Settings and the Android account menu.

The shared playback log keeps the latest 500 events in a ring buffer. Recording assigns an ID and timestamp without copying the log. Export reads a snapshot; the visible log refreshes at most four times per second. Clearing keeps event IDs increasing.

Android, desktop and iOS use the shared settings registry. The settings shell owns navigation for registered pages, including platform-rendered ones, which reuse its title and Back action. At 840 dp and wider, category selection and search replace the detail pane, and nested pages show Back inside it. Search and reopening restore a page's registered parent path. Hosts mark external actions explicitly. Only a visible collapsing toolbar attaches its own nested-scroll connection. Custom pages register localized action labels in `SettingsPageRegistration.searchTerms` so search can find them.

The theme colour picker appears only while Material You is off. With Material You on, artwork colours apply when enabled, then wallpaper colours on Android 12+, otherwise the default seed. Sliders placed directly in a card have a button that restores the default.

## Lyrics

### Lookup

Automatic lookup uses the first usable source in the configured provider order, even when a later source has richer timing. With Prefer word-synced lyrics (off by default), lookup continues until it finds word timing, keeping provider order within each timing type and falling back to line-synced or plain lyrics. User edits and explicit source choices always take priority. Cached automatic results are used offline only while their provider is enabled and cannot outrank an earlier provider. Changing the source order reloads the current song's lyrics.

Automatic searches wait for Lyprio within its 50-second timeout before trying lower providers. If a completed Lyprio search returns nothing, it retries once with the picker's full provider set within the same budget. Errors and cancellation are not cached as misses. The final selection is cached after the search completes, including refetches. Video-specific sources used for retiming also follow provider priority.

Lines that share one start and have no word timing count as unsynced. Lookup keeps them while checking for a synced fallback. Lyrics without line starts do not gain timing from a TTML body-duration end.

Room stores confirmed misses with their lookup identity for seven days. Metadata, enabled providers, provider order, video context and explicit refresh trigger a new lookup. Provider failures are never saved as misses.

Shared lyrics HTTP reads retry a 503 twice with 500 ms and 1 s backoff, honoring Retry-After within a five-second budget.

Lyprio fetches remove a leading title-and-artist header only when the whole line matches the requested metadata, separated by a spaced hyphen, en dash or em dash in either order, and another nonblank line follows. Matching ignores case and repeated whitespace.

Shared MLX parsing rejects Han text in Lyprio/KUGOU romanization fields, because older KRC responses labeled Chinese translations as romanization. This applies to cached lyrics too; missing romanization is then generated locally.

LyricsPlus and Zemer are not lookup providers. Stored provider settings drop them, and saved lyrics from either remain readable.

Each candidate includes cached translations and dictionary-free romanization before display. When Japanese dictionary preparation is needed and nothing is displayed, plain lyrics publish first. Enrichment replaces only the displayed entries and keeps source timestamps. Cancelled requests and late enrichment cannot overwrite newer results. Songwriter lookups run independently.

The shared player keeps empty results loading until the request releases its deduplication key. Refetch bypasses the saved result; empty or failed refreshes keep the displayed lyrics. Player captions request lyrics when enabled, but hidden mobile players do not. Next-song prefetch requires full lyrics or subtitles.

### Timing

A persistent global offset adds to each song's saved adjustment everywhere. Positive values advance lyrics. Highlighting, scrolling, captions and tap seeking use the combined offset.

Activation uses scroll lookahead and per-line overlap handling unless "Highlight lyrics by their timestamps" is on, which activates each row only between its start and end. Lyprio JSON lines without an end use the next line start, or the song duration for the last line. Legacy LRC offsets shift timestamps with the same sign as Lyprio's converter. Enhanced LRC uses the next line for a missing final-word end. Interpolation uses monotonic time and resets on backward seeks. No platform adds a fixed output-latency offset: Android reads Media3's position, anchored to AudioTrack timestamps for PCM output, and desktop reads played frames. Verify sync on speakers and Bluetooth, at changed speeds and after seeks.

### Manual search and editing

Manual search opens a title and artist dialog, then a provider picker. All providers search in parallel, including disabled ones and the fixed User and YouTube sources; completed groups with results appear in settings order. Dismissing, selecting or changing tracks cancels pending requests. Custom queries omit the track's video ID, album and duration.

The shared full-screen editor opens the active lyrics as MLX v2 JSON behind a timestamped text field: `[mm:ss.cc] text`, or three fractional digits for millisecond sources. Word-synced lines use inline tags such as `[00:08.20] <00:08.20>word <00:08.51>tags`. `\n`, `\r`, `\\`, `\<` and `\>` are escapes. Timing buttons shift selected lines by half a second.

Unchanged JSON lines keep every field, including provider extensions. Editing a line timestamp shifts its word and syllable intervals together. Editing a word tag changes that word's start and keeps its end when still valid. New words end at the next word or the line end, otherwise after half a second. Removing all tags removes foreground word timing and timed romanization. Text edits clear stale foreground translations and romanization; background vocals stay intact. The editor uses source timing even when the player retimed lyrics for a video.

The first change creates a User fork in the song lyrics row, separate from the provider and translation caches. User lyrics win over refetches and provider selection. Autosave waits 500 ms and flushes on close or backgrounding. Invalid timestamps are reported by line number and saved as a draft beside the recoverable JSON. Revert deletes the fork and draft.

The lyrics settings preview uses the current song's lyrics only during active playback and otherwise shows the bundled sample without starting a lookup. Source rows reorder by a drag handle or its move menu.

### AI translation

Automatic translation checks each main and background vocal for a meaningful passage outside the target language, including phrases inside mixed-language lines. The detector returns a source passage, not a dominant-language flag; invalid passages fail detection without caching a negative decision. Only vocals with a foreign passage are translated, and results appear beneath them. Detection batches up to 40 unique vocals. The song-level detector for romanization and connection checks reads lyrics in batches of at most 8,000 characters and stops at the first foreign passage; a negative decision needs every batch to succeed. The translation cache covers both vocal sources and separates forced from automatic requests.

Detection and translation retry once after a failure, except for cancellation, missing credentials, HTTP 401/403 and 429. A final failure keeps the original lyrics and shows a short snackbar naming the cause. HTTP 200 alone does not prove a usable translation. Playback exports record the stage, provider, model, failure category, output lengths, finish reason and reasoning presence, but never lyrics, response bodies or credentials. Gemini thought parts are excluded. Manual translation enriches displayed lyrics without refetching and keeps the source, offset and video retiming.

OpenRouter setup is a three-step page under Lyrics settings. It uses PKCE browser sign-in, stores the returned account key separately from manual keys and derives the target language from the saved choice, app language or system locale. Done sends one short sample through the chosen free model with a 128-token budget and enables translation only after a valid response; a failure keeps the existing configuration.

Inference uses `https://openrouter.ai/api/v1/chat/completions` with bearer authorization, `HTTP-Referer` and `X-Title`. The default model is `google/gemma-4-26b-a4b-it:free`, with `google/gemma-4-31b-it:free` and other filtered free models as alternatives. Model pickers list free models first, then sort by combined input and output price, and hide non-text, preview, expired and alias models and those above $1 input or $5 output per million tokens. Only a successful provider list can replace a missing saved model.

OpenRouter free translations combine detection and translation in one batch. Presets disable optional reasoning to preserve the output budget. On HTTP 400 or 422, the client tries JSON Schema, JSON object mode, plain output, then plain output without reasoning. Parsing accepts JSON wrapped in fences or prose and still checks line types and alignment.

A 429 never retries. Explicit upstream throttling moves to the next free model and remembers busy models per account for the server's retry interval, or one minute. Account daily limits stop automatic inference until the reset header or the next UTC day; a setup retry first checks `GET /api/v1/key` and resumes only if quota remains. Minute limits and ambiguous 429s stop the current operation. No fallback uses a paid model.

See OpenRouter's [model catalog](https://openrouter.ai/api/v1/models), [limits](https://openrouter.ai/docs/api_reference/limits), [reasoning tokens](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens), [structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs), [provider selection](https://openrouter.ai/docs/guides/routing/provider-selection) and [errors](https://openrouter.ai/docs/api_reference/errors-and-debugging). Account privacy policies can filter out every provider and cause a 404; setup links to the privacy settings for that case.

iOS lyrics support long-press selection and the shared image editor. Images are shared as temporary PNG files that are removed when sharing finishes.

## Persistence

The database uses [Room 3](https://developer.android.com/jetpack/androidx/releases/room3) with the Android system SQLite driver, the desktop JDBC driver and the iOS bundled driver. All platforms share the migration chain. Schema 62 and the historical Room 2 exports keep their identities, and migration tests build fixtures from those exports on every platform. Do not replace old exports. Keep migrations compatible with existing installs. Schema 59 uses numeric boolean defaults for Android SQLite before 3.23.

Pending scrobbles are rows in `pending_scrobble`. The first queue operation migrates valid entries from `pendingScrobblesJson` and clears it only after the insert succeeds; malformed entries move to `corruptPendingScrobblesJson`. Acknowledging a submission deletes only its row.

Preserve duplicate queue occurrences and revision-bound checkpoints. Commit the full queue before its position checkpoint so interrupted writes cannot combine different queues. Desktop checkpoints map video positions back to the queued audio timeline. Queue Undo belongs to the queue session that produced it; replacement queues and persisted restores invalidate it.

Resume checkpoints run every 30 seconds during playback and immediately on pause, seek, queue or track change, completion and backgrounding or exit. An abrupt kill can lose one interval. Disabled queue persistence has no timer. Android and desktop drain final writes on shutdown; iOS drains them at background and termination checkpoints. Pending desktop track changes publish the new position before the song, so the closing track cannot overwrite a podcast checkpoint. During Cast playback, checkpoints use receiver progress while the receiver owns the current occurrence.

Podcast playback reads the local episode checkpoint before starting audio, falling back to YouTube's resume point or progress percentage. Library sync imports account progress only when no local checkpoint exists. Episodes restart when fewer than 30 seconds remain. Episodes with saved progress skip preload promotion so seeking finishes before audio starts.

Backup restore must roll back to the exact prior settings, and exports must reject destinations pointing at the live database or its SQLite sidecars. Restores stage `song.db-wal` beside the backup database and checkpoint it before validation. The database and each sidecar are limited to 512 MiB, with 600 MiB across archive entries. iOS journals the prior settings and database before restoring and recovers an interrupted restore before starting app services. Exports suggest `metrolist-backup-YYYYMMDD-HHMMSS.zip` in local time.

Android backup snapshots use Room's writer connection: `VACUUM INTO` on API 30+, otherwise a copy of the main file and WAL under an exclusive transaction, checkpointed into the staged copy. After a failed Android legacy migration, onboarding can create an empty library while keeping the original database under the private `files/legacy-library-recovery/` directory.

## Integrations

### Casting

Android and desktop share the JVM cast sessions. Connecting and replacing media preserve receiver volume for Google Cast, DLNA/Sonos and FCast; only explicit adjustments send volume commands. Receiver reports supply remote volume state.

### Discord

Discord initializes only once the integration is enabled or its settings open; Android OAuth callbacks also initialize it. Clearing presence starts a 30-second idle grace period, after which the gateway closes but credentials stay. Playback reconnects. Known offline state suspends retries until the network monitor reports connectivity. Handshakes time out after 15 seconds and retry with capped backoff.

### SponsorBlock

Android and desktop offer an opt-in **SponsorBlock (podcasts)** switch. Only items typed as podcast episodes request segments. It skips `sponsor`, `selfpromo` and `interaction` segments, but not during casting or remote guest playback. iOS has no integration. The observer uses the [hash-prefix API](https://wiki.sponsor.ajay.app/w/API_Docs#GET_/api/skipSegments/:sha256HashPrefix), sending four SHA-256 characters and matching the full ID locally. Results are cached in memory for an hour, or five minutes for empty results and failures. Invalid ranges are dropped, overlaps merged and ranges past the duration ignored. Uses [SponsorBlock data](https://sponsor.ajay.app/) under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/).

### Sentry

Every platform passes reports and breadcrumbs through `redactDiagnosticText` and `redactDiagnosticUrl` in `beforeSend` and `beforeBreadcrumb`, removing credentials, tokens, cookies, query strings, long opaque path segments and IP addresses. Playback reports attach the last 200 playback events. Android and desktop report playback failures only after recovery is exhausted or a track cannot start. iOS releases are named `com.metrolist.ios@<appVersion>` because sideloaded builds get random bundle IDs, and release workflows upload dSYMs. iOS reports HTTP 5xx automatically only for HTTPS on port 443 to `metrolist.cc`, its subdomains and `metroserverx.meowery.eu`; see [HTTP client errors](https://docs.sentry.io/platforms/apple/configuration/http-client-errors/).

### Imports and scrobbling

Android account imports from Deezer, Tidal and Amazon Music handle one playlist or liked-songs collection at a time. Back from destination selection returns to the previous step, keeping CSV rows and mappings. Batches over `PlaylistImportManager.MAX_PLAYLIST_TRACKS` fail instead of truncating; Spotify reports skipped playlists. The desktop Spotify import can target Favorites, a new synced playlist or an editable synced playlist. Playlist file imports keep explicitly supplied titles as local custom metadata; bare URLs and filenames do not become titles, and local edits win on reimport.

Last.fm and ListenBrainz login dialogs link to account registration; ListenBrainz also links to its token page.

## Android music widgets

The music player defaults to 4x2 cells with a 250x110 dp fallback and a 2:1 preview. Shorter placements keep metadata beside the four controls when possible and drop the cover first; smaller ones use the controls-only and square variants. The minimum resize width of 240 dp keeps 48 dp touch targets and the action label. Layout selection uses available dp and font scale, with size-specific RemoteViews on Android 12+ and portrait/landscape layouts before that. Keep existing widget IDs and actions valid.

Turntable requests a fixed 2x2 placement with 110 dp minimums and draws artwork and controls in a centered square. Recognizer requests 4x1 cells with a 250x40 dp fallback; its card wraps its content so taller hosts do not stretch it, and narrow hosts get the mic-only variant.

Music layouts order Previous, Play/Pause, Next and Like. Play/Pause shows a localized label and follows requested playback during buffering. Colors come from a Material 3 Tonal Spot scheme of the cached album art, else the system Material You scheme: primary for Play/Pause, secondary for Previous, Next and unselected Like, tertiary for selected Like. Android 12+ publications carry light and dark colors. Before Android 12, missing artwork uses the day/night XML colors, and tintable ImageViews keep background shapes without the Android 12 tint APIs. Turntable, playlist and recognizer colors come from `androidApp/src/main/res/values*/widget_colors.xml`.

`MusicService` runs one 10-second progress sampler while a track with a known duration plays, the screen is on and unlocked, and a music or expanded playlist widget has a progress layout. Screen return, seeks and resizes refresh immediately. Progress requires a full publication for the current song first. Music widgets republish full cached RemoteViews because Android does not merge child actions in size wrappers; playlist widgets use partial updates. Neither reloads artwork for progress.

## Troubleshooting

- For missing desktop resources, check the source directory and filename case.
- For protobuf errors, initialize `metroproto` and run `./gradlew :shared:desktopTest` to regenerate and test the codecs.
- If the configuration cache is stale, rerun the failing command with `--no-configuration-cache`.
- Never commit heap dumps, traces, account tokens or local configuration. They can contain private data.

# Rendering

## Artwork

Shared collection covers resolve through `LibraryCovers.kt`. Keep user-selected covers ahead of built-in resources. Android widgets and Android Auto use the same artwork provider for offline covers. Retained download covers use a stable identity that ignores cache-busting revisions, query strings, display sizes and video renditions, so a refetched URL keeps resolving to the PNG already on disk. Legacy keys remain readable and are included when pruning.

Player and collection artwork use square, centered crops. Full-player video thumbnails use the same preview source as song rows, including the thumbnail without encoded letterboxing. Dedicated video shelves, episode previews and video playback keep their wide layouts. On iOS, managed playlist-cover identifiers resolve against the current Application Support directory so container relocation does not break covers. Delete only managed files after the database change succeeds.

Apple Music animated covers match versioned tracks against their full catalog titles before using the album artwork. Track and album qualifiers treat brackets and parentheses equally. Consecutive album notes beginning with `+` in trailing parentheses or brackets are ignored before matching names and detecting explicit or clean editions. Single and EP suffixes may differ across catalogs, while release labels such as Taylor's Version and Deluxe remain significant. A known album must match; only tracks without an album can fall back to a self-titled release. The updated matcher discards stored lookup results from the previous rules. Android also bypasses downloaded Apple Music covers stored before this matcher change.

Spotify Canvas tries tracks from the requested album first, then other releases that pass the same title, artist and duration checks. This lets an EP or album track use a Canvas available on its single. Each Spotify track ID is queried at most once per lookup.

Artwork lookups reject title, artist or album fields longer than 4,096 characters before normalization. Album suffix parsing is iterative and does not use recursive regular-expression matching.

The portrait full-screen artwork continuation starts before a wide thumbnail's bottom edge. Its preview and detailed blur keep their fades, with no uncovered band between the image and continuation.

Android mini-player video crops to its artwork slot. Expanded-player video fits its aspect ratio within the available space, with the player background outside the frame and controls inside it. The loading artwork keeps its crop through the first-frame fade. Fullscreen video uses fit, including letterboxing when the screen and video have different aspect ratios. PiP has its own scaling.

Android full-player artwork uses software bitmaps so Compose and image decoding can share pixels. Export code copies hardware images when a software canvas requires it.

Official YouTube artwork hosts keep system DNS results, including VPN mappings; arbitrary artwork hosts still require public addresses. HTTPS certificate checks and response-size limits remain in place.

Notification requests decode at 512 px but fetch the canonical source when they use its disk-cache key, so full-player requests do not reuse a smaller encoded cover. Online requests refresh undersized images left under larger cache keys by earlier notification loads; offline requests and failed online refreshes retain their cached fallback. Refreshes replace the saved disk bytes only after the new response decodes, so an invalid HTTP 200 response cannot destroy a valid offline cover. Successful image decodes record their source in memory and disk metadata. A source that remains small then reuses its verified cache across loads and restarts instead of refreshing again.

Dimension-based recovery requires positive image dimensions. Prefetch requests keep cached bytes without attempting that recovery. Cache lookups preserve explicit precision and bitmap limits, including iOS now-playing artwork at 600 px.

Only one refresh runs per destination key. Waiting requests check the repaired cache before fetching again.

When skipping in the expanded player, keep the displayed track and decoded cover until the next cover or mini-player preview is ready. First open shows metadata immediately. Failed or missing covers advance to the new track without keeping old metadata. Keep the background fades and crossfade the title and artist together. Android's pause control follows playback intent between tracks, including a pause requested during loading.

## Android artwork blur

`supportsNativeBlur()` reports RenderEffect support from API 31. Compose blur modifiers do not blur on API 30 or lower, including Android 10, API 29. Artwork backgrounds use the expanded player's `BlurredArtworkBackground` fallback there. API 31 and 32 retain native blur; API 33 and newer retain the existing artwork shader and progressive blur shaders.

`LegacyArtworkBlur.kt` copies and downsizes artwork to at most 128 pixels on its longest side, then calls the existing `blurArtworkPixels` on `Dispatchers.Default`. A four-entry cache shares results by source bitmap, generation and radius. Concurrent requests share the same calculation. Radius changes use the existing crop-based radius buckets. The reduced Coil request has a separate cache key and requests software pixels on old Android. Neither resizing nor pixel blur runs in a draw callback. The cache holds up to four source bitmaps as well as their small blurred copies.

The following audit covers API 29 and 30. A missing or failed artwork request still leaves the container or loading state; it does not supply an image to blur.

| Drawing | Before this fix | Current behavior |
| --- | --- | --- |
| Android standard and modern miniplayer, including its wide layout | Sharp artwork under the existing scrim | Expanded-player software blur with the same scrim and a 200 ms artwork fade |
| Shared standard and modern miniplayer | Sharp artwork under the existing scrim | Same software fallback and fade |
| Expressive miniplayer | Solid Material container and sharp artwork slot by design | Unchanged |
| Expanded player and lyrics artwork backgrounds | Software-blurred artwork, with a stronger scrim while processing | Same blur, now cached across background instances |
| Fullscreen player backdrop | Sharp artwork at 35 percent opacity | Software-blurred artwork at the same opacity, with an artwork fade |
| Fullscreen thumbnail backdrop and bottom extension | Software blur and software-generated progressive artwork bleed | Unchanged rendering and motion |
| Artist page and photo viewer backdrops | Software-blurred artwork | Same blur, now cached |
| Search top-result card | Sharp artwork at 38 percent opacity under its surface tint | Software-blurred artwork with the same opacity and tint, with an artwork fade |
| Liquid background | Animated layers with a downsampled software stack blur | Unchanged, including its existing per-frame renderer |
| Lyrics-share preview and exported image | Precomputed software-blurred artwork | Unchanged |
| Queue bottom and lyrics text edge modifiers | Sharp live content because the blur modifier has no effect | Unchanged; these are live-content effects, not artwork backgrounds |
| Wide player artwork edge modifier | Sharp artwork with the existing edge fades | Unchanged; the fullscreen artwork bleed has its own software blur |
| Seek feedback | White arrow without the optional blurred shadow icon; text keeps its shadow | Unchanged; the unsupported icon blur is explicitly gated |
| Lyrics text shadows and glow masks | Text rasterizer shadows and cached glyph masks | Unchanged; these do not require RenderEffect |

The background-style settings still select solid, gradient or blurred artwork. Expressive style still uses its own Material container. Lyrics progressive blur retains its existing setting and API 31+ behavior.

History checks found the current fallback and radius buckets in the v14 import, `6f7351ca5`. Earlier Android blur fixes include `e9118160f` for rendering cost, `cf18e4b0c` for removing extra previous-background layers, `e3909bded` for smooth background transitions and `a197ca57c` for miniplayer foreground colors. This change preserves the full player's artwork handoff, scrims, foreground colors, swipe springs, press scaling and sheet morphing. This fix adds a fade to the legacy replacements for Coil's artwork fade and leaves the full player's existing track crossfade in place.

Verify on API 29 and Android 12 or newer. Check standard and modern miniplayers in blurred, gradient and solid modes; Expressive must keep its solid container. Skip tracks rapidly, revisit tracks, expand and collapse the player, drag and reverse the sheet, pause and resume, rotate, and return from the background. Check the lyrics background, fullscreen artwork and video backdrop, artist photos and search top-result cards. Use square, portrait, landscape, transparent, missing and failed artwork in light and dark themes. Watch for sharp flashes, stale covers, blank backgrounds, edge seams and lost fades. Confirm settings persist, reduced motion still applies, and low-end API 29 devices remain responsive during skips and sheet motion. On API 31 and 32 compare native blur; on API 33 or newer also compare shader backgrounds, liquid motion, queue edges and lyrics progressive-blur toggles.

## Desktop artwork blur

`ArtworkBlur.desktop.kt` keeps one viewport-sized raster snapshot per drawing node for expanded-player, lyrics-drawer and mini-player artwork blurs. A 1080 by 720 RGBA snapshot uses about 3 MB; a 4K snapshot about 32 MiB. Artwork, painter animation, crossfade, size, density and theme changes redraw the snapshot. Other redraws reuse it. Replacing the snapshot or detaching the node releases the previous image. The first blur and each invalidation still pay the full rasterization cost.

The cache applies Compose's `BlurEffect` filter, including its radius-to-sigma conversion, and draws the scrim into the snapshot. It keeps the original crop, scale, saturation, source background and clip. Apply the modifier directly to artwork drawing; child graphics layers can update independently of their raster parent. The shared mini-player uses this cache on desktop, native blur on Android API 31+ and iOS, and the artwork bitmap fallback on older Android. Shared shader backgrounds use their own preblurred texture cache.

On GPU backends the raster path can differ from the GPU filter by one channel step from rounding.

Wavy, Squiggly and Now Playing bars advance their phase from monotonic time at 30 updates per second. A phase write schedules its own rendering; waiting on the frame clock first schedules another render of the previous phase. The animated gradient background steps on the same clock's 50 ms grid, like Liquid, so its repaints can share frames with other ticks on that clock. Lifecycle and window visibility suspend hidden work. Visible unfocused windows keep animating.

## Desktop software rendering

Skiko's `SOFTWARE_*` backends rasterise the whole window on the CPU for every repaint, including each seek bar step. `softwareRendering` records the backend the main window actually got. When it is set, the Liquid background uses the 128 px blur cache in place of the artwork shader, which costs about 95 ms per 1080 by 720 frame on one core, and static artwork backdrops draw the shader once into a raster snapshot until artwork, size or style change. GPU backends keep the per-frame shader.

## Icons and attribution

Shared glyphs come from Google's [Material Symbols](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web) under [Apache 2.0](../assets/material-symbols/LICENSE). `scripts/generate_material_symbols.py` pins the source revision and glyph mapping. Keep weight 400, grade 0, optical size 24, fill and mirroring consistent with that mapping.

Run `python3 scripts/generate_material_symbols.py regen`, then `verify`. For an upstream refresh, pass `--revision <full-sha>`, update `UPSTREAM_REVISION` and record the SHA in the commit body. `taskbar` regenerates Windows ICOs with `rsvg-convert` and Pillow. Run `python3 scripts/test_generate_material_symbols.py` after generator changes.

Gradient artwork lives in `assets/app-icons/gradient/`; the alternate icons are by Kayé. Launcher, brand and tray art are separate from Material Symbols. Preserve their source artwork and notices. Last.fm artist biographies keep their attribution link to Last.fm.

## Motion and visibility

Use the theme's MotionScheme and respect system animation duration scale. Keep enter and exit transitions mounted until they finish. Interrupted transitions must resume from their current state.

Shared dialogs and button popups use the theme's fast effects spec for opacity and fast spatial spec for a 0.95-to-1 scale. They do not register shared bounds or hide the opening control. Animation values are read in `graphicsLayer`, without frame-by-frame composition or measurement. Page transitions and bottom-sheet slides use their own motion. Android's devices sheet reserves its expanded height before discovery, so it slides to that height without a second resize.

Popup input guards belong to each popup session. Completion and cancellation release them in `finally`; disposal clears the session. A superseded animation cannot release a newer request's guard. The closing popup is removed after its animation, while sheets keep their own dismissal lifetime. Sheets dispose their modal window once they move out of view, even while PopupContent retains the parent through its exit animation. Dismissal observation stays active when a new presentation reuses that retained sheet state. Programmatic hide calls keep the parent menu alive until Details or Equalizer takes over. A held drag can still reverse at the hidden anchor.

The portrait player's canvas controls fade and expand or shrink. Its queue toolbar fades and slides; title and seek rows follow canvas and fullscreen-lyrics state. Queue-toolbar alpha uses drawing modulation so Screen-blended controls can sample the artwork.

Player expansion clips both content layers to the moving container. Check reversing expansion and collapse, short drags and navigation insets. DEFAULT seek sliders must follow the [agent rule](../AGENTS.md#implementation-rules).

Animated artwork and lyrics run only while their content is visible. Hidden players must release frame work while keeping playback. Check resumption at the current position after backgrounding, minimizing or switching workspaces. Hyprland window handling is in [desktop rendering](desktop.md#hyprland-rendering).

Desktop seek bars update their progress value once per pixel of travel, based on duration, measured width and playback speed, within 16 to 250 ms. Time labels read the same clock as the seek bar and update when the displayed second changes, so a label change shares a repaint with a progress step. WAVY and SQUIGGLY tracks keep their playing animation; lyrics have their own frame rate. Bottom-bar titles and artists scroll once per track, like the mobile mini-players; the expanded player title scrolls continuously. Desktop marquees move in whole pixels at most 30 times a second on the playback animation clock, and stop while the window is hidden. Each step repaints the whole window.

The expressive mini-player title and artist stay on one line and show an ellipsis when space runs out, before and after their marquee pass, including with custom fonts.

## Lyrics

Shared lyrics live under `shared/src/commonMain/kotlin/com/metrolist/shared/ui/components/lyrics/`. Read the selected renderer and its position/visibility inputs before changing timing. Keep song offsets, global offsets and retimed timestamps consistent with tap-to-seek diagnostics.

Lyrics and player lyric captions disable standard font ligatures so letters such as `fi` stay separate. Background vocals, translations and romanization inherit this text style.

Scrolling Add buttons keep trailing padding outside the animated label so expansion and collapse keep an inset. Their width, label fade and scroll-driven slide animate.

Expressive and Braccato active lines request wall-clock frames only while playing. Their positions are snapshot state, so a paused line still redraws after a seek. Animation visibility is separate from playback ownership. Pausing or hiding a view must not alter the queue or playback clock. Test line changes, word timing, scrolling, selection and returning from a covered player with the affected style.

Player lyric captions have a separate alignment setting, centered by default. They reserve two line heights and center shorter text vertically. Keep the text crossfade without a size transform and measure the full content height so incoming lines, translations and romanization remain visible.

## Themes

Generated palettes use MaterialKolor 6's upstream color engine, which corrects Expressive primary and on-primary colors for warm and pale seeds. Tonal spot, Vibrant, Expressive and Neutral use `SPEC_2025`; the other bundled styles resolve to `SPEC_2021` upstream. The 2026 specification applies to CMF, a separate palette that requires a second seed color. The selected palette style and accessibility contrast still apply to artwork, custom colors and Samsung wallpaper palettes. Other Android system palettes use the platform's color roles.

Android publishes system dark mode to shared settings before palette resolution. Check Auto mode after a system theme change. Player action groups use Screen blending over non-solid dark backgrounds and SrcOver in light mode or over solid backgrounds. Filled player controls pair surface-variant containers with on-surface-variant icons, including over artwork in light mode.

Android status-bar icons follow the full player's foreground contrast while the player is visible. Collapsing the player, entering picture-in-picture or pausing the activity releases its override, so the app theme and artist header control the icons again.

Artwork palettes request a fixed source and decode at 128 px with a separate memory-cache key. Google artwork uses 256 px sources; YouTube video thumbnails use the bar-free `mqdefault` source. Palette requests do not reuse larger display thumbnails, so artwork load order and URL size variants cannot change the sampled colors. Animated covers do not supply palette frames.

Seeds use Material's Score over exact color counts from samples of up to 128 pixels per side. Keeping the sampled colors avoids merging sparse accents into neutrals. Grayscale artwork uses Material's blue fallback. Desktop samples the current track once, independently of displayed artwork. Track changes cancel the previous palette request and keep the current colors until the next palette loads. The main window, player controls, dialogs, snackbars and detached mini player share one animated scheme. Windows accent colors apply only when a system palette is requested and no artwork seed is active; they never replace a custom or artwork seed that happens to match the default color.

The landscape TV player keeps artwork backgrounds visible through a dark scrim behind its controls. Solid backgrounds keep their surface and content color pair. Both color changes animate with the theme motion scheme.

The full-screen artwork style also applies to audio tracks in immersive landscape mode. Immersive mode hides system bars without changing the selected artwork style. Playing music videos retain their inline surface and fullscreen controls.

Current-song rows use a pill container, scalloped artwork and matching content colors. Color transitions run in both directions. Preserve playing/paused accessibility descriptions without a repeating list animation.

## Verification

Run affected [platform checks](checks.md#platform-checks). Native checks must verify taps, keyboard actions, dismissal and rendering.

Native checks must cover interrupted motion, missing and portrait artwork, theme changes, reduced motion, large fonts and accessibility. Compare CPU/GPU use when changing shaders, blur or visibility work. Backend tests do not establish rendering or power behavior.

Light-theme Liquid backgrounds use a white wash with dark controls. Fullscreen video backdrops retain light controls. Lyrics use the same foreground polarity for blending and contrast scrims; additive blending applies only to light text.

The landscape fullscreen contrast wash covers the sharp artwork as well as its backdrop, so tablet controls remain legible when opening the queue narrows the player pane.

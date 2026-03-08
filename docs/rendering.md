# Rendering

## Artwork

Shared collection covers resolve through `LibraryCovers.kt`. Keep user-selected covers ahead of built-in resources. Android widgets and Android Auto use the same artwork provider for offline covers.

Player and collection artwork use square, centered crops. Full-player video thumbnails use the same preview source as song rows, including the thumbnail without encoded letterboxing. Dedicated video shelves, episode previews and video playback keep their wide layouts. On iOS, managed playlist-cover identifiers resolve against the current Application Support directory so container relocation does not break covers. Delete only managed files after the database change succeeds.

Android mini-player and expanded-player artwork slots request crop explicitly through the player platform. The loading artwork uses the same crop through the first-frame fade. Fullscreen video uses fit, including letterboxing when the screen and video have different aspect ratios. PiP has its own scaling.

Android full-player artwork uses software bitmaps so Compose and image decoding can share pixels. Export code copies hardware images when a software canvas requires it.

When skipping in the expanded player, keep the displayed track and decoded cover until the next cover or mini-player preview is ready. First open shows metadata immediately. Failed or missing covers advance to the new track without keeping old metadata. Keep the background fades and crossfade the title and artist together. Android's pause control follows playback intent between tracks, including a pause requested during loading.

## Desktop artwork blur

`ArtworkBlur.desktop.kt` keeps one viewport-sized raster snapshot per drawing node for expanded-player, lyrics-drawer and mini-player artwork blurs. A 1080 by 720 RGBA snapshot uses about 3 MB; a 4K snapshot about 32 MiB. Artwork, painter animation, crossfade, size, density and theme changes redraw the snapshot. Other redraws reuse it. Replacing the snapshot or detaching the node releases the previous image. The first blur and each invalidation still pay the full rasterization cost.

The cache applies Compose's `BlurEffect` filter, including its radius-to-sigma conversion, and draws the scrim into the snapshot. It keeps the original crop, scale, saturation, source background and clip. Apply the modifier directly to artwork drawing; child graphics layers can update independently of their raster parent. The shared mini-player uses this cache on desktop and native blur on Android and iOS. Shared shader backgrounds use their own preblurred texture cache.

On GPU backends the raster path can differ from the GPU filter by one channel step from rounding. `ArtworkBlurTest` compares the raster path with the Compose/Skia RenderNode pipeline across sizes, densities, edges, backdrops and scrims.

Wavy, Squiggly and Now Playing bars advance their phase from monotonic time at 30 updates per second. A phase write schedules its own rendering; waiting on the frame clock first schedules another render of the previous phase. Lifecycle and window visibility suspend hidden work. Visible unfocused windows keep animating.

## Icons and attribution

Shared glyphs come from Google's [Material Symbols](https://github.com/google/material-design-icons/tree/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web) under [Apache 2.0](../assets/material-symbols/LICENSE). `scripts/generate_material_symbols.py` pins the source revision and glyph mapping. Keep weight 400, grade 0, optical size 24, fill and mirroring consistent with that mapping.

Run `python3 scripts/generate_material_symbols.py regen`, then `verify`. For an upstream refresh, pass `--revision <full-sha>`, update `UPSTREAM_REVISION` and record the SHA in the commit body. `taskbar` regenerates Windows ICOs with `rsvg-convert` and Pillow. Run `python3 scripts/test_generate_material_symbols.py` after generator changes.

Gradient artwork lives in `assets/app-icons/gradient/`; the alternate icons are by Kayé. Launcher, brand and tray art are separate from Material Symbols. Preserve their source artwork and notices. Last.fm artist biographies keep their attribution link to Last.fm.

## Motion and visibility

Use the theme's MotionScheme and respect system animation duration scale. Keep enter and exit transitions mounted until they finish. Interrupted transitions must resume from their current state.

Shared dialogs and button popups use the theme's fast effects spec for opacity and fast spatial spec for a 0.95-to-1 scale. They do not register shared bounds or hide the opening control. Animation values are read in `graphicsLayer`, without frame-by-frame composition or measurement. Page transitions and bottom-sheet slides use their own motion.

Popup input guards belong to each popup session. Completion and cancellation release them in `finally`; disposal clears the session. A superseded animation cannot release a newer request's guard. The closing popup is removed after its animation, while sheets keep their own dismissal lifetime.

The portrait player's canvas controls fade and expand or shrink. Its queue toolbar fades and slides; title and seek rows follow canvas and fullscreen-lyrics state. Queue-toolbar alpha uses drawing modulation so Screen-blended controls can sample the artwork.

Player expansion clips both content layers to the moving container. Check reversing expansion and collapse, short drags and navigation insets. DEFAULT seek sliders must follow the [agent rule](../AGENTS.md#implementation-rules).

Animated artwork and lyrics run only while their content is visible. Hidden players must release frame work while keeping playback. Check resumption at the current position after backgrounding, minimizing or switching workspaces. Hyprland window handling is in [desktop rendering](desktop.md#hyprland-rendering).

Desktop seek bars update their progress value once per pixel of travel, based on duration, measured width and playback speed, within 16 to 250 ms. Time labels update when the displayed second changes. WAVY and SQUIGGLY tracks keep their playing animation; lyrics have their own frame rate. Bottom-bar titles and artists scroll once per track, like the mobile mini-players; the expanded player title scrolls continuously. Each marquee frame repaints the whole window.

The expressive mini-player title and artist stay on one line and show an ellipsis when space runs out, before and after their marquee pass, including with custom fonts.

## Lyrics

Shared lyrics live under `shared/src/commonMain/kotlin/com/metrolist/shared/ui/components/lyrics/`. Read the selected renderer and its position/visibility inputs before changing timing. Keep song offsets, global offsets and retimed timestamps consistent with tap-to-seek diagnostics.

Lyrics and player lyric captions disable standard font ligatures so letters such as `fi` stay separate. Background vocals, translations and romanization inherit this text style.

Scrolling Add buttons keep trailing padding outside the animated label so expansion and collapse keep an inset. Their width, label fade and scroll-driven slide animate.

Animation visibility is separate from playback ownership. Pausing or hiding a view must not alter the queue or playback clock. Test line changes, word timing, scrolling, selection and returning from a covered player with the affected style.

Player lyric captions have a separate alignment setting, centered by default. They reserve two line heights and center shorter text vertically. Keep the text crossfade without a size transform and measure the full content height so incoming lines, translations and romanization remain visible.

## Themes

Android publishes system dark mode to shared settings before palette resolution. Check Auto mode after a system theme change. Player action groups use Screen blending over non-solid dark backgrounds and SrcOver in light mode or over solid backgrounds.

Artwork palettes request a fixed source and decode at 128 px with a separate memory-cache key. Google artwork uses 256 px sources; YouTube video thumbnails use the bar-free `mqdefault` source. Palette requests do not reuse larger display thumbnails, so artwork load order and URL size variants cannot change the sampled colors. Animated covers do not supply palette frames.

The landscape TV player keeps artwork backgrounds visible through a dark scrim behind its controls. Solid backgrounds keep their surface and content color pair. Both color changes animate with the theme motion scheme.

Current-song rows use a pill container, scalloped artwork and matching content colors. Color transitions run in both directions. Preserve playing/paused accessibility descriptions without a repeating list animation.

## Verification

Run affected [platform checks](checks.md#platform-checks). Relevant backend suites include `VisibleWorkTest`, `PlayerArtworkHandoffTest`, `LibraryCoversTest` and `ArtworkBlurTest`. Backend tests cover popup guard release paths; native checks must verify taps, keyboard actions, dismissal and rendering.

Native checks must cover interrupted motion, missing and portrait artwork, theme changes, reduced motion, large fonts and accessibility. Compare CPU/GPU use when changing shaders, blur or visibility work. Backend tests do not establish rendering or power behavior.

---v14.0.0
# First public multiplatform release
Metrolist 14.0.0 replaces the standalone Android app with Metrolist-KMP.

## Highlights
- Added Windows portable ZIPs and an x64 installer, macOS DMGs, and Linux AppImages, with x64 and ARM64 desktop builds (@l6t9 @nyxiereal @applxcake)
- Added iOS and iPadOS 17+ support through an unsigned IPA that needs signing before sideloading (@nyxiereal)
- Redesigned the app with Material 3 Expressive, new player layouts and an Expressive mini player (@mostafaalagamy @alexxbytes @isotjs)
- Improved Android TV remote navigation and added pinned playlists and song queue actions in Android Auto (@nyxiereal)

## New features
- Added music-video playback on Android and desktop, plus YouTube live audio and video (@nyxiereal @l6t9 @applxcake)
- Added ListenBrainz scrobbling alongside Last.fm (@nyxiereal)
- Import playlists from Spotify, Apple Music, Deezer, Tidal and Amazon Music (@nyxiereal)
- Import compatible InnerTune, ViMusic, Kreate, SimpMusic and Prism backups on Android and desktop (@nyxiereal)
- Added optional blocking of AI-generated artists and local hiding of artists and podcasts (@nyxiereal)
- Added SponsorBlock skipping for podcast episodes on Android and desktop (@nyxiereal)
- Added opt-in Device Connect pairing and remote playback between Android and desktop on the local network (@adrielGGmotion @nyxiereal)
- Added Lyprio, lrc.red, SimpMusic and Unison lyrics sources, provider search and an editor for lyrics and word timings (@l6t9 @nyxiereal @ibratabian17)
- Added custom font imports and on-demand Google Sans downloads on Android (@nyxiereal @adrielGGmotion)

## Changes from 13.x
- Moved playback, library, settings and integrations into a Kotlin Multiplatform codebase (@l6t9 @mostafaalagamy @nyxiereal)
- MetroEQ is available across platforms, with directly editable bands and AutoEQ profile imports (@kairosci @nyxiereal)
- Casting on Android and desktop supports Google Cast without Google Play Services, FCast, DLNA and Sonos (@nyxiereal @ibratabian17)
- Listen Together rooms now work across Android, desktop and iOS (@nyxiereal)
- Android suspends paused-player timers and limits Cast discovery to the foreground device picker (@nyxiereal)
- Removed LyricsPlus and Zemer lyrics providers; music alarms are not included

## Fixes
- Corrected crossfade gain with silence skipping and volume normalization (@kairosci @nyxiereal)
- Preserved duplicate song occurrences when importing foreign playlists (@nyxiereal)
- Restored offline browsing of saved YouTube playlists in Android Auto (@nyxiereal)
- Rejected Lyprio results for the wrong artist and preserved manual lyric edits when switching providers (@nyxiereal)

## Known limitations
- iOS does not yet support music-video playback, casting, Device Connect, SponsorBlock or foreign-app backup import
- iOS HLS fallback streams cannot use MetroEQ or independent pitch adjustment

Full changelog: https://github.com/MetrolistGroup/Metrolist/commits/v14.0.0

---v13.7.0
# KMP status update
Metrolist-KMP remains in alpha. To try it, join our Discord and donate at least $1 to support development.

## Highlights
- Migrated networking to InnerTubeX with automatic client fallback (@nyxiereal)
- Reduced memory use and background work in large libraries, History, Cache, Android Auto, and audio processing (@makro17 @nyxiereal)
- Repaired older-version upgrades, Android Auto browsing, artist metadata, and YouTube channel switching (@nyxiereal)
- Improved login, account loading, uploads, downloads, caching, metadata editing, and playlist sync (@nyxiereal)
- Restored the classic app icon (@nyxiereal)

## New features
- Choose whether songs are added to the start or end of playlists (@nyxiereal)
- Added Zemer lyrics for Jewish music (@alltechdev)
- Added detailed, copyable playback error reports (@nyxiereal)
- Added a unified update prompt for standalone and KMP releases (@nyxiereal)
- Added Inception AI models and improved lyric translation output (@nyxiereal)
- Restored universal x86 and x86_64 support (@nyxiereal)

## Fixes and improvements
- Fixed missing or cropped artwork and improved timed and Cyrillic lyrics (@RizkLee @GameOn223 @Cocoa2219 @nyxiereal)
- Preserved manual metadata edits during refreshes and corrected Cache Playlist contents (@nyxiereal)
- Fixed repeat and shuffle with crossfade, Listen Together stutter, and paused queues restarting at the end (@nyxiereal)
- Fixed per-song volume normalization and improved playback recovery (@nyxiereal)
- Fixed dismissed media controls reappearing and several startup crashes (@nyxiereal)
- Protected local likes from incomplete sync responses and improved uploaded-song handling (@nyxiereal)
- Updated dependencies and CI (@nyxiereal)

New contributors: @RizkLee (#4277), @GameOn223 (#4269), and @makro17 (#4270)

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.6.3...v13.7.0

---v13.6.3

This hotfix repairs lyrics and the media controller.

---v13.6.2
# KMP development
We are fixing the remaining bugs in Metrolist KMP and preparing for release. It is still at least a couple of weeks away.

# Major changes
- Fixed playback issues caused by changes that also broke official apps (@nyxiereal)
- Fixed playlist sync duplication and out-of-memory crashes (@kairosci)
- Improved automatic player configuration updates for future YouTube changes (@mostafaalagamy @nyxiereal)

## Notable new features
- Added handling for KMP updates and migrations (@nyxiereal)

## Other improvements
- Improved Android Auto voice search matching and radio queue generation (@FireLion137)
- Added an Android Auto search limit and optimized local searches to prevent out-of-memory crashes (@FireLion137)
- Fixed podcast playback errors (@kairosci)
- Fixed crossfade timing at non-default playback speeds (@kairosci)
- Fixed the app lingering in the background after it was closed (@kairosci)
- Fixed sleep timer dialog layouts and menus containing long translated text (@kairosci)
- Fixed the persistent shuffle setting not working (@SimoneFelici)
- Dimmed the repeat button when repeat is disabled (@arpitagarwal1301)
- Rounded the corners of exported lyrics images (@arpitagarwal1301)
- Updated dependencies (@nyxiereal)

## New contributors
* @SimoneFelici made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/4102
* @arpitagarwal1301 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/4178

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.6.1...v13.6.2

---v13.6.1
# KMP development
We aim to release the Kotlin Multiplatform version of Metrolist within the next month. Until then, the current app remains in maintenance mode and receives bug fixes and minor improvements.

# Major changes
- Improved playback reliability and recovery from YouTube player failures (@alltechdev @JASK625 @kairosci @mostafaalagamy @nyxiereal)
- Fixed black screens, startup crashes, and playback freezes (@kairosci @mostafaalagamy @nyxiereal)
- Improved Listen Together synchronization (@nyxiereal)

## Notable new features
- Added refreshed branding and an optional dynamic app icon (@mostafaalagamy)
- Added predictive back support and improved landscape scaling (@HansHolz09 @kairosci)

## Other improvements
- Fixed login, logout, and backup restoration issues (@mostafaalagamy @kairosci @nyxiereal)
- Fixed incorrect artist names, song durations, podcast metadata, and missing artwork (@alltechdev @kairosci @mostafaalagamy @nyxiereal)
- Fixed Android Auto freezes and playback delays (@kairosci)
- Fixed foreground service ANRs and multiple other crashes (@kairosci @mostafaalagamy)
- Fixed sleep timer crashes and restored saved defaults (@johannesbrauer @mostafaalagamy)
- Improved library sync performance and YouTube player compatibility (@mostafaalagamy @nyxiereal)

## New contributors
* @HansHolz09 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3837

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.6.0...v13.6.1

---v13.5.0
# Maintenance mode
Metrolist is in maintenance mode. We accept only bug fixes and minor improvements. Do not submit PRs for new features or major changes.

# Major changes
- Rewrote the Discord RPC integration again (@adrielGGmotion @nyxiereal)
- Fixed random playback issues and pauses (@DanielSchmerber @isotjs)
- Fixed display issues with liked songs, playlists, albums and search results (@adrielGGmotion @nyxiereal)

## Notable new features
- Added a toggle for automatic radio queue generation (@FireLion137)
- Added automatic tablet UI scaling (@kairosci)
- Switch from repeat one to repeat all after a song change (@sunjeetkajla)

## Other improvements
- Fixed covers sometimes failing to load (@Arjuanto)
- Fixed artist name splitting and made the names clickable (@kairosci)
- Fixed multiple crashes (@kairosci @nyxiereal)
- Improved audio normalization (@kairosci)
- Fixed search results not being combined properly (@kairosci)
- Fixed 'High quality' option not choosing the highest quality option (@kairosci)
- Fixed history sync not working (@kairosci)

## New contributors
- @DanielSchmerber made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3777
- @Arjuanto made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3780

---v13.4.3
# Maintenance mode
Metrolist is in maintenance mode. We accept only bug fixes and minor improvements. Do not submit PRs for new features or major changes.

# Major changes
- Rewrote the Discord RPC integration (@adrielGGmotion)
- Improved the look of playlist screens (@adrielGGmotion)
- Added a new playlist widget (@David-2765 @AntonioDionisio05)

## Notable new features
- Added Apple Music lyrics support (@adrielGGmotion)
- Added a normalization level selector (@Jeff0945)
- Added an option to hide monthly and weekly most-played playlists (@isotjs)

## Other improvements
- Improved performance and stability (@adrielGGmotion @nyxiereal)
- Fixed Devanagari lyrics display (@cloud-zip)
- Improved lyrics fetching speed (@nyxiereal)
- Fixed crashes and some memory leaks (@nyxiereal)
- Fixed images being low resolution for some users (@adrielGGmotion)
- Fixed playlist paging (@kairosci)
- Made smaller improvements (@kairosci)

## New contributors
- @Jeff0945 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3358

---v13.4.2
# Maintenance mode
Metrolist is in maintenance mode. We accept only bug fixes and minor improvements. Do not submit PRs for new features or major changes.

# Major changes
- Fixed random crashes and some memory leaks (@nyxiereal)
- Fixed issues with uploading songs to YouTube (@kairosci)
- Fixed playback for uploaded songs (@punkscience)

## Notable new features
- EQ screen redesign and guided AutoEQ profile import (@ndellagrotte)
- Automatically create database backups before updates (@nyxiereal)

## Other improvements
- Improved support for Android Auto (@cmeka)
- Brought back the copy lyrics button for experimental lyrics (@nyxiereal)
- Fixed the re-sync button for experimental lyrics (@nyxiereal)
- Fixed listen together not working (@nyxiereal)
- Added back support for choosing an account upon login (@nyxiereal)
- Added concurrent lyrics fetching, fixed fetch ordering and optimized LyricsPlus server selection (@ibratabian17)
- Corrected the play-next shuffle order (@johannesbrauer)
- Improved the Android Auto icon (@ThatOneCalculator)

## New contributors
- @ndellagrotte made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3487
- @cmeka made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3534
- @punkscience made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3517

---v13.4.1
# Maintenance mode
Metrolist is in maintenance mode. We accept only bug fixes and minor improvements. Do not submit PRs for new features or major changes.

# Major changes
- Fixed cached songs showing up in the downloads playlist (@nyxiereal)
- Fixed multiple playback issues and prepared for YouTube's player changes (@mostafaalagamy @nyxiereal)

## Notable new features
- Added direct playback by pasting URLs into search (@nyxiereal)
- Added a search bar to the Library screen (@isotjs)
- Added a setting to bind pitch and speed together (@sasha-melech)
- Added support for Gemini voice playback (@FireLion137)
- Added an option to choose the highest available audio quality (@nyxiereal @kairosci)
- Added a button to create a playlist from the Library screen (@SunjeetKajla)

## Other improvements
- Moved the resync button to the lyrics menu (@nyxiereal)
- Fixed player reset after IO errors (@kairosci)
- Multiple improvements to lyrics fetching and parsing (@kairosci @nyxiereal @ibratabian17)
- Added a setting to disable autoplay (@kairosci)
- Fixed foreground/background service crashes (@kairosci)
- Fixed Play next not working (@johannesbrauer)
- Fixed database updates when removing downloads (@kairosci)
- Used LyricsPlus caching to lower server load (@binimum)
- Performance optimizations (@stopper2408)
- Prefetch lyrics for the next song if currently viewing lyrics (@nyxiereal)
- Fixed multiple issues with Listen Together (@nyxiereal)
- Fixed multiple issues with the experimental lyrics (@nyxiereal)
- Fixed pause music on task clear not working (@nyxiereal)

## New contributors
* @ibratabian17 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3474
* @sasha-melech made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3301
* @FireLion137 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3500
* @binimum made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3493
* @stopper2408 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3506
* @SunjeetKajla made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3505

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.4.0...v13.4.1
---v13.4.0
# Maintenance mode
Metrolist is in maintenance mode. We accept only bug fixes and minor improvements. Do not submit PRs for new features or major changes.

This April 1 release starts maintenance mode. Metrolist development continues.

# Major changes
- Multiple playback fixes and reliability improvements (@alltechdev)
- Updated the lyrics engine to improve accuracy and usability (@adrielGGmotion)
- Fixed multiple crash issues (@kairosci, @nyxiereal)
- Multiple improvements to Android Auto support (@andker87)
- Fixed multiple grammar and text inconsistency issues in the project (@TheRebo)

## Notable new features
- Added support for treating cached songs as offline songs (@kairosci)
- Added music alarm scheduling (@0xarchit)
- Added miniplayer styles (@johannesbrauer)
- Added a button to copy all song lyrics to the clipboard (@kairosci)
- Added listening-time transfers between songs on the stats page (@finley-webber)
- Added customization support for the AI prompt used for translations (@nyxiereal)
- Added a notification-based music recognition for the QS tile shortcut (@isotjs)

## Other improvements
- Fixed incorrect artist order for multi-artist songs (@AntonioDionisio05)
- Fixed playtime in the stats page not being fully visible (@David-2765)
- Improved radio startup from the currently playing track (@luigiwwmf)
- Improved the UI for tablets (@adrielGGmotion)
- Improved the About Screen layout (@adrielGGmotion)
- Fixed ghost adds on playlists (@johannesbrauer)
- Improved search focus and navigation behavior (@saivijaychandan)
- Added album navigation on song title click regardless of play source (@gergesh)
- Prevented UI state reset when switching apps (@mostafaalagamy)
- Restored the Daily Discover title in the Home screen (@mostafaalagamy)
- Fixed listen together audio choppiness (@nyxiereal)
- Redesigned romanization and account settings (@omardotdev)
- Improved the design of the sleep timer dialog (@johannesbrauer)
- Redesigned some components to use Material 3 Expressive (@johannesbrauer)
- Fixed links in the README (@Lolen10 @nyxiereal)

## New contributors
* @AntonioDionisio05 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3255
* @David-2765 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3271
* @luigiwwmf made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3293
* @gergesh made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3300
* @Lolen10 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3328

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.3.0...v13.3.1
---v13.3.0
# Major changes
- Added song uploads and deletion (@alltechdev)
- Multiple playback fixes and reliability improvements (@alltechdev, @mostafaalagamy)
- Fixed ProGuard rules that broke reproducible builds (@nyxiereal)
- Fixed ProGuard rules that removed Listen Together protobuf classes (@mostafaalagamy)
- Added a playlist export option to the playlist context menu (@nyxiereal)

## Notable new features
- Added a Play all action for the stats page (@isotjs)
- Added a quick settings tile for recognizing music (@nyxiereal)
- Added automatic sleep timer options and integrated fade-out volume handling (@isotjs)
- Added a profile search filter (@alltechdev)
- Added channel subscriptions for podcasts and artists (@alltechdev)

## Other improvements
- Fixed cached images not clearing properly and cached covers not showing when offline (@nyxiereal)
- Removed useless and stale strings from the codebase (@nyxiereal)
- Refined the song details view (@omardotdev)
- Added support for Mistral AI models (@nyxiereal)
- Redesigned Last.fm integration settings (@omardotdev)
- Fixed crashes when importing CSV files (@nyxiereal)
- Prevented guest playback while in Listen Together (@nyxiereal)
- Fixed podcasts not working for logged-out users (@alltechdev)
- Updated dependencies (@nyxiereal)

## New contributors
* @isotjs made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3090

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.2.1...v13.3.0
---v13.2.1
>[!WARNING]
>Listen Together does not work in v13.2.1. Use v13.2.0 if you need it.

## Hotfixes
- Fixed interface lag
- Fixed navigation to local playlists pinned in Speed Dial
- Removed "cache songs only after playback has started" option

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.2.0...v13.2.1
---v13.2.0
# Major changes
- Fixed playback breaking due to YouTube's February 2026 n-transform changes (@alltechdev)
- Added full podcast library support (@mostafaalagamy & @alltechdev)
- Redesigned loading, Changelog, and About screens (@adrielGGmotion)
- Improved app startup time via parallelized home screen loading (@mostafaalagamy)

## Notable new features
- Added an option to cache songs only after playback has started (@kairosci)
- Added a music recognizer home screen widget (@mostafaalagamy)
- Rewrote the music recognizer in pure Kotlin to remove its NDK dependency and reduce APK size (@mostafaalagamy)
- Added the LyricsPlus provider, AI lyric fixes, untranslation support and provider priority settings (@nyxiereal)
- Switched Listen Together to protobuf to lower latency and improve reliability (@nyxiereal)
- Added an auto-approve setting for Listen Together song requests (@nyxiereal)
- Added an option to persist the sleep timer default value (@johannesbrauer)
- Added a dialog on logout to keep or clear library data (@alltechdev)

## Other improvements
- Fixed backup restore causing playback errors due to stale auth credentials (@alltechdev)
- The CSV import dialog is now scrollable (@kairosci)
- Fixed Android 15 foreground service crashes (@kairosci)
- Fixed a crash on the About screen on some devices (@mostafaalagamy)
- Fixed home screen playlist navigation routing to wrong screen (@mostafaalagamy)
- Fixed crash when creating local playlists (@mostafaalagamy)

## New contributors
* @johannesbrauer made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/2991

Full changelog: https://github.com/MetrolistGroup/Metrolist/compare/v13.1.1...v13.2.0

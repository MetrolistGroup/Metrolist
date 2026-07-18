---v13.6.1
# THE FUTURE OF METROLIST
The new Kotlin Multiplatform version of Metrolist is now in a good state, and we are aiming to release it within the next month. Until then, the current app will remain in maintenance mode and receive bug fixes and minor improvements.

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

## New Contributors
* @HansHolz09 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3837

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.6.0...v13.6.1

---v13.5.0
# MAINTENANCE MODE
Metrolist is currently in maintenance mode. This means we will only be fixing bugs and making minor improvements. Please do not submit PRs for new features or major changes, as they will not be accepted.

# Major changes
- Rewrote the Discord RPC integration again (@adrielGGmotion @nyxiereal)
- Fixed random playback issues and pauses (@DanielSchmerber @isotjs)
- Fixed liked songs, playlists, albums, search results, etc. not displaying properly (@adrielGGmotion @nyxiereal)

## Notable new features
- Added a toggle for automatic radio queue generation (@FireLion137)
- Added automatic tablet UI scaling (@kairosci)
- Toggle from repeat(1) to repeat(all) after song change (@sunjeetkajla)

## Other improvements
- Fixe covers not loading sometimes (@Arjuanto)
- Artist names are now split properly, and are now clickable (@kairosci)
- Fixed multiple crashes (@kairosci @nyxiereal)
- Improved audio normalization (@kairosci)
- Fixed search results not being combined properly (@kairosci)
- Fixed 'High quality' option not choosing the highest quality option (@kairosci)
- Fixed history sync not working (@kairosci)

## New Contributors
- @DanielSchmerber made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3777
- @Arjuanto made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3780

---v13.4.3
# MAINTENANCE MODE
Metrolist is currently in maintenance mode. This means we will only be fixing bugs and making minor improvements. Please do not submit PRs for new features or major changes, as they will not be accepted.

# Major changes
- Rewrote the Discord RPC integration (@adrielGGmotion)
- Improved the look of playlist screens (@adrielGGmotion)
- Added a new playlist widget (@David-2765 @AntonioDionisio05)

## Notable new features
- Added proper apple music lyrics support (@adrielGGmotion)
- Added a normalization level selector (@Jeff0945)
- Added the ability to hide monthly/weekly most playlists (@isotjs)

## Other improvements
- Improve overall performance and stability (@adrielGGmotion @nyxiereal)
- Fixed devnagari lyrics not being displayed properly (@cloud-zip)
- Improve lyrics fetching speed (@nyxiereal)
- Fixed crashes and some memory leaks (@nyxiereal)
- Fixed images being low resolution for some users (@adrielGGmotion)
- Handle playlist paging properly (@kairosci)
- Multiple smaller improvements by @kairosci <3

## New Contributors
- @Jeff0945 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3358

---v13.4.2
# MAINTENANCE MODE
Metrolist is currently in maintenance mode. This means we will only be fixing bugs and making minor improvements. Please do not submit PRs for new features or major changes, as they will not be accepted.

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
- Implemented concurrent fetching, fix lyrics fetch ordering, and optimize LyricsPlus server selection (@ibratabian17)
- Corrected the play-next shuffle order (@johannesbrauer)
- Improved the Android Auto icon (@ThatOneCalculator)

## New Contributors
- @ndellagrotte made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3487
- @cmeka made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3534
- @punkscience made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3517

---v13.4.1
# MAINTENANCE MODE
Metrolist is currently in maintenance mode. This means we will only be fixing bugs and making minor improvements. Please do not submit PRs for new features or major changes, as they will not be accepted.

# Major changes
- Fixed cached songs showing up in the downloads playlist (@nyxiereal)
- Fixed multiple playback issues and prepared for YouTube's player changes (@mostafaalagamy @nyxiereal)

## Notable new features
- Added the ability to paste URLs to the search to play them directly (@nyxiereal)
- Added a search bar to the Library screen (@isotjs)
- Added a setting to bind pitch and speed together (@sasha-melech)
- Added support for Gemini voice playback (@FireLion137)
- Added an option choose the highest possible audio quality (@nyxiereal @kairosci)
- Added a button to create a playlist from the Library screen (@SunjeetKajla)

## Other improvements
- Moved the resync button to the lyrics menu (@nyxiereal)
- Properly reset player on IO errors (@kairosci)
- Multiple improvements to lyrics fetching and parsing (@kairosci @nyxiereal @ibratabian17)
- Made autoplay disablable from the settings (@kairosci)
- Fixed foreground/background service crashes (@kairosci)
- Fixed Play next not working (@johannesbrauer)
- Properly handle database updates on download removal (@kairosci)
- Use lyricsplus caching to lower server load (@binimum)
- Performance optimizations (@stopper2408)
- Prefetch lyrics for the next song if currently viewing lyrics (@nyxiereal)
- Fixed multiple issues with Listen Together (@nyxiereal)
- Fixed multiple issues with the experimental lyrics (@nyxiereal)
- Fixed pause music on task clear not working (@nyxiereal)

## New Contributors
* @ibratabian17 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3474
* @sasha-melech made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3301
* @FireLion137 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3500
* @binimum made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3493
* @stopper2408 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3506
* @SunjeetKajla made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3505

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.4.0...v13.4.1
---v13.4.0
# MAINTENANCE MODE
Metrolist is currently in maintenance mode. This means we will only be fixing bugs and making minor improvements. Please do not submit PRs for new features or major changes, as they will not be accepted.

No, this is not an April Fools joke, even though this update is being released on April 1st.

We are working on something big for the future of Metrolist - this is not the end of the project.

# Major changes
- Multiple playback fixes and reliability improvements (@alltechdev)
- Revamped the entire Lyrics engine, improving lyric accuracy and usability (@adrielGGmotion)
- Fixed multiple crash issues (@kairosci, @nyxiereal)
- Multiple improvements to Android Auto support (@andker87)
- Fixed multiple grammar and text inconsistency issues in the project (@TheRebo)

## Notable new features
- Added support for treating cached songs as offline songs (@kairosci)
- Added music alarm scheduling (@0xarchit)
- Added miniplayer styles (@johannesbrauer)
- Added a button to copy all song lyrics to the clipboard (@kairosci)
- Added a time transfer feature to move listening time between songs in the stats page (@finley-webber)
- Added customization support for the AI prompt used for translations (@nyxiereal)
- Added a notification-based music recognition for the QS tile shortcut (@isotjs)

## Other improvements
- Fixed incorrect artist order for multi-artist songs (@AntonioDionisio05)
- Fixed playtime in the stats page not being fully visible (@David-2765)
- Improved radio to start seamlessly when initiated from the currently playing track (@luigiwwmf)
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

## New Contributors
* @AntonioDionisio05 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3255
* @David-2765 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3271
* @luigiwwmf made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3293
* @gergesh made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3300
* @Lolen10 made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3328

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.3.0...v13.3.1
---v13.3.0
# Major changes
- Implemented song upload and delete functionality (@alltechdev)
- Multiple playback fixes and reliability improvements (@alltechdev, @mostafaalagamy)
- Fixed proguard rules causing issues with Reproducible Builds (@nyxiereal)
- Fixed proguard rules removing Listen Together protobuf classes (@mostafaalagamy)
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
- Redesigned the lastfm integration settings (@omardotdev)
- Fixed importing csv files crashing the app (@nyxiereal)
- Prevent guest playback while in listen together (@nyxiereal)
- Fixed podcasts not working for logged-out users (@alltechdev)
- Updated dependencies (@nyxiereal)

## New Contributors
* @isotjs made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/3090

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.2.1...v13.3.0
---v13.2.1
>[!WARNING]
>Listen Together doesn't work in v13.2.1! Use v13.2.0 if you need it.

## Hot Fixes
- Fix interface lag issue
- Fix navigate local playlists pinned in speed dial
- Removed "cache songs only after playback has started" option

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.2.0...v13.2.1
---v13.2.0
# Major changes
- Fixed playback breaking due to YouTube's February 2026 n-transform changes (@alltechdev)
- Added full podcast library support (@mostafaalagamy & @alltechdev)
- Redesigned loading, Changelog, and About screens (@adrielGGmotion)
- Improved app startup time via parallelized home screen loading (@mostafaalagamy)

## Notable new features
- Added an option to cache songs only after playback has started (@kairosci)
- Added a music recognizer home screen widget (@mostafaalagamy)
- Rewrote music recognizer in pure Kotlin, removing NDK dependency and reducing APK size (@mostafaalagamy)
- Overhauled lyrics: added LyricsPlus provider, AI lyric fixes, untranslation support, and provider priority settings (@nyxiereal)
- Changed listen together to use protobuf, lowering latency and improving reliability (@nyxiereal)
- Added auto-approve setting for listen together song requests (@nyxiereal)
- Added an option to persist the sleep timer default value (@johannesbrauer)
- Added a dialog on logout to keep or clear library data (@alltechdev)

## Other improvements
- Fixed backup restore causing playback errors due to stale auth credentials (@alltechdev)
- The CSV import dialog is now scrollable (@kairosci)
- Fixed Android 15 foreground service crashes (@kairosci)
- Fixed a crash on the About screen on some devices (@mostafaalagamy)
- Fixed home screen playlist navigation routing to wrong screen (@mostafaalagamy)
- Fixed crash when creating local playlists (@mostafaalagamy)

## New Contributors
* @johannesbrauer made their first contribution in https://github.com/MetrolistGroup/Metrolist/pull/2991

**Full Changelog**: https://github.com/MetrolistGroup/Metrolist/compare/v13.1.1...v13.2.0
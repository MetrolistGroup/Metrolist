# Test fixtures

Checked-in media is synthesized with FFmpeg, not downloaded music, footage or artwork. The tones are dedicated to the public domain under CC0-1.0. FFmpeg and its encoders retain their own licenses; their executables are not distributed. Tests assert media properties rather than exact encoded bytes, which can vary by encoder version.

## Audio decode fixtures

`shared/src/desktopTest/resources/ffmpeg/` contains two-second 440 Hz stereo tones for WebM/Opus, M4A/AAC and AAC-in-MPEG-TS decoding. Tests use production decoders without network or audio hardware. Missing native libraries must fail rather than skip tests. AAC permits priming/padding variance.

`generateOpusTestFixture` embeds `opus-251.webm` in generated common test code for JVM and iOS parser/remux tests. The source fixture and its CC0 provenance stay here.

Regenerate with FFmpeg n9.0.2, from the repository root:

```sh
cd shared/src/desktopTest/resources/ffmpeg
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2' -ac 2 -c:a libopus -b:a 128k opus-251.webm
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' -ac 2 -c:a aac -b:a 128k -movflags +faststart aac-140.m4a
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=4' -ac 2 -c:a aac -b:a 32k -map_metadata -1 -movflags +dash+global_sidx -frag_duration 1000000 fragmented-aac.mp4
ffmpeg -i aac-140.m4a -c:a copy -f hls -hls_time 1 -hls_playlist_type vod -hls_segment_filename 'hls-%d.ts' hls.m3u8
```

Base64-encode `fragmented-aac.mp4` to regenerate the Kotlin constant, then remove the intermediate file. Keep only the Kotlin fixture in git.

## Canvas clips

The Android instrumentation fixtures are 32-second 64x48 clips with audio and one keyframe per second. Only the test APK packages them. Regenerate from the repository root with FFmpeg n9.0.2 and libx264, libvpx-vp9 and libopus:

```sh
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=64x48:rate=10:duration=32' \
  -f lavfi -i 'sine=frequency=440:sample_rate=8000:duration=32' \
  -c:v libx264 -profile:v baseline -pix_fmt yuv420p -crf 35 \
  -g 10 -keyint_min 10 -sc_threshold 0 -c:a aac -b:a 8k -map_metadata -1 \
  androidApp/src/androidTest/assets/canvas-source.mp4

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=64x48:rate=10:duration=32' \
  -f lavfi -i 'sine=frequency=440:sample_rate=8000:duration=32' \
  -c:v libvpx-vp9 -b:v 0 -crf 45 -g 10 -c:a libopus -b:a 6k -map_metadata -1 \
  androidApp/src/androidTest/assets/canvas-source.webm
```

Copy the generated MP4 to `shared/src/desktopTest/resources/ffmpeg/canvas-source.mp4` for desktop audio-only decoding checks against a muxed video source.

## Prism backup import

`shared/src/desktopTest/resources/backup/prism.sql` contains invented songs and listening events. `PrismBackupImportTest` constructs a database from that SQL and adds synthetic JSON/XML companion records in a ZIP. Edit those sources to regenerate the fixture; never substitute a user's backup.

The format was researched from [Yigder/Prism at 498965a9a3031ac3864051a334a0025334ba6a29](https://github.com/Yigder/Prism/tree/498965a9a3031ac3864051a334a0025334ba6a29), under GPL-3.0-or-later. Sources included the exported Room v8 schema and `data/Backup.kt`, `data/db/Database.kt`, `data/PrismLibrary.kt`, `data/OfflineLibrary.kt`, `data/ArtistPrefs.kt` and `data/model/Models.kt` under `app/src/main/java/com/prism/music/`. No Prism implementation code was copied or executed.

Prism ZIPs contain `databases/prism.db`, optional SQLite sidecars and these companion paths:

- `files/library/prism.json` for local playlists and saved albums.
- `files/offline/library.json` for synced bookmarks.
- `files/offline/pages/<id>.json` for cached playlist membership and order.
- `shared_prefs/artist_prefs.xml` for starred artists in its `favorites` JSON string.

Raw database imports cannot recover those companion collections. Listening durations come from `play_events`, never a fabricated duration from `lastPlayed`. Preserve existing Metrolist metadata and import deduplication. Do not fetch missing data from the network or import credentials/settings, local media or Prism-specific caches. Malformed recognized companion data and external XML entities must fail before merging. Preserve extraction size limits and the 4,096-entry cap; export oversized backups without downloads.

## Playlist header responses

`shared/src/desktopTest/resources/youtube/playlist-header-*.json` contains reduced signed-out WEB_REMIX browse responses. They keep header paths, text, artwork URLs and creator browse endpoints. Track bodies, menus and telemetry were removed. `PlaylistHeaderTest` covers facepiles, straplines, legacy detail headers, editable wrappers and localized statistics.

The browse IDs are `VLPLPFNXelFU5pPeamFpCnM6plwdHA1fJ27V` for Hann's public WOOF playlist, `VLRDCLAK5uy_kw2wIlEv9llILhO0qoMTLsBBhmjzuibAc` for All-Time Hip Hop Hits, `VLPLw-VjHDlEOgtH-Ce-dJzPOtIm5xTVC4t2` for the 500-track 80s playlist and `MPREb_dr8UmlYhpUp` for Fat Dog's WOOF. album. Search `WOOF!` with playlist filter `EgeKAQQoAEAB` to find the user playlist.

To refresh, POST to `https://music.youtube.com/youtubei/v1/browse` with the browse ID and a signed-out `WEB_REMIX` client context. The fixtures use client version `1.20260615.03.00`, `hl=en` and `gl=PL` as explicit probe parameters. Keep only the header renderer and its containing path before committing a fixture. The album supplies a real strapline shape for comparison; it does not change album parsing.

## Artist audience counts

`shared/src/jvmSharedTest/resources/youtube/artist-counts/*.json` contains 27 signed-out WEB_REMIX artist header captures from 2026-11-15. They retain only `subscriberCountText`, `longSubscriberCountText` and `monthlyListenerCount` under their original header paths, including text runs and accessibility labels. No cookies, account data, visitor data, menus or track bodies were saved.

To refresh, POST to `https://music.youtube.com/youtubei/v1/browse?prettyPrint=false` with browse ID `UC0C-w0YjGpqDXGB8IHb662A`. Use the pinned InnerTubeX v0.11.7 context: client name `WEB_REMIX`, client version `1.20260707.12.00`, the fixture's explicit `hl` and `gl`, `request.internalExperimentFlags=[]`, `request.useSsl=true` and `user.lockedSafetyMode=false`. Send no cookies. Keep only the three count subtrees and their containing path.

The file names identify the request pairs. They cover en/US, de/DE, fr/FR, es/ES, pt-BR/BR, it/IT, nl/NL, pl/PL, cs/CZ, ru/RU, uk/UA, tr/TR, ar/EG, fa/US, iw/IL, he/IL, hi/IN, bn/BD, th/TH, vi/VN, id/ID, ja/JP, ko/KR, zh-CN/SG, zh-TW/TW, hu/HU and fi/FI. Persian requests with IR and AF returned HTTP 400; fa/US returned Persian text. The supplied probe ID `UCvoQ3TJRxv-g8uPvnz5Nqfg` returned no artist header.

`ArtistAudienceFixturesTest` loads every capture on desktop and Android host, checks both subscriber fields and asserts the displayed count with its app label. It uses no settings or Android APIs.

## Glyphmind dictionary downloads

`shared/src/jvmSharedTest/resources/glyphmind/mandarin.bin` is the unmodified [dict-v1 Mandarin pack](https://github.com/nyxiereal/glyphmind/releases/download/dict-v1/mandarin.bin). It is 264,352 bytes with SHA-256 `ed3f75c21aadf72819f226ab47d17ccc35166c1acc2e2d0ae1d0801ea77f4fa0`. The backend wiring tests serve it through Ktor MockEngine and install it in temporary storage. Only test runtimes include this fixture; production still downloads it on demand.

The table combines Unicode Unihan 17.0.0 and houbb/pinyin 0.4.0 data. Its attribution and retained Unicode and Apache license texts are in [glyphmind's bundled notice](../shared/src/commonMain/composeResources/files/licenses/glyphmind/NOTICE.md) and the adjacent `licenses/` directory. Replacing the fixture requires verifying the published pack against glyphmind's pinned manifest.

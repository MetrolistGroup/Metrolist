# Test fixtures

Checked-in media is synthesized with FFmpeg, not downloaded music, footage or artwork. The tones are dedicated to the public domain under CC0-1.0. FFmpeg and its encoders retain their own licenses; their executables are not distributed. Tests assert media properties rather than exact encoded bytes, which can vary by encoder version.

## Audio decode fixtures

`shared/src/desktopTest/resources/ffmpeg/` contains two-second 440 Hz stereo tones for WebM/Opus, M4A/AAC and AAC-in-MPEG-TS decoding. Tests use production decoders without network or audio hardware. Missing native libraries must fail rather than skip tests. AAC permits priming/padding variance.

`generateOpusTestFixture` embeds `opus-251.webm` in generated common test code for JVM and iOS parser/remux tests. The source fixture and its CC0 provenance stay here.

`shared/src/iosTest/kotlin/com/metrolist/shared/storage/FragmentedAacFixture.kt` embeds a four-second 440 Hz 44.1 kHz stereo AAC-LC tone as Base64. The fragmented MP4 contains `ftyp`, `moov`, `sidx`, four `moof`/`mdat` pairs and `mfra`. The iOS capture tests serve this fixture and the remuxed Opus control through the production SABR capture and resource loader, with no YouTube requests. `IosFragmentedAacDecodeTest` decodes it with AVAssetReader.

`opus-peaks.webm` contains 2.013 seconds of distinct stereo tones with 5 ms Opus packets. Its floating-point decode exceeds full scale. The remux regression compares every float sample before integer conversion, including these peaks, pre-skip and final padding. Set `METROLIST_OPUS_WEBM` to a local mono/stereo WebM path to include a real capture in that JVM comparison. Captures stay outside git and require no network during the test.

Regenerate with FFmpeg n9.0.2, from the repository root:

```sh
cd shared/src/desktopTest/resources/ffmpeg
ffmpeg -f lavfi -i 'sine=frequency=440:sample_rate=48000:duration=2' -ac 2 -c:a libopus -b:a 128k opus-251.webm
ffmpeg -f lavfi -i 'aevalsrc=1.1*sin(2*PI*997*t)+0.2*sin(2*PI*13001*t)|0.8*sin(2*PI*2203*t):s=48000:d=2.013' -c:a libopus -b:a 128k -frame_duration 5 -map_metadata -1 opus-peaks.webm
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

## Uploaded-song response shapes

`shared/src/commonTest/kotlin/com/metrolist/shared/api/UploadedSongsTest.kt` uses invented JSON titles, IDs and cookies, not captured account responses. Shapes derive from existing parser fixtures and the pinned InnerTubeX `BrowseResponse` model. Prism's upload parser was reviewed as source only.

These fixtures and `DefaultStreamResolverTest` check upload parsing, authentication and identity propagation without YouTube access. They cannot establish the live response or immediate post-upload visibility for any particular Google account.

## Playlist header responses

`shared/src/desktopTest/resources/youtube/playlist-header-*.json` contains reduced signed-out WEB_REMIX browse responses. They keep header paths, text, artwork URLs and creator browse endpoints. Track bodies, menus and telemetry were removed. `PlaylistHeaderTest` covers facepiles, straplines, legacy detail headers, editable wrappers and localized statistics.

The browse IDs are `VLPLPFNXelFU5pPeamFpCnM6plwdHA1fJ27V` for Hann's public WOOF playlist, `VLRDCLAK5uy_kw2wIlEv9llILhO0qoMTLsBBhmjzuibAc` for All-Time Hip Hop Hits, `VLPLw-VjHDlEOgtH-Ce-dJzPOtIm5xTVC4t2` for the 500-track 80s playlist and `MPREb_dr8UmlYhpUp` for Fat Dog's WOOF. album. Search `WOOF!` with playlist filter `EgeKAQQoAEAB` to find the user playlist.

To refresh, POST to `https://music.youtube.com/youtubei/v1/browse` with the browse ID and a signed-out `WEB_REMIX` client context. The fixtures use client version `1.20260615.03.00`, `hl=en` and `gl=PL` as explicit probe parameters. Keep only the header renderer and its containing path before committing a fixture. The album supplies a real strapline shape for comparison; it does not change album parsing.

## Music-video round trips

`MusicVideoResolverTest.audioSwitchRetainsUgcVideoWhenNextAndVideoSearchHaveNoCounterpart` uses reduced `/next` rows from signed-out WEB_REMIX requests with client version `1.20260707.12.00`. `W5ww34C76Gk` is the UGC upload of Myth In Slaughter; `olNdzTu5QYg` is its ATV audio track. Both responses contain an ordinary `playlistPanelVideoRenderer` without a counterpart wrapper. The fixture keeps the video ID and type, with a shortened containing path. Search results are supplied by the test, not captured. The authoritative timing fixture in the adjacent test is synthetic.

## Home renderer projection

`HomeRendererParityTest` uses reduced synthetic JSON for ordinary and immersive Home headers, mix subtitle runs and initial/shelf/page continuation tokens. Its IDs, titles and artwork strings are invented; it is not a signed-in Home capture. `HomeShelfBuilderTest` exercises the shared data projection without rendering UI. See [Home projection](innertube.md#home) for the shared behavior.

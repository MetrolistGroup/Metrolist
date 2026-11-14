# iOS Opus playback

## Selection and offline audio

iOS uses AVPlayer with AAC/MP4 or remuxed Opus. The InnerTubeX pin in `gradle.properties` determines available clients and format selection. Client overrides still apply and can fail when no compatible response exists.

Existing downloads and cache entries take priority over new streaming quality choices. Use an uncached song to test selection. Manual downloads request AAC without HLS or SABR. Opus and SABR captures remain temporary and never publish raw WebM as offline audio.

## Remux format

`WebmOpusDemuxer.kt` accepts mono/stereo mapping-family-zero Opus, SimpleBlock, BlockGroup, lacing and unknown-sized segments/clusters. It validates element boundaries, codec delay, packet duration and timestamp continuity. It rejects encryption, extra Opus tracks, unsupported mappings, leading discard padding and discontinuities.

`OpusMp4Remuxer.kt` copies compressed packets into indexed MP4. It preserves pre-skip, final discard padding, decoder gain, input sample rate and seek preroll. Sample tables and movie, track and media durations use a 48 kHz timeline. An edit list defines the trimmed presentation; the final sample duration excludes end padding.

Use the [Opus MP4 mapping](https://www.opus-codec.org/docs/opus_in_isobmff.html) and [WebM container guidelines](https://www.webmproject.org/docs/container/) when changing the format. Keep [fixture provenance](test-fixtures.md#audio-decode-fixtures).

## Capture limits

`IosPlaybackCache` validates trusted URLs, redirects, ranges, lengths and free space. SABR uses InnerTubeX's Googlevideo validation and UMP transport. Whole-file preparation has a two-minute bound and validates byte count and media duration.

Opus needs a complete input before playback, limited to 32 MiB. That bounds input size, not total memory during demux/remux. AVFoundation reads ranges from the remuxed MP4 buffer. Preloads, crossfades and reloads retain capture leases; final release deletes temporary Opus files. Capture-dependent representations cannot fall back directly to native HTTPS when admission fails.

Direct capture or remux failure retries once as AAC while preserving queue, position and playback intent. Later resolutions keep AAC until another song is selected. Failed SABR preparation excludes its profile and uses client recovery. A failed replacement preserves the outgoing stream. See [attestation and SABR](innertube.md#sabr) for token and progress rules.

## Audio processing

See [platform audio paths](audio-processing.md#equalizer-and-gain) for tap support and HLS limits. AVPlayer retains the presentation clock, seeking, Now Playing, remote commands and audio-session ownership.

## Verification

Run shared parser/remux tests, including `WebmOpusTest` and `OpusMp4DecodeTest`, and the macOS [iOS checks](checks.md#platform-checks). Decode the fixture through AVFoundation and compare pre-skip, padding and peaks before integer conversion.

On iOS, check uncached Opus, AAC fallback, seeking, crossfades, track cancellation and offline priority. Bluetooth crackling and native decoder behavior require a device capture. Linux cannot compile or run the iOS targets with this configuration.

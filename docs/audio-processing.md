# Audio processing

## Equalizer and gain

MetroEQ uses shared profiles and DSP under `shared`. Platform players apply those profiles through their PCM paths. Output volume carries attenuation and crossfade fades; do not apply crossfade gain before silence skipping or tempo processing.

MetroEQ band gains and AutoEQ imports support -30 to +30 dB. The separate output preamp supports -12 to +12 dB. The graph shows the full band range plus preamp and holds its scale during a drag. Frequency edits stay within 20 Hz to 20 kHz, or the lower sample-rate ceiling, and stop at neighbouring bands. The editor lists bands by frequency while retaining their storage indices for reset and DSP history. Enabling MetroEQ retains the selected profile and live edits, or selects Flat when no profile is selected.

- Android uses Media3 audio processors and PCM16. Eligible compressed streams can offload when software effects, tempo, pitch and meter use do not require PCM. Libopus always decodes to PCM. An output error or playback stall can disable offload for the player lifetime and recover on PCM.
- Desktop decodes through FFmpeg. `AudioRenderer` applies EQ and gain before silence skipping, then normalization and user volume at output.
- iOS attaches `MTAudioProcessingTap` through `AVAudioMix` before starting a prepared player. Each tap owns its processing state. HLS fallback bypasses the tap, so MetroEQ, independent pitch and the meter are unavailable for that stream. Speed remains available.

Android's System equalizer opens the installed audio-effects panel for the current audio session. Each new player opens its own effects session; releasing an outgoing player closes only that session. Panel availability depends on the device and route. Changing hardware audio processing requires restart because the sink reads it at construction.

## Tempo and pitch

Keep speed and pitch independent. Android uses the software path when pitch requires it. Desktop uses `AudioTempoProcessor`; iOS uses AVPlayer's spectral algorithm for speed and a tap-owned NewTimePitch unit for independent pitch.

Pitch changes must reach both players during a crossfade. HLS cannot apply pitch-changing presets. Device Connect sends pitch only to peers advertising support; see [compatibility](device-connect.md#compatibility-and-queues).

## Silence and recovery

Android and desktop detect sustained silence before compressing it. Preserve the removed-frame accounting in playback clocks. Android instant silence skipping uses a separate detector and seek path; disabling it must stop detection work without changing ordinary PCM output.

Recovery budgets span replacement players. Preparing a candidate does not reset the budget. Preserve the current position and playback intent, and keep a warm candidate muted until promotion. Intentional crossfades retain their overlap and gain ramps.

Android Bluetooth recovery observes the actual AudioTrack route and available output signals. Pauses, buffering, muted output and transitions defer recovery. Do not infer audible failure from source silence or missing Bluetooth permission. Validate route recovery on real hardware.

## Meter

The shared 18-band meter captures PCM only while the MetroEQ page requests it. Analysis runs outside the audio callback. Hiding the page stops capture and clears display state. Preserve stereo energy when combining channels.

## Verification

Run affected [platform checks](checks.md#platform-checks), including `AudioTempoProcessorTest` for desktop tempo changes. Check audible output on each affected route with effects on and off, speed/pitch changes, silence skipping, crossfade and interruption. iOS HLS needs a separate check for its unavailable-processing state. Backend tests cannot verify offload, Bluetooth output or tap installation on devices.

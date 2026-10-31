package ultra.hooks

import androidx.media3.common.audio.AudioProcessor
import okhttp3.OkHttpClient

/*
 * Seam contracts. One interface per upstream seam; ultra features implement the ones they need
 * and contribute them through Hilt multibindings (see :ultra-wiring). Everything here is plain
 * Kotlin/Android types: no upstream class is referenced, so feature modules stay independent of :app.
 *
 * Contract rules for implementors:
 *  - Be fast and non-blocking. configure()/create() run on whatever thread builds the client,
 *    player or screen list, and resolve() runs on the playback-resolution coroutine.
 *  - Throwing is survivable (UltraHooks isolates every call) but the contribution is lost.
 */

/** S4: tweak the OkHttp client that backs InnerTube's Ktor engine (interceptors, DNS, proxy, ...). */
fun interface ApiClientContributor {
    fun configure(builder: OkHttpClient.Builder)
}

/** S5: tweak the OkHttp client that fetches audio bytes for ExoPlayer. */
fun interface StreamClientContributor {
    fun configure(builder: OkHttpClient.Builder)
}

/**
 * S6: extra audio processors for the ExoPlayer audio sink.
 *
 * [create] is called once per audio sink, and crossfade runs two players, so it MUST return
 * fresh instances every time. AudioProcessors are stateful and cannot be shared between sinks.
 * Processors run after upstream's normalization, equalizer and silence processors, and before
 * ExoPlayer's built-in silence-skipping and speed/pitch (Sonic) stages, which DefaultAudioSink
 * always applies last.
 */
fun interface AudioProcessorsContributor {
    fun create(): List<AudioProcessor>
}

/**
 * S7: gets the first chance to resolve a stream before YouTube is asked.
 * Return [StreamResolution.Pass] to leave the track to upstream.
 *
 * Bound your own network calls with `withTimeoutOrNull`, never `withTimeout`: a
 * TimeoutCancellationException is a CancellationException, and cancellation is deliberately
 * not swallowed by UltraHooks (it would cancel the caller's playback resolution).
 */
fun interface StreamResolverContributor {
    suspend fun resolve(request: StreamRequest): StreamResolution
}

/** S8: screens added to the app's navigation graph. */
fun interface DestinationContributor {
    fun destinations(): List<UltraDestination>
}

/** S9: entries added to the main Settings screen. */
fun interface SettingsEntryContributor {
    fun entries(): List<UltraSettingsEntry>
}

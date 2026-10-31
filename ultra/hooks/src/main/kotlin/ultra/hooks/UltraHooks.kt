package ultra.hooks

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.media3.common.audio.AudioProcessor
import okhttp3.OkHttpClient
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only object upstream code talks to. Every seam in an upstream file is a one-line call into
 * this object (see ultra/seams.txt).
 *
 * Two guarantees make the seams safe to leave in place permanently:
 *  1. No-op defaults. Until [install] is called (or if :ultra-wiring is absent) every method
 *     returns the neutral value, so upstream behaves exactly as if the seam did not exist.
 *  2. Fault isolation. A contributor that throws is logged and skipped; it can never take down
 *     an upstream code path (client construction, audio sink creation, playback resolution, UI).
 *     Only coroutine cancellation and JVM-fatal errors (OOM, ...) propagate.
 */
object UltraHooks {
    private const val TAG = "UltraHooks"

    @Volatile
    private var registry: HookRegistry = HookRegistry.EMPTY

    val isInstalled: Boolean
        get() = registry !== HookRegistry.EMPTY

    /** Called once by :ultra-wiring's initializer. Replaces any previous registry. */
    fun install(registry: HookRegistry) {
        this.registry = registry
    }

    @VisibleForTesting
    internal fun uninstall() {
        registry = HookRegistry.EMPTY
    }

    fun stats(): HookStats {
        val current = registry
        return HookStats(
            installed = current !== HookRegistry.EMPTY,
            apiClient = current.apiClient.size,
            streamClient = current.streamClient.size,
            audioProcessors = current.audioProcessors.size,
            streamResolvers = current.streamResolvers.size,
            destinations = current.destinations.size,
            settingsEntries = current.settingsEntries.size,
        )
    }

    /* ---- S4 ---- */
    fun configureApiClient(builder: OkHttpClient.Builder) {
        registry.apiClient.forEach { guarded("apiClient", Unit) { it.configure(builder) } }
    }

    /* ---- S5 ---- */
    fun configureStreamClient(builder: OkHttpClient.Builder) {
        registry.streamClient.forEach { guarded("streamClient", Unit) { it.configure(builder) } }
    }

    /* ---- S6 ---- */
    /** Fresh instances on every call; see [AudioProcessorsContributor]. */
    fun audioProcessors(): Array<AudioProcessor> {
        val processors = ArrayList<AudioProcessor>()
        registry.audioProcessors.forEach { contributor ->
            guarded("audioProcessors", Unit) { processors += contributor.create() }
        }
        return processors.toTypedArray()
    }

    /* ---- S7 ---- */
    /** First non-[StreamResolution.Pass] answer wins; a resolver that throws counts as Pass. */
    suspend fun resolveStream(request: StreamRequest): StreamResolution {
        for (resolver in registry.streamResolvers) {
            val resolution = guarded("streamResolvers", StreamResolution.Pass) { resolver.resolve(request) }
            if (resolution !is StreamResolution.Pass) return resolution
        }
        return StreamResolution.Pass
    }

    /* ---- S8 ---- */
    fun destinations(): List<UltraDestination> =
        registry.destinations
            .flatMap { contributor -> guarded("destinations", emptyList()) { contributor.destinations() } }
            .distinctBy { it.route }

    /* ---- S9 ---- */
    fun settingsEntries(): List<UltraSettingsEntry> =
        registry.settingsEntries
            .flatMap { contributor -> guarded("settingsEntries", emptyList()) { contributor.entries() } }
            .distinctBy { it.route }

    private inline fun <T> guarded(seam: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError) throw e
            Log.e(TAG, "Contributor failed at seam '$seam'; its contribution is skipped", e)
            fallback
        }
}

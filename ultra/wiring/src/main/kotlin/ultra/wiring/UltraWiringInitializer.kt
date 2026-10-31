package ultra.wiring

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.startup.Initializer
import dagger.hilt.android.EntryPointAccessors
import ultra.hooks.HookRegistry
import ultra.hooks.UltraHooks
import ultra.wiring.di.UltraHookSets
import java.io.File

/**
 * Folds the Hilt-provided contributor sets into [UltraHooks].
 *
 * androidx.startup's InitializationProvider is a ContentProvider, so this runs during process
 * start, before Application.onCreate and before any service, activity or ExoPlayer exists. That is
 * what lets seams S4-S6 (client and audio sink construction) see the contributors on first use.
 * Hilt's application component is created lazily by the first entry-point access, so reaching it
 * from here is safe.
 *
 * The initializer is registered through :ultra-wiring's own manifest; :app's manifest is untouched.
 */
class UltraWiringInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext
        // The :crash process (and any other secondary process) runs no player or UI, and
        // building the Hilt graph there would only waste startup time.
        if (!isMainProcess(app)) return

        try {
            val sets = EntryPointAccessors.fromApplication(app, UltraHookSets::class.java)
            UltraHooks.install(
                HookRegistry(
                    apiClient = sets.apiClient(),
                    streamClient = sets.streamClient(),
                    audioProcessors = sets.audioProcessors(),
                    streamResolvers = sets.streamResolvers(),
                    destinations = sets.destinations(),
                    settingsEntries = sets.settingsEntries(),
                ),
            )
        } catch (e: Exception) {
            // Never block app startup: with nothing installed, every seam keeps its no-op default.
            Log.e(TAG, "Ultra extension layer disabled: could not build the hook registry", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    private fun isMainProcess(app: Context): Boolean {
        val processName =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                runCatching { File("/proc/self/cmdline").readText().trim { it == '\u0000' } }.getOrNull()
            }
        // Fail open: if the name is unavailable, initialize rather than silently disable the layer.
        return processName == null || processName == app.packageName
    }

    private companion object {
        const val TAG = "UltraWiring"
    }
}

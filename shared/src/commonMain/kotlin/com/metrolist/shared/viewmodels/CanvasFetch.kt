package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun CoroutineScope.launchCanvasFetch(block: suspend CoroutineScope.() -> Unit) =
    launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w("Canvas", "Canvas lookup failed", error)
        }
    }

internal suspend fun loadCanvasSources(
    spotify: suspend () -> String?,
    apple: suspend () -> String?,
    onResult: (CanvasContentSource, String?) -> Unit,
): Pair<String?, String?> =
    coroutineScope {
        suspend fun load(
            source: CanvasContentSource,
            block: suspend () -> String?,
        ): String? {
            val result =
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Logger.w("Canvas", "Artwork source lookup failed", error)
                    null
                }
            onResult(source, result)
            return result
        }
        val spotifyResult = async { load(CanvasContentSource.SPOTIFY_CANVAS, spotify) }
        val appleResult = async { load(CanvasContentSource.APPLE_MUSIC, apple) }
        spotifyResult.await() to appleResult.await()
    }

internal suspend fun <T : Any> retryCanvasLookup(load: suspend () -> T?): T? {
    repeat(3) { attempt ->
        if (attempt > 0) delay(30_000L)
        try {
            return load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w("Canvas", "Canvas lookup failed", error)
        }
    }
    return null
}

internal class CanvasPlaybackRecovery {
    private var attempted = false

    fun reset() {
        attempted = false
    }

    suspend fun <T> recover(
        expired: Boolean = false,
        reportFailure: suspend () -> Unit,
        refresh: suspend () -> T?,
    ): T? {
        val refreshAllowed = !attempted
        attempted = true
        if (!expired) reportFailure()
        return if (refreshAllowed) refresh() else null
    }
}

internal class CanvasFetchRequest(
    private val scope: CoroutineScope,
) {
    private var visible = false
    private var key: String? = null
    private var job: Job? = null

    fun setVisible(value: Boolean) {
        visible = value
        if (!value && job?.isCompleted == false) invalidate()
    }

    fun launch(
        key: String,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (!visible || this.key == key) return
        invalidate()
        this.key = key
        job = scope.launchCanvasFetch(block)
    }

    fun invalidate() {
        job?.cancel()
        job = null
        key = null
    }

    suspend fun join() {
        job?.join()
    }
}

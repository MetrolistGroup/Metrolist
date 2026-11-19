package com.metrolist.android.ui.player

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.utils.PreparedLyricsLocalVideo
import com.metrolist.android.utils.prepareLyricsLocalVideo
import com.metrolist.shared.ui.components.lyrics.LyricsVideoBackground
import com.metrolist.shared.ui.components.lyrics.LyricsVideoBackgroundLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private class AndroidLyricsVideoBackground(private val prepared: PreparedLyricsLocalVideo) : LyricsVideoBackground {
    override val durationMs: Long get() = prepared.durationMs

    override fun acquireLease(): LyricsVideoBackgroundLease {
        val lease = prepared.acquireLease()
        return object : LyricsVideoBackgroundLease {
            override fun withFrame(positionMs: Long, block: (ImageBitmap) -> Unit) {
                lease.withFrame(positionMs) { block(it.asImageBitmap()) }
            }

            override fun close() = lease.close()
        }
    }

    fun close() = prepared.close()
}

internal class LyricsVideoPickerState {
    private var owner: AndroidLyricsVideoBackground? = null
    val background: LyricsVideoBackground? get() = selectedBackground
    private var selectedBackground by mutableStateOf<LyricsVideoBackground?>(null)
    var preparing by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var pick: () -> Unit = {}
    private var generation = 0L
    private var job: Job? = null
    private var disposed = false

    fun prepare(context: Context, uri: Uri, scope: CoroutineScope) {
        if (disposed) return
        val token = ++generation
        job?.cancel()
        preparing = true
        failed = false
        job = scope.launch {
            var pending: PreparedLyricsLocalVideo? = null
            try {
                pending = prepareLyricsLocalVideo(context, uri)
                ensureActive()
                if (disposed || generation != token) return@launch
                val replacement = AndroidLyricsVideoBackground(pending)
                pending = null
                val previous = owner
                owner = replacement
                selectedBackground = replacement
                previous?.close()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Provider exceptions may contain private URIs. Expose only a generic failure state.
                if (!disposed && generation == token) failed = true
            } finally {
                pending?.close()
                if (!disposed && generation == token) preparing = false
            }
        }
    }

    fun reportPickerFailure() {
        if (!disposed) failed = true
    }

    fun remove() {
        generation++
        job?.cancel()
        job = null
        selectedBackground = null
        owner?.close()
        owner = null
        preparing = false
        failed = false
    }

    fun dispose() {
        disposed = true
        remove()
        pick = {}
    }
}

/** Keep this call at a stable composition location, keyed by the frozen share request. */
@Composable
internal fun rememberLyricsVideoPicker(sessionKey: Any?): LyricsVideoPickerState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val state = remember(sessionKey) { LyricsVideoPickerState() }
    // A late system result belongs to the editor that launched it, never a replacement editor.
    var pickerOwner by remember { mutableStateOf<LyricsVideoPickerState?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pickerOwner
        pickerOwner = null
        if (uri != null && target === state && sessionKey != null) target.prepare(context, uri, scope)
    }
    state.pick = {
        if (sessionKey != null && pickerOwner == null) {
            pickerOwner = state
            try {
                launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            } catch (_: Exception) {
                pickerOwner = null
                state.reportPickerFailure()
            }
        }
    }
    DisposableEffect(state) {
        onDispose { state.dispose() }
    }
    return state
}

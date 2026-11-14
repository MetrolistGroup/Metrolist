package com.metrolist.android.ui.components

// All transitions run on the artwork player's looper.
internal class CanvasPlaybackRecovery(
    private val initialSource: CanvasPlaybackSource,
    private var fallback: CanvasPlaybackSource?,
    private var preparingClips: Boolean,
    private val prepareSource: (CanvasPlaybackSource) -> Boolean,
    private val stopPlayback: () -> Unit,
    private val onError: () -> Unit,
) {
    var failed = false
        private set

    private var waitingForClips = false

    val isPlaybackActive: Boolean
        get() = !failed && !waitingForClips

    fun start() = play(initialSource)

    fun onPlaybackError() {
        if (failed || waitingForClips) return
        stopPlayback()
        val recovery = fallback
        fallback = null
        when {
            recovery != null -> play(recovery)
            preparingClips -> waitingForClips = true
            else -> fail()
        }
    }

    fun onPreparationFinished(prepared: CanvasPlaybackSource?) {
        if (failed || !preparingClips) return
        preparingClips = false
        if (prepared != null) {
            fallback = initialSource.takeUnless { waitingForClips }
            waitingForClips = false
            play(prepared)
        } else if (waitingForClips) {
            fail()
        }
    }

    private fun play(source: CanvasPlaybackSource) {
        if (!prepareSource(source)) onPlaybackError()
    }

    private fun fail() {
        failed = true
        onError()
    }
}

package com.metrolist.android.recognition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

internal class RecognitionSessionController(
    private val scope: CoroutineScope,
    private val recognize: suspend () -> Unit,
    private val finish: () -> Unit,
) {
    private var recognition: Job? = null
    private var stopping: Job? = null
    private var pendingStart = false

    fun start() {
        if (stopping?.isActive == true) {
            pendingStart = true
        } else if (recognition?.isActive != true) {
            recognition =
                scope.launch {
                    recognize()
                    finish()
                }
        }
    }

    fun toggle() {
        if (recognition?.isActive == true || stopping?.isActive == true) stop() else start()
    }

    fun stop() {
        pendingStart = false
        if (stopping?.isActive == true) return
        val running = recognition
        stopping =
            scope.launch {
                running?.cancelAndJoin()
                recognition = null
                stopping = null
                if (pendingStart) {
                    pendingStart = false
                    start()
                } else {
                    finish()
                }
            }
    }
}

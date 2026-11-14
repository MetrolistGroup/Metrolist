package com.metrolist.android.playback

internal class PendingPlaybackCommands {
    private val commands = ArrayDeque<() -> Unit>()
    var ready = false
        private set

    fun defer(command: () -> Unit): Boolean {
        if (ready) return false
        commands.addLast(command)
        return true
    }

    fun connected() {
        ready = true
        while (ready && commands.isNotEmpty()) commands.removeFirst().invoke()
    }

    fun disconnected() {
        ready = false
    }

    fun clear() = commands.clear()
}

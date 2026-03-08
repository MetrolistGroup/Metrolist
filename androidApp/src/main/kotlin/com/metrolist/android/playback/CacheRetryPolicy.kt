package com.metrolist.android.playback

internal class CacheRetryPolicy(
    private val now: () -> Long,
) {
    private data class Failure(
        val attempts: Int,
        val retryAt: Long,
    )

    private val failures = linkedMapOf<String, Failure>()

    fun canAttempt(id: String): Boolean = failures[id]?.let { it.attempts < 3 && now() >= it.retryAt } ?: true

    fun failed(id: String) {
        failures[id] = Failure((failures[id]?.attempts ?: 0) + 1, now() + 60_000L)
        while (failures.size > 64) failures.remove(failures.keys.first())
    }

    fun succeeded(id: String) {
        failures.remove(id)
    }

    fun reset() = failures.clear()
}

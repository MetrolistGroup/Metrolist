/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kidj

import kotlin.math.abs
import kotlin.math.min

data class Wish(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val guests: MutableSet<String> = linkedSetOf(),
) {
    val votes get() = guests.size
}

enum class WishResult { ADDED, VOTED, ALREADY_VOTED, LIMIT_REACHED }

/** Pure DJ decision logic: guest wishes, learned floor reaction per artist, rescue rule. */
class DjBrain(
    private val maxOpenPerGuest: Int = 3,
) {
    private val wishes = LinkedHashMap<String, Wish>()
    private val reaction = HashMap<String, Double>()

    @Synchronized
    fun addWish(id: String, title: String, artist: String, thumbnail: String?, guest: String): WishResult {
        wishes[id]?.let { return if (it.guests.add(guest)) WishResult.VOTED else WishResult.ALREADY_VOTED }
        if (wishes.values.count { guest in it.guests } >= maxOpenPerGuest) return WishResult.LIMIT_REACHED
        wishes[id] = Wish(id, title, artist, thumbnail, linkedSetOf(guest))
        return WishResult.ADDED
    }

    @Synchronized
    fun vote(id: String, guest: String): Boolean = wishes[id]?.guests?.add(guest) ?: false

    @Synchronized
    fun remove(id: String) {
        wishes.remove(id)
    }

    @Synchronized
    fun wishes(floor: Double, currentArtist: String?): List<Wish> =
        wishes.values.sortedByDescending { score(it, floor, currentArtist) }.map { it.copy(guests = it.guests.toMutableSet()) }

    /** Takes the wish that fits best right now out of the list. */
    @Synchronized
    fun pickNext(floor: Double, currentArtist: String?): Wish? =
        wishes.values.maxByOrNull { score(it, floor, currentArtist) }?.also { wishes.remove(it.id) }

    fun score(w: Wish, floor: Double, currentArtist: String?): Double {
        val learned = reaction[w.artist.lowercase()] ?: 0.5
        // An empty floor makes proven crowd pleasers count more than raw votes.
        val trust = if (floor < LOW_FLOOR * 2) 4.0 else 1.5
        val sameArtist = currentArtist != null && w.artist.equals(currentArtist, ignoreCase = true)
        return w.votes + (learned - 0.5) * trust - (if (sameArtist) 0.5 else 0.0)
    }

    /** Stores how full the floor was while an artist played (moving average, 0..1). */
    @Synchronized
    fun learn(artist: String, floorEnergy: Double) {
        val key = artist.lowercase()
        reaction[key] = (reaction[key] ?: 0.5) * 0.7 + floorEnergy.coerceIn(0.0, 1.0) * 0.3
    }

    @Synchronized
    fun reactions(): Map<String, Double> = HashMap(reaction)

    @Synchronized
    fun restoreReactions(map: Map<String, Double>) {
        reaction.putAll(map)
    }

    companion object {
        const val LOW_FLOOR = 0.12
    }
}

/** Decides when an empty floor should trigger an early switch to the next song. */
class RescueWatch(
    private val lowForMs: Long = 90_000,
    private val cooldownMs: Long = 180_000,
) {
    private var lowSince = -1L
    private var lastRescue = -cooldownMs

    fun feed(floor: Double, nowMs: Long): Boolean {
        if (floor >= DjBrain.LOW_FLOOR) {
            lowSince = -1
            return false
        }
        if (lowSince < 0) lowSince = nowMs
        if (nowMs - lowSince < lowForMs || nowMs - lastRescue < cooldownMs) return false
        lastRescue = nowMs
        lowSince = -1
        return true
    }

    fun reset() {
        lowSince = -1
    }
}

/**
 * Movement on the dance floor from two grayscale frames: mean absolute pixel change,
 * scaled by [sensitivity] so a lively floor lands near 1.
 */
fun motionEnergy(prev: ByteArray, cur: ByteArray, sensitivity: Double, step: Int = 8): Double {
    val n = min(prev.size, cur.size)
    if (n == 0) return 0.0
    var sum = 0L
    var count = 0
    var i = 0
    while (i < n) {
        sum += abs((prev[i].toInt() and 0xFF) - (cur[i].toInt() and 0xFF))
        count++
        i += step
    }
    return min(1.0, sum.toDouble() / count / 255.0 * sensitivity)
}

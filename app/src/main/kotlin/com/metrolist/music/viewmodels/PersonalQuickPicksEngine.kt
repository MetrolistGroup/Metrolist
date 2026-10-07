/**
 * 7xTune Project (C) 2026
 * Personal Quick Picks engine.
 *
 * This intentionally does not consume YouTube's Home/Quick Picks shelf.
 * It combines 7xTune listening signals into one deterministic selection per load.
 */
package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.YTItem
import kotlin.random.Random

internal object PersonalQuickPicksEngine {
    private enum class Bucket {
        FAMILIAR,
        RELATED,
        LIKED,
        DISCOVERY,
    }

    fun build(
        familiar: List<YTItem>,
        related: List<YTItem>,
        liked: List<YTItem>,
        discovery: List<YTItem>,
        seed: Long,
        limit: Int = 20,
    ): List<YTItem> {
        if (limit <= 0) return emptyList()

        val random = Random(seed)
        val pools =
            mapOf(
                Bucket.FAMILIAR to familiar.distinctBy { it.id }.shuffled(random).toMutableList(),
                Bucket.RELATED to related.distinctBy { it.id }.shuffled(random).toMutableList(),
                Bucket.LIKED to liked.distinctBy { it.id }.shuffled(random).toMutableList(),
                Bucket.DISCOVERY to discovery.distinctBy { it.id }.shuffled(random).toMutableList(),
            )

        val result = mutableListOf<YTItem>()
        val used = mutableSetOf<String>()

        // Keep the shelf feeling mixed rather than grouping all favourites/similar songs together.
        val pattern =
            buildList {
                repeat(limit) { index ->
                    add(
                        when (index % 10) {
                            0, 2, 6 -> Bucket.FAMILIAR
                            1, 4, 7 -> Bucket.RELATED
                            3, 8 -> Bucket.LIKED
                            else -> Bucket.DISCOVERY
                        },
                    )
                }
            }

        fun takeFrom(bucket: Bucket): Boolean {
            val pool = pools.getValue(bucket)
            while (pool.isNotEmpty()) {
                val item = pool.removeAt(0)
                if (used.add(item.id)) {
                    result += item
                    return true
                }
            }
            return false
        }

        pattern.forEach { requestedBucket ->
            if (result.size >= limit) return@forEach
            if (!takeFrom(requestedBucket)) {
                Bucket.entries.firstOrNull { takeFrom(it) }
            }
        }

        // Sparse histories should still produce a full shelf whenever candidates exist.
        if (result.size < limit) {
            val remaining =
                Bucket.entries
                    .flatMap { pools.getValue(it) }
                    .filter { it.id !in used }
                    .distinctBy { it.id }
                    .shuffled(random)

            result += remaining.take(limit - result.size)
        }

        return result.take(limit)
    }
}

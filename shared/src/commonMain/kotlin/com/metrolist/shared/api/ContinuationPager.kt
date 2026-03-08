package com.metrolist.shared.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ParsedPage<T>(
    val items: List<T>,
    val continuation: String?,
    val continuationAmbiguous: Boolean = false,
)

internal data class PaginationResult<T>(
    val items: List<T>,
    val complete: Boolean,
)

internal data class BranchingParsedPage<T>(
    val items: List<T>,
    val continuations: Set<ContinuationBranch>,
)

internal data class ContinuationBranch(
    val token: String,
    val sectionTitle: String? = null,
)

internal fun String?.normalizedContinuation(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

internal suspend fun <T> collectPages(
    initial: ParsedPage<T>,
    maxRequests: Int = 50,
    loadNext: suspend (String) -> ParsedPage<T>,
): PaginationResult<T> {
    val items = initial.items.toMutableList()
    if (initial.continuationAmbiguous) return PaginationResult(items, complete = false)
    val seen = mutableSetOf<String>()
    var continuation = initial.continuation.normalizedContinuation()
    var requests = 0
    while (continuation != null) {
        if (!seen.add(continuation) || requests >= maxRequests) {
            return PaginationResult(items, complete = false)
        }
        val page =
            try {
                loadNext(continuation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return PaginationResult(items, complete = false)
            }
        requests++
        items += page.items
        if (page.continuationAmbiguous) return PaginationResult(items, complete = false)
        continuation = page.continuation.normalizedContinuation()
    }
    return PaginationResult(items, complete = true)
}

internal suspend fun <T> collectPageBranches(
    initial: BranchingParsedPage<T>,
    maxRequests: Int = 50,
    loadNext: suspend (ContinuationBranch) -> BranchingParsedPage<T>,
): PaginationResult<T> {
    val items = initial.items.toMutableList()
    val pending = ArrayDeque<ContinuationBranch>()
    val scheduled = mutableSetOf<String>()
    val edges = mutableMapOf<String, MutableSet<String>>()
    initial.continuations.forEach { branch ->
        branch.token.normalizedContinuation()?.let { token ->
            if (scheduled.add(token)) pending.addLast(branch.copy(token = token))
        }
    }
    val seen = mutableSetOf<String>()
    var requests = 0

    while (pending.isNotEmpty()) {
        val batch = mutableListOf<ContinuationBranch>()
        while (pending.isNotEmpty() && batch.size < 4 && requests + batch.size < maxRequests) {
            val branch = pending.removeFirst()
            if (seen.add(branch.token)) {
                batch.add(branch)
            }
        }
        if (batch.isEmpty()) break
        requests += batch.size

        val results =
            coroutineScope {
                batch
                    .map { branch ->
                        async {
                            branch to
                                try {
                                    loadNext(branch)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                        }
                    }.awaitAll()
            }

        for ((branch, page) in results) {
            if (page == null) return PaginationResult(items, complete = false)
            items += page.items
            for (nextBranch in page.continuations) {
                val next = nextBranch.token.normalizedContinuation() ?: continue
                edges.getOrPut(branch.token) { mutableSetOf() } += next
                if (scheduled.add(next)) pending.addLast(nextBranch.copy(token = next))
            }
        }
    }

    if (pending.isNotEmpty()) return PaginationResult(items, complete = false)

    val incoming = scheduled.associateWith { 0 }.toMutableMap()
    edges.values.forEach { targets -> targets.forEach { incoming[it] = incoming.getValue(it) + 1 } }
    val roots = ArrayDeque(incoming.filterValues { it == 0 }.keys)
    var visited = 0
    while (roots.isNotEmpty()) {
        val token = roots.removeFirst()
        visited++
        edges[token].orEmpty().forEach { next ->
            val remaining = incoming.getValue(next) - 1
            incoming[next] = remaining
            if (remaining == 0) roots.addLast(next)
        }
    }
    return PaginationResult(items, complete = visited == scheduled.size)
}

internal fun JsonElement?.findContinuationToken(): String? {
    val tokens = mutableSetOf<String>()
    collectContinuationTokens(tokens, limit = 2)
    return tokens.singleOrNull()
}

internal fun JsonElement?.findContinuationTokens(): Set<String> {
    val tokens = mutableSetOf<String>()
    collectContinuationTokens(tokens)
    return tokens
}

private fun JsonElement?.collectContinuationTokens(
    tokens: MutableSet<String>,
    limit: Int = Int.MAX_VALUE,
) {
    if (tokens.size >= limit) return
    when (this) {
        null -> {
            return
        }

        is JsonArray -> {
            forEach { if (tokens.size >= limit) return else it.collectContinuationTokens(tokens, limit) }
        }

        is JsonObject -> {
            this["nextContinuationData"]
                ?.jsonObject
                ?.get("continuation")
                ?.jsonPrimitive
                ?.contentOrNull
                .normalizedContinuation()
                ?.let(tokens::add)
            this["continuationCommand"]
                ?.jsonObject
                ?.get("token")
                ?.jsonPrimitive
                ?.contentOrNull
                .normalizedContinuation()
                ?.let(tokens::add)
            values.forEach { if (tokens.size >= limit) return else it.collectContinuationTokens(tokens, limit) }
        }

        else -> {
            return
        }
    }
}

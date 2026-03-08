package com.metrolist.android.recognition

import android.util.LruCache
import com.metrolist.shared.recognition.RecognitionResult

internal class RecognitionResultCache {
    private val entries = LruCache<String, Pair<Long, RecognitionResult>>(32)

    @Synchronized
    fun get(
        signature: String,
        now: Long,
    ): RecognitionResult? {
        val (createdAt, result) = entries.get(signature) ?: return null
        if (now - createdAt >= 5 * 60 * 1000L) {
            entries.remove(signature)
            return null
        }
        return result
    }

    @Synchronized
    fun put(
        signature: String,
        result: RecognitionResult,
        now: Long,
    ) {
        entries.put(signature, now to result)
    }
}

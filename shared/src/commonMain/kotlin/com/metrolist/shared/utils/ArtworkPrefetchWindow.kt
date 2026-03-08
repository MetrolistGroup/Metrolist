package com.metrolist.shared.utils

class ArtworkPrefetchWindow<T>(
    private val enqueue: (String) -> T,
    private val dispose: (T) -> Unit,
) {
    private val requests = mutableMapOf<String, T>()

    fun update(urls: List<String>) {
        val wanted = urls.toSet()
        val departing = requests.keys.filterNot { it in wanted }
        departing.forEach { requests.remove(it)?.let(dispose) }
        urls.forEach { url -> requests.getOrPut(url) { enqueue(url) } }
    }

    fun clear() {
        requests.values.forEach(dispose)
        requests.clear()
    }
}

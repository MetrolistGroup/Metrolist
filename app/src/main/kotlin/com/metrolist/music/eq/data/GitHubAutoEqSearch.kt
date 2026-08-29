package com.metrolist.music.eq.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub-backed search interface for AutoEq data.
 *
 * Uses the GitHub Trees API to build the index from file paths,
 * then fetches individual files (ParametricEQ.txt, name_index.tsv) on demand.
 * All fetched data is cached locally in the app's internal storage.
 */
class GitHubAutoEqSearch(private val context: Context) {

    private val entries = mutableListOf<Entry>()
    private var isIndexed = false

    // Cache for name_index.tsv data: Map<source, Map<headphoneName, rig>>
    private val rigLookupCache = mutableMapOf<String, Map<String, String>>()

    private val cacheDir = File(context.filesDir, "autoeq_cache")
    private val treeFile = File(cacheDir, "tree.json")
    private val treeTimestampFile = File(cacheDir, "tree_timestamp")

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "GitHubAutoEqSearch"
        private const val REPO_OWNER = "ndellagrotte"
        private const val REPO_NAME = "AutoEq"
        private const val BRANCH = "master"
        private const val TREE_API_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/git/trees/$BRANCH?recursive=1"
        private const val RAW_BASE_URL =
            "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$BRANCH"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    @Serializable
    private data class GitHubTreeResponse(
        val sha: String,
        val tree: List<GitHubTreeNode>,
        val truncated: Boolean = false
    )

    @Serializable
    private data class GitHubTreeNode(
        val path: String,
        val mode: String? = null,
        val type: String,
        val sha: String? = null,
        val size: Long? = null
    )

    /**
     * Check whether a cached tree file exists (i.e. the database has been downloaded before).
     */
    fun isDatabaseCached(): Boolean = treeFile.exists()

    /**
     * Build the search index by fetching the GitHub repo tree.
     * Uses cached tree if available and not stale (<24h old).
     */
    suspend fun buildIndex(): Boolean = withContext(Dispatchers.IO) {
        try {
            cacheDir.mkdirs()

            val treeJson = getCachedOrFetchTree() ?: return@withContext false
            val treeResponse = json.decodeFromString<GitHubTreeResponse>(treeJson)

            if (treeResponse.truncated) {
                Timber.tag(TAG).w("GitHub tree response was truncated, some entries may be missing")
            }

            // Filter to only ParametricEQ.txt blobs under results/
            val eqNodes = treeResponse.tree.filter { node ->
                node.type == "blob" &&
                        node.path.startsWith("results/") &&
                        node.path.endsWith(" ParametricEQ.txt")
            }

            val newEntries = mutableListOf<Entry>()
            for (node in eqNodes) {
                try {
                    val entry = parseEntryFromPath(node.path) ?: continue
                    newEntries.add(entry)
                } catch (e: Exception) {
                    // Skip entries that can't be parsed
                }
            }

            entries.clear()
            entries.addAll(newEntries)
            isIndexed = true
            Timber.tag(TAG).d("Indexed ${entries.size} entries from GitHub tree")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to build index")
            false
        }
    }

    /**
     * Parse an Entry from a GitHub tree path.
     * Expected: results/{source}/{formRig}/{headphoneName}/{headphoneName} ParametricEQ.txt
     */
    private fun parseEntryFromPath(path: String): Entry? {
        val parts = path.split("/")
        // parts[0] = "results"
        // parts[1] = source (e.g., "crinacle")
        // parts[2] = formRig directory (e.g., "711 in-ear")
        // parts[3] = headphone name (e.g., "Sony WF-1000XM4")
        // parts[4] = filename
        if (parts.size < 5) return null

        val source = parts[1]
        val formRigDir = parts[2]
        val headphoneName = parts[3]

        val (rigFromDir, parsedForm) = parseFormAndRig(formRigDir)

        val finalRig = determineRig(
            rigFromDirectory = rigFromDir,
            source = source,
            headphoneName = headphoneName
        )

        return Entry(
            label = headphoneName,
            form = parsedForm,
            rig = finalRig,
            source = source,
            formDirectory = formRigDir
        )
    }

    /**
     * Returns cached tree JSON if fresh, otherwise fetches from GitHub API.
     */
    private fun getCachedOrFetchTree(): String? {
        // Try cached version first
        if (treeFile.exists() && treeTimestampFile.exists()) {
            val timestamp = treeTimestampFile.readText().trim().toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - timestamp < CACHE_TTL_MS) {
                return treeFile.readText()
            }
        }

        // Fetch from GitHub
        return try {
            val request = Request.Builder()
                .url(TREE_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2026-03-10")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("GitHub API returned ${response.code}: ${response.message}")
                    // Fall back to stale cache if available
                    if (treeFile.exists()) return treeFile.readText()
                    return null
                }

                val body = response.body?.string() ?: return null
                treeFile.writeText(body)
                treeTimestampFile.writeText(System.currentTimeMillis().toString())
                body
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch tree from GitHub")
            // Fall back to stale cache if available
            if (treeFile.exists()) return treeFile.readText()
            null
        }
    }

    /**
     * Fetch a raw file from GitHub, with local caching.
     */
    private fun fetchRawFile(repoPath: String): String? {
        // Check local cache (same TTL as tree.json)
        val cacheFile = File(cacheDir, repoPath.replace("/", File.separator))
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS) {
            return cacheFile.readText()
        }

        // Fetch from GitHub
        return try {
            val url = "$RAW_BASE_URL/${repoPath.encodePathSegments()}"
            val request = Request.Builder().url(url).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Failed to fetch %s: %d", repoPath, response.code)
                    // Fall back to stale cache if available
                    if (cacheFile.exists()) return cacheFile.readText()
                    return null
                }

                val body = response.body?.string() ?: return null
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(body)
                body
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch %s", repoPath)
            // Fall back to stale cache if available
            if (cacheFile.exists()) return cacheFile.readText()
            null
        }
    }

    /**
     * URL-encode path segments while preserving slashes.
     */
    private fun String.encodePathSegments(): String {
        return split("/").joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8")
                .replace("+", "%20")
        }
    }

    // SEARCH / FILTER METHODS

    /**
     * Search for models by label substring.
     * Returns a map of normalized model name -> entries for that model,
     * ordered by relevance (exact match > prefix match > substring match).
     * An empty query returns all models.
     */
    fun searchModels(query: String, maxResults: Int = 100): Map<String, List<Entry>> {
        if (!isIndexed) {
            Timber.tag(TAG).w("Index not built. Call buildIndex() first.")
            return emptyMap()
        }

        val lowerQuery = query.lowercase().trim()

        val filtered = if (lowerQuery.isBlank()) {
            entries
        } else {
            entries.filter { normalizeModelName(it.label).lowercase().contains(lowerQuery) }
        }

        val grouped = filtered.groupBy { normalizeModelName(it.label) }

        val sortedNames = grouped.keys.sortedWith(
            compareByDescending<String> { name ->
                val lower = name.lowercase()
                when {
                    lowerQuery.isEmpty() -> 0
                    lower == lowerQuery -> 2000
                    lower.startsWith(lowerQuery) -> 1000
                    else -> 100
                }
            }.thenBy { it }
        ).take(maxResults)

        return sortedNames.associateWith { grouped.getValue(it) }
    }

    /**
     * Get all entries for a specific headphone model.
     */
    fun getVariantsForModel(modelName: String): List<Entry> {
        val normalizedSearch = normalizeModelName(modelName)
        return entries.filter {
            normalizeModelName(it.label).equals(normalizedSearch, ignoreCase = true)
        }
    }

    /**
     * Load the parametric EQ for a selected entry.
     * Fetches from GitHub (with caching) and parses with ParametricEQParser.
     */
    suspend fun loadEQ(entry: Entry): ParametricEQ? = withContext(Dispatchers.IO) {
        try {
            val eqPath = getEQPath(entry)
            val content = fetchRawFile(eqPath)
                ?: return@withContext null
            ParametricEQParser.parseText(content)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load EQ for %s", entry.label)
            null
        }
    }

    /**
     * Get all indexed entries
     */
    fun getAllEntries(): List<Entry> = entries.toList()

    // PRIVATE HELPERS

    private fun normalizeModelName(modelName: String): String {
        return modelName.replace(Regex("""\s*\([^)]*\)\s*"""), "").trim()
    }

    /**
     * 3-step rig detection logic:
     * Step 1: Use rig from directory structure
     * Step 2: If unknown, look up in measurements/{source}/name_index.tsv
     * Step 3: If still unknown and source is special, use default rig
     */
    private fun determineRig(rigFromDirectory: String, source: String, headphoneName: String): String {
        if (rigFromDirectory != "unknown" && rigFromDirectory.isNotBlank()) {
            return rigFromDirectory
        }

        val rigFromIndex = lookupRigInNameIndex(source, headphoneName)
        if (rigFromIndex != null && rigFromIndex != "unknown") {
            return rigFromIndex
        }

        return when (source) {
            "Headphone.com Legacy", "Innerfidelity" -> "HMS II.3"
            else -> "unknown"
        }
    }

    private fun lookupRigInNameIndex(source: String, headphoneName: String): String? {
        try {
            if (!rigLookupCache.containsKey(source)) {
                loadNameIndexForSource(source)
            }
            return rigLookupCache[source]?.get(headphoneName)
        } catch (e: Exception) {
            return null
        }
    }

    private fun loadNameIndexForSource(source: String) {
        try {
            val indexPath = "measurements/$source/name_index.tsv"
            val content = fetchRawFile(indexPath)

            if (content == null) {
                rigLookupCache[source] = emptyMap()
                return
            }

            val rigMap = mutableMapOf<String, String>()
            val lines = content.lines()

            // Skip header line
            lines.drop(1).forEach { line ->
                val columns = line.split("\t")
                if (columns.size >= 5) {
                    val name = columns[2].trim()
                    val rig = columns[4].trim()

                    if (name.isNotBlank() && rig.isNotBlank() && rig != "ignore") {
                        rigMap[name] = rig
                    }
                }
            }

            rigLookupCache[source] = rigMap
        } catch (e: Exception) {
            rigLookupCache[source] = emptyMap()
        }
    }

    private fun parseFormAndRig(formRig: String): Pair<String, String> {
        val formKeywords = listOf("in-ear", "over-ear", "earbud")

        val foundForm = formKeywords.firstOrNull {
            formRig.contains(it, ignoreCase = true)
        } ?: "unknown"

        val rig = formRig.replace(foundForm, "", ignoreCase = true).trim()

        return Pair(rig.ifEmpty { "unknown" }, foundForm)
    }

    private fun getEQPath(entry: Entry): String {
        return "results/${entry.source}/${entry.formDirectory}/${entry.label}/${entry.label} ParametricEQ.txt"
    }
}

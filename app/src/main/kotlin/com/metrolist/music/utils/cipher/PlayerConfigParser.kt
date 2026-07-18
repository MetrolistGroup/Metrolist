package com.metrolist.music.utils.cipher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Parses and validates the player-config JSON (bundled asset and remote copies).
 *
 * Pure JVM on purpose: no Android or Timber imports, so the full validation surface is
 * coverable by plain unit tests. Android concerns (assets, cache files, fetching) live in
 * [PlayerConfigStore].
 *
 * Security boundary: every value in this file ends up evaluated as JavaScript inside the
 * cipher WebView, so entries are regex-locked to shapes that cannot carry arbitrary JS —
 * `sig` must be a single `name(int,int,INPUT)` call and `nClass` a bare identifier; the
 * n-transform IIFE is built locally from [buildNJsExpression], never taken from the file.
 */
object PlayerConfigParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    private val SIG_RE = Regex("""^[A-Za-z0-9${'$'}_]{1,8}\(\d+,\d+,INPUT\)$""")
    private val NCLASS_RE = Regex("""^[A-Za-z0-9${'$'}_]{1,8}$""")
    private val HASH_RE = Regex("""^[a-f0-9]{8}$""")

    sealed class ParseResult {
        data class Success(
            val configs: Map<String, FunctionNameExtractor.HardcodedPlayerConfig>,
            val skippedEntries: List<String>,
        ) : ParseResult()

        data class Failure(val reason: String) : ParseResult()
    }

    fun buildNJsExpression(nClass: String): String =
        "(function(n){try{var u=new g.$nClass('https://x.googlevideo.com/videoplayback?n='+n,true);" +
            "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)"

    /**
     * Parses [jsonText]. File-level problems (malformed JSON, missing/unsupported
     * schemaVersion, missing `players`) return [ParseResult.Failure] — callers keep their
     * previous map. Invalid individual entries are skipped and reported in
     * [ParseResult.Success.skippedEntries] so one bad entry can't poison the rest.
     */
    fun parse(jsonText: String): ParseResult {
        val root = try {
            Json.parseToJsonElement(jsonText) as? JsonObject
                ?: return ParseResult.Failure("root is not a JSON object")
        } catch (e: Exception) {
            return ParseResult.Failure("malformed JSON: ${e.message}")
        }

        // Non-string primitive only (like sts): a string-typed "1" must fail identically
        // here and in the harness loader, or the two readers drift on the same file.
        val schemaVersion = (root["schemaVersion"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content?.toIntOrNull()
            ?: return ParseResult.Failure("schemaVersion missing or not an int")
        if (schemaVersion <= 0) return ParseResult.Failure("schemaVersion must be positive")
        if (schemaVersion > SUPPORTED_SCHEMA_VERSION) {
            return ParseResult.Failure("unsupported schemaVersion $schemaVersion (supported: $SUPPORTED_SCHEMA_VERSION)")
        }

        val players = root["players"] as? JsonObject
            ?: return ParseResult.Failure("players missing or not an object")

        val configs = mutableMapOf<String, FunctionNameExtractor.HardcodedPlayerConfig>()
        val skipped = mutableListOf<String>()

        for ((hash, entryElement) in players) {
            val entry = parseEntry(hash, entryElement as? JsonObject)
            if (entry == null) {
                skipped += hash
                continue
            }
            val (config, aliases) = entry
            // A key collision (an alias duplicating another entry's hash or alias, or its
            // own primary) makes the table ambiguous — which entry wins would depend on
            // iteration order. That is a file-level defect, not a bad entry: reject the
            // whole file so callers keep their previous table.
            val keys = listOf(hash) + aliases
            val duplicate = keys.firstOrNull { it in configs }
                ?: keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
            if (duplicate != null) {
                return ParseResult.Failure("duplicate hash/alias '$duplicate' (entry $hash)")
            }
            configs[hash] = config
            for (alias in aliases) configs[alias] = config
        }

        return ParseResult.Success(configs, skipped)
    }

    private fun parseEntry(
        hash: String,
        obj: JsonObject?,
    ): Pair<FunctionNameExtractor.HardcodedPlayerConfig, List<String>>? {
        if (obj == null || !HASH_RE.matches(hash)) return null

        val sig = (obj["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!SIG_RE.matches(sig)) return null

        val nClass = (obj["nClass"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (!NCLASS_RE.matches(nClass)) return null

        val stsPrimitive = (obj["sts"] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        val sts = stsPrimitive.content.toIntOrNull() ?: return null
        if (sts <= 0) return null

        val aliases = when (val aliasesElement = obj["aliases"]) {
            null -> emptyList()
            else -> {
                val array = try {
                    aliasesElement.jsonArray
                } catch (e: Exception) {
                    return null
                }
                array.map { element ->
                    val alias = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                    if (!HASH_RE.matches(alias)) return null
                    alias
                }
            }
        }

        val config = FunctionNameExtractor.HardcodedPlayerConfig(
            sigFuncName = "_expr_sig",
            sigConstantArg = null,
            sigJsExpression = sig,
            nFuncName = "_expr_n",
            nArrayIndex = null,
            nConstantArgs = null,
            nJsExpression = buildNJsExpression(nClass),
            signatureTimestamp = sts,
        )
        return config to aliases
    }

    /** Overlay [remote] onto [bundled]: remote wins per key; bundled-only keys survive. */
    fun merge(
        bundled: Map<String, FunctionNameExtractor.HardcodedPlayerConfig>,
        remote: Map<String, FunctionNameExtractor.HardcodedPlayerConfig>,
    ): Map<String, FunctionNameExtractor.HardcodedPlayerConfig> = bundled + remote
}

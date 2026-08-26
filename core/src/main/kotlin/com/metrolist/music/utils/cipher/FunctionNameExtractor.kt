package com.metrolist.music.utils.cipher

import timber.log.Timber
import java.security.MessageDigest

/**
 * Extracts cipher function names from YouTube's player.js
 *
 * Handles both legacy patterns and modern Q-array obfuscation (2025+).
 * Falls back to hardcoded configs for known player.js hashes when regex fails.
 */
object FunctionNameExtractor {
    private const val TAG = "Metrolist_CipherFnExtract"

    // ==================== DATA CLASSES ====================

    data class SigFunctionInfo(
        val name: String,
        val constantArg: Int?, // The first numeric argument (e.g., 48 in JI(48, sig)) - legacy
        val constantArgs: List<Int>? = null, // All constant args e.g., JI(48, 1918, ...) -> [48, 1918]
        val preprocessFunc: String? = null, // Preprocessing function e.g., f1
        val preprocessArgs: List<Int>? = null, // Preprocess args e.g., f1(1, 6528, sig) -> [1, 6528]
        val jsExpression: String? = null,
        val isHardcoded: Boolean = false
    )

    data class NFunctionInfo(
        val name: String,
        val arrayIndex: Int?, // e.g. FUNC[0] -> index=0
        val constantArgs: List<Int>? = null, // e.g. GU(6, 6010, n) -> [6, 6010]
        val jsExpression: String? = null,
        val isHardcoded: Boolean = false
    )

    /**
     * Hardcoded player.js configuration for when regex extraction fails
     * Due to Q-array obfuscation, patterns like `.get("n")` become `Q[T^6001]`
     */
    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?, // Legacy single arg
        val sigConstantArgs: List<Int>? = null, // e.g. JI(48, 1918, ...) -> [48, 1918]
        val sigPreprocessFunc: String? = null, // e.g. f1
        val sigPreprocessArgs: List<Int>? = null, // e.g. f1(1, 6528, sig) -> [1, 6528]
        val sigJsExpression: String? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?, // e.g. GU(6, 6010, n) -> [6, 6010]
        val nJsExpression: String? = null,
        val signatureTimestamp: Int
    )

    // ==================== DETECTION PATTERNS ====================

    // See extractSignatureTimestamp for why these two are tried in different precedence tiers.
    private val ANCHORED_STS_PATTERN = Regex("""signatureTimestamp['":\s]+(\d+)""")
    private val LOOSE_STS_PATTERN = Regex("""sts['":\s]+(\d+)""")

    // Detect Q-array obfuscation: var Q="...".split("}")
    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    // Extract player hash from common patterns
    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['":\s]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    // Modern 2025+ signature deobfuscation function patterns
    private val SIG_FUNCTION_PATTERNS = listOf(
        // Pattern 1 (2025+): &&(VAR=FUNC(NUM,decodeURIComponent(VAR))
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        // Pattern 1a (April 2026): &&(z=hJ(6,decodeURIComponent(h.s))
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"""),
        // Classic patterns (pre-2025, kept as fallback)
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\bm=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(h\.s\)\)"""),
        Regex("""\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("""),
        Regex("""\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
    )

    // N-parameter (throttle) transform function patterns
    private val N_FUNCTION_PATTERNS = listOf(
        // Pattern 1: .get("n"))&&(b=FUNC[IDX](VAR)
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        // Pattern 2: .get("n"))&&(FUNC=VAR[IDX](FUNC) (2025+ variant)
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)"""),
        // Pattern 3: .get("n");if(m){var M=n.match... (April 2026 variant)
        Regex("""\.get\("n"\);if\([a-zA-Z0-9$]+\)\s*\{[^}]*match"""),
        // Pattern 4: String.fromCharCode(110) variant (110 = 'n')
        Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"""),
        // Pattern 5: enhanced_except_ function pattern
        Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_"""),
    )

    // ==================== EXTRACTION FUNCTIONS ====================

    /**
     * Detect if player.js uses Q-array obfuscation
     */
    fun hasQArrayObfuscation(playerJs: String): Boolean {
        val hasQArray = Q_ARRAY_PATTERN.containsMatchIn(playerJs)
        Timber.tag(TAG).d("Q-array obfuscation check: hasQArray=$hasQArray")

        if (hasQArray) {
            // Try to count Q array elements for additional info
            val match = Q_ARRAY_PATTERN.find(playerJs)
            if (match != null) {
                val start = match.range.first
                val qDefEnd = playerJs.indexOf(";", start)
                if (qDefEnd > start) {
                    val qDef = playerJs.substring(start, qDefEnd)
                    val elementCount = qDef.count { it == '}' } + 1
                    Timber.tag(TAG).d("Q-array detected with ~$elementCount elements")
                }
            }
        }
        return hasQArray
    }

    /**
     * Extract player.js hash from embedded URLs or compute from content
     */
    fun extractPlayerHash(playerJs: String): String? {
        Timber.tag(TAG).d("Extracting player hash from playerJs (${playerJs.length} chars)")

        // Try to extract from embedded URLs first
        for ((index, pattern) in PLAYER_HASH_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val hash = match.groupValues[1]
                Timber.tag(TAG).d("Player hash found via pattern $index: $hash")
                return hash
            }
        }

        // Fallback: compute hash from first 10KB of content
        val contentToHash = playerJs.take(10000)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(contentToHash.toByteArray())
        val computedHash = digest.take(4).joinToString("") { "%02x".format(it) }
        Timber.tag(TAG).d("Player hash computed from content: $computedHash")
        return computedHash
    }

    /**
     * Get the validated config for a known player.js hash from [PlayerConfigStore] (the
     * bundled asset overlaid by the remote table). Replaces the former hardcoded map — new
     * players are now added by pushing to player_configs.json, not by editing this file.
     */
    fun getHardcodedConfig(playerHash: String): HardcodedPlayerConfig? {
        val config = PlayerConfigStore.get(playerHash)
        if (config != null) {
            Timber.tag(TAG).d("Found config for hash $playerHash:")
            Timber.tag(TAG).d("  sigFunc=${config.sigFuncName}(${config.sigConstantArg}, ...)")
            Timber.tag(TAG).d("  sigExpr=${config.sigJsExpression}")
            Timber.tag(TAG).d("  nFunc=${config.nFuncName}[${config.nArrayIndex}]")
            Timber.tag(TAG).d("  signatureTimestamp=${config.signatureTimestamp}")
        } else {
            Timber.tag(TAG).w("No config for hash: $playerHash")
            Timber.tag(TAG).w("Known hashes: ${PlayerConfigStore.knownHashes().sorted().joinToString()}")
        }
        return config
    }

    /**
     * Extract signature function info from player.js.
     *
     * Validated config FIRST, legacy regex heuristics only as a fallback: config entries are
     * proven against the live CDN (HTTP 206) before they ship, while the patterns below are
     * unanchored heuristics that can false-match anywhere in the ~2 MB player JS. A heuristic
     * must never shadow a validated config — and a false positive here must not block the
     * unknown-player forced refresh in CipherDeobfuscator.
     * @param playerJs The player.js content
     * @param knownHash Optional hash for config lookup
     */
    fun extractSigFunctionInfo(playerJs: String, knownHash: String? = null): SigFunctionInfo? {
        Timber.tag(TAG).d("========== EXTRACTING SIG FUNCTION ==========")
        Timber.tag(TAG).d("Player.js size: ${playerJs.length} chars")

        // Validated config first.
        val hashToUse = knownHash ?: extractPlayerHash(playerJs)
        Timber.tag(TAG).d("Using hash for config lookup: $hashToUse (knownHash=$knownHash)")
        if (hashToUse != null) {
            val config = getHardcodedConfig(hashToUse)
            if (config != null) {
                if (config.sigJsExpression != null) {
                    Timber.tag(TAG).d("USING EXPRESSION-BASED SIG: ${config.sigJsExpression}")
                } else {
                    Timber.tag(TAG).d("USING HARDCODED SIG FUNCTION: ${config.sigFuncName}(${config.sigConstantArgs}, ...)")
                    Timber.tag(TAG).d("Sig preprocess: ${config.sigPreprocessFunc}(${config.sigPreprocessArgs}, sig)")
                }
                return SigFunctionInfo(
                    name = config.sigFuncName,
                    constantArg = config.sigConstantArg,
                    constantArgs = config.sigConstantArgs,
                    preprocessFunc = config.sigPreprocessFunc,
                    preprocessArgs = config.sigPreprocessArgs,
                    jsExpression = config.sigJsExpression,
                    isHardcoded = true
                )
            }
        }

        Timber.tag(TAG).w("No config for hash $hashToUse, trying legacy sig patterns...")

        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            Timber.tag(TAG).v("Trying sig pattern $index: ${pattern.pattern.take(60)}...")
            val match = pattern.find(playerJs)
            if (match != null) {
                val name = match.groupValues[1]
                val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
                Timber.tag(TAG).d("SIG FUNCTION FOUND via pattern $index:")
                Timber.tag(TAG).d("  name=$name, constantArg=$constArg")
                Timber.tag(TAG).d("  match context: ...${playerJs.substring(maxOf(0, match.range.first - 20), minOf(playerJs.length, match.range.last + 20))}...")
                return SigFunctionInfo(name, constArg, isHardcoded = false)
            }
        }

        Timber.tag(TAG).e("========== SIG FUNCTION EXTRACTION FAILED ==========")
        Timber.tag(TAG).e("Could not find signature deobfuscation function name")
        return null
    }

    /**
     * Extract N-transform function info from player.js.
     *
     * Validated config FIRST, legacy regex heuristics only as a fallback — same precedence
     * rationale as [extractSigFunctionInfo].
     * @param playerJs The player.js content
     * @param knownHash Optional hash for config lookup
     */
    fun extractNFunctionInfo(playerJs: String, knownHash: String? = null): NFunctionInfo? {
        Timber.tag(TAG).d("========== EXTRACTING N-FUNCTION ==========")
        Timber.tag(TAG).d("Player.js size: ${playerJs.length} chars")

        // Validated config first.
        val hashToUse = knownHash ?: extractPlayerHash(playerJs)
        Timber.tag(TAG).d("Using hash for config lookup: $hashToUse (knownHash=$knownHash)")
        if (hashToUse != null) {
            val config = getHardcodedConfig(hashToUse)
            if (config != null) {
                if (config.nJsExpression != null) {
                    Timber.tag(TAG).d("USING EXPRESSION-BASED N-FUNCTION: ${config.nJsExpression.take(60)}")
                } else {
                    Timber.tag(TAG).d("USING HARDCODED N-FUNCTION: ${config.nFuncName}[${config.nArrayIndex}]")
                    Timber.tag(TAG).d("N-function constant args: ${config.nConstantArgs}")
                }
                return NFunctionInfo(config.nFuncName, config.nArrayIndex, config.nConstantArgs, config.nJsExpression, isHardcoded = true)
            }
        }

        Timber.tag(TAG).w("No config for hash $hashToUse, trying legacy n-func patterns...")

        for ((index, pattern) in N_FUNCTION_PATTERNS.withIndex()) {
            Timber.tag(TAG).v("Trying n-func pattern $index: ${pattern.pattern.take(60)}...")
            val match = pattern.find(playerJs)
            if (match != null) {
                when (index) {
                    0 -> {
                        val name = match.groupValues[1]
                        val arrayIdx = match.groupValues[2].toIntOrNull()
                        Timber.tag(TAG).d("N-FUNCTION FOUND via pattern $index:")
                        Timber.tag(TAG).d("  name=$name, arrayIndex=$arrayIdx")
                        return NFunctionInfo(name, arrayIdx, isHardcoded = false)
                    }
                    1 -> {
                        val name = match.groupValues[2]
                        val arrayIdx = match.groupValues[3].toIntOrNull()
                        Timber.tag(TAG).d("N-FUNCTION FOUND via pattern $index:")
                        Timber.tag(TAG).d("  name=$name, arrayIndex=$arrayIdx")
                        return NFunctionInfo(name, arrayIdx, isHardcoded = false)
                    }
                    else -> {
                        // Skip patterns that match but don't expose a usable function name.
                        // E.g. the `.get("n");if(...){var M=n.match...` April 2026 variant has
                        // no capturing groups and reading groupValues[1] would throw.
                        if (pattern.toPattern().matcher("").groupCount() < 1) {
                            Timber.tag(TAG).d("N-pattern $index matched but has no capture groups; skipping")
                            continue
                        }
                        val name = match.groupValues[1]
                        Timber.tag(TAG).d("N-FUNCTION FOUND via pattern $index:")
                        Timber.tag(TAG).d("  name=$name")
                        return NFunctionInfo(name, null, isHardcoded = false)
                    }
                }
            }
        }

        Timber.tag(TAG).e("========== N-FUNCTION EXTRACTION FAILED ==========")
        Timber.tag(TAG).e("Could not find n-transform function name")
        return null
    }

    /**
     * Extract signatureTimestamp from player.js
     */
    fun extractSignatureTimestamp(playerJs: String, knownHash: String? = null): Int? {
        Timber.tag(TAG).d("Extracting signatureTimestamp...")

        // Precedence: (1) the anchored `signatureTimestamp` literal in the JS itself — it is
        // the player's own embedded field and the source config sts values are copied from
        // at authoring time, so it is immune to config typos, stale aliases, and bad remote
        // pushes (configs' sts is NOT CDN-validated, unlike sig/n); (2) the config, for
        // players lacking the literal; (3) the loose `sts` pattern, which can false-match
        // anywhere in ~2 MB of JS and must never shadow the other two.
        val anchored = ANCHORED_STS_PATTERN.find(playerJs)?.groupValues?.get(1)?.toIntOrNull()
        if (anchored != null) {
            Timber.tag(TAG).d("signatureTimestamp from player JS literal: $anchored")
            return anchored
        }

        val playerHash = knownHash ?: extractPlayerHash(playerJs)
        if (playerHash != null) {
            val config = getHardcodedConfig(playerHash)
            if (config != null) {
                Timber.tag(TAG).d("Using hardcoded signatureTimestamp: ${config.signatureTimestamp}")
                return config.signatureTimestamp
            }
        }

        val loose = LOOSE_STS_PATTERN.find(playerJs)?.groupValues?.get(1)?.toIntOrNull()
        if (loose != null) {
            Timber.tag(TAG).d("signatureTimestamp via loose sts pattern: $loose")
            return loose
        }

        Timber.tag(TAG).w("Could not extract signatureTimestamp")
        return null
    }

    /**
     * Full analysis of player.js - extracts all cipher info
     * @param playerJs The player.js content
     * @param knownHash Optional hash from PlayerJsFetcher (preferred over computed)
     */
    fun analyzePlayerJs(playerJs: String, knownHash: String? = null): PlayerAnalysis {
        Timber.tag(TAG).d("=== PLAYER.JS CIPHER ANALYSIS ===")

        // Use knownHash from PlayerJsFetcher if provided, otherwise extract/compute
        val playerHash = if (knownHash != null) {
            Timber.tag(TAG).d("Using known hash from PlayerJsFetcher: $knownHash")
            knownHash
        } else {
            extractPlayerHash(playerJs)
        }

        val hasQArray = hasQArrayObfuscation(playerJs)
        val sigInfo = extractSigFunctionInfo(playerJs, playerHash)
        val nFuncInfo = extractNFunctionInfo(playerJs, playerHash)
        val signatureTimestamp = extractSignatureTimestamp(playerJs, playerHash)

        Timber.tag(TAG).d("=== ANALYSIS SUMMARY ===")
        Timber.tag(TAG).d("Player Hash:        ${playerHash ?: "unknown"}")
        Timber.tag(TAG).d("Q-Array Obfuscated: $hasQArray")
        Timber.tag(TAG).d("Sig Function:       ${sigInfo?.name ?: "NOT FOUND"} (hardcoded=${sigInfo?.isHardcoded})")
        Timber.tag(TAG).d("Sig Constant Arg:   ${sigInfo?.constantArg}")
        Timber.tag(TAG).d("N-Function:         ${nFuncInfo?.name ?: "NOT FOUND"} (hardcoded=${nFuncInfo?.isHardcoded})")
        Timber.tag(TAG).d("N-Array Index:      ${nFuncInfo?.arrayIndex}")
        Timber.tag(TAG).d("Signature TS:       $signatureTimestamp")

        return PlayerAnalysis(
            playerHash = playerHash,
            hasQArrayObfuscation = hasQArray,
            sigInfo = sigInfo,
            nFuncInfo = nFuncInfo,
            signatureTimestamp = signatureTimestamp
        )
    }

    data class PlayerAnalysis(
        val playerHash: String?,
        val hasQArrayObfuscation: Boolean,
        val sigInfo: SigFunctionInfo?,
        val nFuncInfo: NFunctionInfo?,
        val signatureTimestamp: Int?
    )
}

/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

/** Spelling rules cannot resolve silent letters in some Thai words without a pronunciation dictionary. */

private val initialConsonant =
    mapOf(
        '\u0e01' to "k",
        '\u0e02' to "kh",
        '\u0e03' to "kh",
        '\u0e04' to "kh",
        '\u0e05' to "kh",
        '\u0e06' to "kh",
        '\u0e07' to "ng",
        '\u0e08' to "ch",
        '\u0e09' to "ch",
        '\u0e0a' to "ch",
        '\u0e0b' to "s",
        '\u0e0c' to "ch",
        '\u0e0d' to "y",
        '\u0e0e' to "d",
        '\u0e0f' to "t",
        '\u0e10' to "th",
        '\u0e11' to "th",
        '\u0e12' to "th",
        '\u0e13' to "n",
        '\u0e14' to "d",
        '\u0e15' to "t",
        '\u0e16' to "th",
        '\u0e17' to "th",
        '\u0e18' to "th",
        '\u0e19' to "n",
        '\u0e1a' to "b",
        '\u0e1b' to "p",
        '\u0e1c' to "ph",
        '\u0e1d' to "f",
        '\u0e1e' to "ph",
        '\u0e1f' to "f",
        '\u0e20' to "ph",
        '\u0e21' to "m",
        '\u0e22' to "y",
        '\u0e23' to "r",
        '\u0e24' to "rue",
        '\u0e25' to "l",
        '\u0e26' to "lue",
        '\u0e27' to "w",
        '\u0e28' to "s",
        '\u0e29' to "s",
        '\u0e2a' to "s",
        '\u0e2b' to "h",
        '\u0e2c' to "l",
        '\u0e2d' to "",
        '\u0e2e' to "h",
    )

private val finalConsonant =
    mapOf(
        '\u0e01' to "k",
        '\u0e02' to "k",
        '\u0e03' to "k",
        '\u0e04' to "k",
        '\u0e05' to "k",
        '\u0e06' to "k",
        '\u0e07' to "ng",
        '\u0e08' to "t",
        '\u0e09' to "t",
        '\u0e0a' to "t",
        '\u0e0b' to "t",
        '\u0e0c' to "t",
        '\u0e0d' to "n",
        '\u0e0e' to "t",
        '\u0e0f' to "t",
        '\u0e10' to "t",
        '\u0e11' to "t",
        '\u0e12' to "t",
        '\u0e13' to "n",
        '\u0e14' to "t",
        '\u0e15' to "t",
        '\u0e16' to "t",
        '\u0e17' to "t",
        '\u0e18' to "t",
        '\u0e19' to "n",
        '\u0e1a' to "p",
        '\u0e1b' to "p",
        '\u0e1c' to "p",
        '\u0e1d' to "p",
        '\u0e1e' to "p",
        '\u0e1f' to "p",
        '\u0e20' to "p",
        '\u0e21' to "m",
        '\u0e22' to "i",
        '\u0e23' to "n",
        '\u0e25' to "n",
        '\u0e27' to "o",
        '\u0e2c' to "n",
        '\u0e28' to "t",
        '\u0e29' to "t",
        '\u0e2a' to "t",
        // Ho hip and o ang have no final sound, so omit them.
    )

private const val SARA_A = '\u0e30'
private const val MAI_HAN_AKAT = '\u0e31'
private const val SARA_AA = '\u0e32'
private const val SARA_AM = '\u0e33'
private const val SARA_I = '\u0e34'
private const val SARA_II = '\u0e35'
private const val SARA_UE = '\u0e36'
private const val SARA_UEE = '\u0e37'
private const val SARA_U = '\u0e38'
private const val SARA_UU = '\u0e39'
private const val SARA_E = '\u0e40'
private const val SARA_AE = '\u0e41'
private const val SARA_O = '\u0e42'
private const val SARA_AI_MAIMUAN = '\u0e43'
private const val SARA_AI_MAIMALAI = '\u0e44'
private const val MAITAIKHU = '\u0e47' // The shortening mark has no sound of its own.
private const val O_ANG = '\u0e2d'
private const val WO_WAEN = '\u0e27'
private const val YO_YAK = '\u0e22'
private const val NIKHAHIT = '\u0e4d' // Nikhahit can combine with sara aa.
private const val HO_NAM = '\u0e2b' // Ho hip can act as a silent tone-class modifier.
private const val THANTHAKHAT = '\u0e4c' // Thanthakhat silences the preceding consonant.

private val leadingVowels = setOf(SARA_E, SARA_AE, SARA_O, SARA_AI_MAIMUAN, SARA_AI_MAIMALAI)
private val toneMarks = setOf('\u0e48', '\u0e49', '\u0e4a', '\u0e4b')
private val otherSkip = setOf('\u0e4e', '\u0e4f')
private val skipChars = toneMarks + otherSkip

private val topBottomVowels = setOf(SARA_I, SARA_II, SARA_UE, SARA_UEE, SARA_U, SARA_UU, MAI_HAN_AKAT, NIKHAHIT)

private val clusterSecond = mapOf('\u0e23' to "r", '\u0e25' to "l", '\u0e27' to "w")

// Only these consonants can lead an r, l or w cluster.
private val clusterLeaders = setOf('\u0e01', '\u0e02', '\u0e04', '\u0e15', '\u0e1a', '\u0e1b', '\u0e1c', '\u0e1e', '\u0e1f', '\u0e14')

// A silent leading ho hip raises the tone class of these low-class sonorants.
private val lowSonorantsAfterHo = setOf('\u0e07', '\u0e0d', '\u0e19', '\u0e21', '\u0e22', '\u0e23', '\u0e25', '\u0e27')

private fun Char.isThai() = this in '\u0e00'..'\u0e7f'

private fun StringBuilder.addSpace() {
    if (isNotEmpty() && last() != ' ') append(' ')
}

internal fun romanizeThai(text: String): String {
    val n = text.length
    val out = StringBuilder(n * 2)
    var i = 0

    fun leadingVowelFallback(ch: Char): String =
        when (ch) {
            SARA_E -> "e"
            SARA_AE -> "ae"
            SARA_O -> "o"
            SARA_AI_MAIMUAN, SARA_AI_MAIMALAI -> "ai"
            else -> ""
        }

    while (i < n) {
        val ch = text[i]

        if (!ch.isThai()) {
            out.append(ch)
            i++
            continue
        }

        if (ch in '\u0e50'..'\u0e59') {
            out.append('0' + (ch - '\u0e50'))
            i++
            continue
        }

        if (ch == '\u0e2f' || ch == '\u0e46') {
            out.addSpace()
            out.append(ch)
            i++
            continue
        }

        if (ch !in initialConsonant && ch !in leadingVowels) {
            i++
            continue
        }

        out.addSpace()

        val hasLeadingVowel = ch in leadingVowels
        val leadingCh = if (hasLeadingVowel) ch else null
        if (hasLeadingVowel) i++

        if (i >= n || text[i] !in initialConsonant) {
            if (leadingCh != null) out.append(leadingVowelFallback(leadingCh))
            continue
        }

        var onsetCh = text[i]
        var onset = initialConsonant.getValue(onsetCh)
        i++

        // A silent leading ho hip modifies the next sonorant; leading o ang modifies yo yak in a fixed word set.
        if (onsetCh == HO_NAM && i < n && text[i] in lowSonorantsAfterHo) {
            onsetCh = text[i]
            onset = initialConsonant.getValue(onsetCh)
            i++
        } else if (!hasLeadingVowel && onsetCh == O_ANG && i < n && text[i] == YO_YAK) {
            onsetCh = text[i]
            onset = initialConsonant.getValue(onsetCh)
            i++
        }

        if (i < n && text[i] == THANTHAKHAT) {
            i++
            continue
        }

        if (onsetCh in clusterLeaders && i < n && text[i] in clusterSecond) {
            val next2 = if (i + 1 < n) text[i + 1] else null
            val looksLikeCluster =
                next2 == null ||
                    next2 in topBottomVowels || next2 == SARA_A || next2 == SARA_AA ||
                    next2 == SARA_AM || next2 in skipChars || next2 == THANTHAKHAT ||
                    next2 in finalConsonant || next2 in leadingVowels
            if (looksLikeCluster) {
                onset += clusterSecond.getValue(text[i])
                i++
                if (i < n && text[i] == THANTHAKHAT) {
                    i++
                    continue
                }
            }
        }

        var vowel = StringBuilder()
        var coda = ""
        var hasVowel = false

        if (hasLeadingVowel) {
            when {
                i < n && text[i] == SARA_AA -> {
                    if (leadingCh == SARA_E) {
                        vowel.append("ao")
                        i++
                    } else {
                        vowel.append(leadingVowelFallback(leadingCh!!))
                    }
                }

                i < n && text[i] == SARA_A -> {
                    vowel.append(leadingVowelFallback(leadingCh!!))
                    i++
                }

                i < n && text[i] == SARA_UEE -> {
                    // A tone mark can separate the vowel sign from its carrier in the uea sequence.
                    vowel.append("uea")
                    i++
                    var j = i
                    while (j < n && text[j] in skipChars) j++
                    if (j < n && text[j] == O_ANG) i = j + 1
                }

                i < n && text[i] == SARA_II && i + 1 < n && text[i + 1] == YO_YAK -> {
                    vowel.append("ia")
                    i += 2
                }

                i < n && text[i] == O_ANG -> {
                    vowel.append("oe")
                    i++
                    if (i < n && text[i] == SARA_A) i++
                    if (i < n && text[i] == YO_YAK && (i + 1 >= n || text[i + 1] !in initialConsonant)) {
                        vowel = StringBuilder("oei")
                        i++
                    }
                }

                i < n && text[i] == SARA_I -> {
                    vowel.append("oe")
                    i++
                }

                else -> {
                    vowel.append(leadingVowelFallback(leadingCh!!))
                }
            }

            // Ai-vowel syllables have no pronounced coda; a trailing yo yak can be silent.
            if (leadingCh != SARA_AI_MAIMUAN && leadingCh != SARA_AI_MAIMALAI &&
                i < n && text[i] == YO_YAK
            ) {
                val peek = i + 1
                if (peek >= n || !text[peek].isThai() || text[peek] in skipChars) i++
            }

            if (leadingCh != SARA_AI_MAIMUAN && leadingCh != SARA_AI_MAIMALAI &&
                i < n && text[i] in finalConsonant
            ) {
                val nxt = text[i]
                val after = i + 1
                if (after < n && text[after] == THANTHAKHAT) {
                    i += 2
                } else {
                    coda = finalConsonant.getValue(nxt)
                    i++
                    while (i < n && text[i] in skipChars) i++
                    if (i < n && text[i] == THANTHAKHAT) i++
                }
            }
        } else {
            loop@ while (i < n) {
                val nxt = text[i]
                when {
                    nxt in skipChars -> {
                        i++
                    }

                    nxt == SARA_A -> {
                        vowel.append("a")
                        hasVowel = true
                        i++
                        break@loop
                    }

                    nxt == MAI_HAN_AKAT -> {
                        if (i + 1 < n && text[i + 1] == WO_WAEN &&
                            (i + 2 >= n || text[i + 2] !in initialConsonant || text[i + 2] in finalConsonant)
                        ) {
                            vowel.append("ua")
                            hasVowel = true
                            i += 2
                        } else {
                            vowel.append("a")
                            hasVowel = true
                            i++
                        }
                    }

                    nxt == SARA_I || nxt == SARA_II || nxt == SARA_UE || nxt == SARA_UEE ||
                        nxt == SARA_U || nxt == SARA_UU -> {
                        vowel.append(
                            when (nxt) {
                                SARA_I, SARA_II -> "i"
                                SARA_UE, SARA_UEE -> "ue"
                                else -> "u"
                            },
                        )
                        hasVowel = true
                        i++
                    }

                    nxt == NIKHAHIT -> {
                        i++
                    }

                    nxt == SARA_AM -> {
                        vowel.append("am")
                        hasVowel = true
                        i++
                        break@loop
                    }

                    nxt == SARA_AA -> {
                        vowel.append("a")
                        hasVowel = true
                        i++
                        if (i < n && text[i] == WO_WAEN && (i + 1 >= n || text[i + 1] !in initialConsonant)) {
                            vowel.append("o")
                            i++
                        }
                        break@loop
                    }

                    nxt == MAITAIKHU -> {
                        i++
                    }

                    nxt == WO_WAEN && !hasVowel -> {
                        // Bare consonant plus wo waen and yo yak gives uai; wo waen alone needs a pronunciation dictionary.
                        if (i + 1 < n && text[i + 1] == YO_YAK &&
                            (i + 2 >= n || text[i + 2] !in initialConsonant || text[i + 2] in finalConsonant)
                        ) {
                            vowel.append("uai")
                            hasVowel = true
                            i += 2
                        }
                        break@loop
                    }

                    nxt in leadingVowels -> {
                        break@loop
                    }

                    nxt == O_ANG && !hasVowel -> {
                        val peek = i + 1
                        val isVowelNucleus =
                            peek >= n || text[peek] in finalConsonant ||
                                text[peek] in skipChars || text[peek] == THANTHAKHAT || !text[peek].isThai()
                        if (isVowelNucleus) {
                            vowel.append("o")
                            hasVowel = true
                            i++
                        }
                        break@loop
                    }

                    else -> {
                        break@loop
                    }
                }
            }

            // A consonant carrying a vowel sign starts the next syllable rather than closing this one.
            if (i < n && text[i] in finalConsonant) {
                val nxt = text[i]
                val after = i + 1
                val afterCh = if (after < n) text[after] else null
                val startsNextSyllable =
                    afterCh == MAI_HAN_AKAT || afterCh in topBottomVowels ||
                        afterCh == SARA_A || afterCh == SARA_AA || afterCh == SARA_AM ||
                        (nxt in clusterLeaders && afterCh in clusterSecond)
                when {
                    startsNextSyllable -> {}

                    after < n && afterCh == THANTHAKHAT -> {
                        i += 2
                    }

                    else -> {
                        coda = finalConsonant.getValue(nxt)
                        i++
                        while (i < n && text[i] in skipChars) i++
                        if (i < n && text[i] == THANTHAKHAT) i++
                    }
                }
            }

            if (!hasVowel) {
                // Unwritten vowels are lexical; approximate closed syllables with o and open syllables with a.
                vowel.append(if (coda.isNotEmpty()) "o" else "a")
            }
        }

        out.append(onset).append(vowel).append(coda)
    }

    return out.toString().replace(Regex("\\s+"), " ").trim()
}

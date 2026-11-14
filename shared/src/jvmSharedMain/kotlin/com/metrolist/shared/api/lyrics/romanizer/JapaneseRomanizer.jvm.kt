package com.metrolist.shared.api.lyrics.romanizer

import com.atilika.kuromoji.dict.CharacterDefinitions
import com.atilika.kuromoji.dict.ConnectionCosts
import com.atilika.kuromoji.dict.InsertedDictionary
import com.atilika.kuromoji.dict.TokenInfoDictionary
import com.atilika.kuromoji.dict.UnknownDictionary
import com.atilika.kuromoji.ipadic.Tokenizer
import com.atilika.kuromoji.trie.DoubleArrayTrie
import com.atilika.kuromoji.util.ResourceResolver
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal actual val kanaRomanizationNeedsJapaneseDictionary = true

internal actual suspend fun prepareJapaneseRomanizer(): Boolean =
    withContext(Dispatchers.IO) {
        try {
            dictionaryInstaller().install() != null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PlaybackEventLog.error(
                PlaybackEventKind.NETWORK,
                "JapaneseRomanizer",
                "Japanese dictionary preparation failed",
                error,
            )
            false
        }
    }

internal actual fun romanizeJapanese(text: String): String {
    if (text.isBlank()) return text
    val tokenizer = installedTokenizer() ?: return if (containsJapaneseKanji(text)) text else LyricsRomanizer.romanizeKana(text)
    val tokens = tokenizer.tokenize(text)
    val readings =
        tokens.mapNotNull { token ->
            val reading =
                token.pronunciation?.takeIf { it.isNotBlank() && it != "*" }
                    ?: token.reading?.takeIf { it.isNotBlank() && it != "*" }
            if (reading == null && containsJapaneseKanji(token.surface)) return text
            (reading ?: token.surface).takeIf(String::isNotBlank)
        }
    val joined =
        buildString {
            readings.forEachIndexed { i, reading ->
                append(reading)
                if (i < readings.lastIndex && reading.last() != 'っ' && reading.last() != 'ッ') append(' ')
            }
        }
    return LyricsRomanizer
        .romanizeKana(joined)
        .replace(Regex(" {2,}"), " ")
        .replace(Regex("\\s+([，。！？,.!?;:])"), "$1")
        .replace(Regex("([，。！？,.!?;:])(?=[a-zA-Z0-9])"), "$1 ")
        .trim()
}

private val tokenizerLock = Any()

@Volatile private var cachedTokenizer: Tokenizer? = null

private fun installedTokenizer(): Tokenizer? =
    try {
        cachedTokenizer ?: synchronized(tokenizerLock) {
            cachedTokenizer ?: dictionaryInstaller().installedDirectory()?.let { directory ->
                try {
                    InstalledDictionaryBuilder(directory).build().also { cachedTokenizer = it }
                } catch (_: Exception) {
                    null
                }
            }
        }
    } catch (_: Exception) {
        null
    }

private fun dictionaryInstaller() = JapaneseDictionaryInstaller(japaneseDictionaryStorageDirectory(), ::downloadDictionaryArtifact)

private fun downloadDictionaryArtifact(): InputStream {
    val connection = URI(JAPANESE_DICTIONARY_URL).toURL().openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.connect()
        if (connection.responseCode != HttpsURLConnection.HTTP_OK) {
            error("Dictionary download failed with HTTP ${connection.responseCode}")
        }
        if (connection.contentLengthLong > 20L * 1024 * 1024) {
            error("Dictionary archive exceeds size limit")
        }
        val stream = connection.inputStream
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    } catch (error: Exception) {
        connection.disconnect()
        throw error
    }
}

private class InstalledDictionaryBuilder(
    private val dictionaryDirectory: File,
) : Tokenizer.Builder() {
    override fun loadDictionaries() {
        resolver = ResourceResolver { name -> File(dictionaryDirectory, name).inputStream() }
        doubleArrayTrie = DoubleArrayTrie.newInstance(resolver)
        connectionCosts = ConnectionCosts.newInstance(resolver)
        tokenInfoDictionary = TokenInfoDictionary.newInstance(resolver)
        characterDefinitions = CharacterDefinitions.newInstance(resolver)
        unknownDictionary = UnknownDictionary.newInstance(resolver, characterDefinitions, totalFeatures)
        insertedDictionary = InsertedDictionary(totalFeatures)
    }
}

internal expect fun japaneseDictionaryStorageDirectory(): File

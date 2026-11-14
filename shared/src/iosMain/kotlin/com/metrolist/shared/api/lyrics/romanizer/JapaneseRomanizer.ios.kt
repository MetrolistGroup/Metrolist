package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.deviceconnect.DeviceCrypto
import com.metrolist.shared.network.HttpFailureLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue
import platform.JavaScriptCore.objectForKeyedSubscript
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal actual val kanaRomanizationNeedsJapaneseDictionary = false

internal actual suspend fun prepareJapaneseRomanizer(): Boolean =
    withContext(Dispatchers.Default) {
        dictionaryMutex.withLock {
            if (engine.isReady()) return@withLock true
            try {
                val directory = withTimeoutOrNull(MAX_DOWNLOAD_MILLIS) { dictionaryInstaller.install() } ?: return@withLock false
                engine.initialize(directory)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
    }

internal actual fun romanizeJapanese(text: String): String {
    if (text.isBlank()) return text
    return engine.romanize(text) ?: if (containsJapaneseKanji(text)) text else LyricsRomanizer.romanizeKana(text)
}

private const val KUROMOJI_VERSION = "0.1.2"
private const val KUROMOJI_COMMIT = "d0357c1427acb912815fc8ab59f284dd08bad0fa"
private const val MAX_DICTIONARY_BYTES = 20L * 1024 * 1024
private const val MAX_DOWNLOAD_MILLIS = 120_000L
private const val READY_MARKER = ".ready"
private const val MANIFEST_NAME = "manifest"
private const val DICTIONARY_URL = "https://raw.githubusercontent.com/takuyaa/kuromoji.js/$KUROMOJI_COMMIT/dict/"

private data class DictionaryFile(
    val name: String,
    val sha256: String,
    val maxBytes: Int,
)

private val dictionaryFiles =
    listOf(
        DictionaryFile("base.dat.gz", "0803327762e1c93ca731e4319ab8343340f2806bb84941207782cde9d2d5a8eb", 4_000_000),
        DictionaryFile("cc.dat.gz", "02b7631be0d4de3a1a75cd9f9cc51536e4f94c9e6b389b813e06ba0f6e7de765", 1_800_000),
        DictionaryFile("check.dat.gz", "193ae0035fff6fe812b58d9ee730e7a7d7ee601d918481ce51075c58114f6cc9", 3_200_000),
        DictionaryFile("tid.dat.gz", "d43d831cb6fb0f0a411739cd287a6d5e998e121a8daca614df14a81a0dcac586", 1_700_000),
        DictionaryFile("tid_map.dat.gz", "33efd5ffd87a70f669add093fa39dee44341d58f940844ef107c8fd98bb795b2", 1_600_000),
        DictionaryFile("tid_pos.dat.gz", "60dbfc99a6ab993f30c5dab648bec6ad7f9aaefa5c14e1843837d95e509f8895", 6_200_000),
        DictionaryFile("unk_char.dat.gz", "9a8e86fd9aff32d323fbb59f5a7006f05927a11f8173c90712cc56293aeb3225", 1_000),
        DictionaryFile("unk_compat.dat.gz", "50f60aa29bc2e86c2903ab8c825bb6fa604d2b294d96941c1d3924259791899d", 1_000),
        DictionaryFile("unk.dat.gz", "f7f991cdeb9bfd3e9c0e4577cc50ee0815a11c508cccd444a9d3ab3c81521100", 12_000),
        DictionaryFile("unk_invoke.dat.gz", "6b210889548457c3006913afd12c8b525562255f2709e404604be9614a25e94c", 2_000),
        DictionaryFile("unk_map.dat.gz", "6df12460e5477230bb6fd9641def918b699fc0a8868016b6c9f794488630509b", 2_000),
        DictionaryFile("unk_pos.dat.gz", "5b183a29f281acc7e0542beca47b83f7985047c0a2d27e78a66f32276be5ad11", 12_000),
    )
private val dictionaryManifest =
    buildString {
        appendLine(KUROMOJI_VERSION)
        appendLine(KUROMOJI_COMMIT)
        dictionaryFiles.forEach { appendLine("${it.name} ${it.sha256}") }
    }
private val dictionaryMutex = Mutex()
private val dictionaryHttpClient =
    HttpClient(Darwin) {
        install(HttpFailureLog)
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

@OptIn(ExperimentalForeignApi::class)
private class IosDictionaryInstaller {
    private val storagePath: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        val appSupport = requireNotNull(paths.firstOrNull() as? String)
        "$appSupport/metrolist/japanese-dictionary"
    }
    private val targetPath by lazy { "$storagePath/kuromoji-$KUROMOJI_VERSION-${KUROMOJI_COMMIT.take(12)}" }

    suspend fun install(): String? {
        installedDirectory()?.let { return it }
        return installAtomically()
    }

    private fun installedDirectory(): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(targetPath) || !excludeFromBackup(storagePath)) return null
        return targetPath.takeIf(::isInstalled)
    }

    private fun isInstalled(directory: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath("$directory/$READY_MARKER") &&
            readText("$directory/$MANIFEST_NAME") == dictionaryManifest &&
            dictionaryFiles.all { spec ->
                val bytes = readBytes("$directory/${spec.name}")
                bytes != null && bytes.size in 1..spec.maxBytes && sha256(bytes) == spec.sha256
            }

    private suspend fun installAtomically(): String? {
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.createDirectoryAtPath(storagePath, withIntermediateDirectories = true, attributes = null, error = null) ||
            !excludeFromBackup(storagePath)
        ) {
            return null
        }
        val stagingPath = "$storagePath/.kuromoji-${KUROMOJI_COMMIT.take(12)}-${NSUUID.UUID().UUIDString}.tmp"
        try {
            removeStaleStagingDirectories()
            if (!fileManager.createDirectoryAtPath(stagingPath, withIntermediateDirectories = false, attributes = null, error = null)) {
                return null
            }
            var totalBytes = 0L
            for (spec in dictionaryFiles) {
                val bytes = download(spec)
                totalBytes += bytes.size
                if (totalBytes > MAX_DICTIONARY_BYTES || sha256(bytes) != spec.sha256) return null
                check(writeBytes("$stagingPath/${spec.name}", bytes))
            }
            check(writeBytes("$stagingPath/$MANIFEST_NAME", dictionaryManifest.encodeToByteArray()))
            check(writeBytes("$stagingPath/$READY_MARKER", byteArrayOf(1)))
            if (fileManager.fileExistsAtPath(targetPath)) {
                check(fileManager.removeItemAtPath(targetPath, error = null))
            }
            check(fileManager.moveItemAtPath(stagingPath, toPath = targetPath, error = null))
            return targetPath.takeIf(::isInstalled)
        } finally {
            if (fileManager.fileExistsAtPath(stagingPath)) fileManager.removeItemAtPath(stagingPath, error = null)
        }
    }

    private suspend fun download(spec: DictionaryFile): ByteArray =
        dictionaryHttpClient.prepareGet(DICTIONARY_URL + spec.name).execute { response ->
            check(response.status == HttpStatusCode.OK) { "Dictionary download failed" }
            check(response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it in 1..spec.maxBytes.toLong() } != false) {
                "Dictionary resource exceeds size limit"
            }
            response.readBodyLimited(spec.maxBytes)
        }

    private fun removeStaleStagingDirectories() {
        val fileManager = NSFileManager.defaultManager
        val entries = fileManager.contentsOfDirectoryAtPath(storagePath, error = null) ?: return
        entries
            .filterIsInstance<String>()
            .filter { it.startsWith(".kuromoji-") && it.endsWith(".tmp") }
            .forEach { fileManager.removeItemAtPath("$storagePath/$it", error = null) }
    }
}

private suspend fun HttpResponse.readBodyLimited(maxBytes: Int): ByteArray {
    val channel = bodyAsChannel()
    val buffer = ByteArray(maxBytes + 1)
    var size = 0
    try {
        while (size < buffer.size) {
            val read = channel.readAvailable(buffer, size, buffer.size - size)
            if (read < 0) break
            if (read == 0) continue
            size += read
        }
        check(size in 1..maxBytes) { "Dictionary resource is empty or exceeds size limit" }
        return buffer.copyOf(size)
    } finally {
        channel.cancel(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readBytes(path: String): ByteArray? {
    val data = NSFileManager.defaultManager.contentsAtPath(path) ?: return null
    val size = data.length.toInt()
    if (size <= 0) return byteArrayOf()
    val source = data.bytes?.reinterpret<ByteVarOf<Byte>>() ?: return null
    return source.readBytes(size)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeBytes(
    path: String,
    bytes: ByteArray,
): Boolean =
    bytes.usePinned { pinned ->
        val data = NSData.create(pinned.addressOf(0), bytes.size.toULong())
        data.writeToFile(path, atomically = true)
    }

private fun readText(path: String): String? = readBytes(path)?.decodeToString()

@OptIn(ExperimentalForeignApi::class)
private fun excludeFromBackup(path: String): Boolean =
    NSURL.fileURLWithPath(path).setResourceValue(true, NSURLIsExcludedFromBackupKey, error = null)

private fun sha256(bytes: ByteArray): String =
    DeviceCrypto.sha256(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

@OptIn(ExperimentalForeignApi::class)
private class IosKuromojiEngine {
    private val lock = NSRecursiveLock()
    private var context: JSContext? = null
    private var initialized = false

    fun isReady(): Boolean = lock.withLock { initialized }

    @OptIn(ExperimentalEncodingApi::class)
    fun initialize(dictionaryDirectory: String): Boolean =
        lock.withLock {
            if (initialized) return true
            val bundlePath = requireNotNull(NSBundle.mainBundle.resourcePath)
            val bootstrap = requireNotNull(readText("$bundlePath/kuromoji-bootstrap.js"))
            val engine = requireNotNull(readText("$bundlePath/kuromoji.js"))
            val js = requireNotNull(JSContext())
            js.evaluate(bootstrap)
            js.evaluate(engine)
            try {
                dictionaryFiles.forEach { spec ->
                    val bytes = readBytes("$dictionaryDirectory/${spec.name}")
                    check(bytes != null && bytes.size in 1..spec.maxBytes && sha256(bytes) == spec.sha256)
                    addDictionary(js, spec.name, Base64.Default.encode(bytes))
                }
                js.call("__metrolistStartBuild")
                repeat(100) {
                    js.call("__metrolistDrainTimers")
                    if (js.call("__metrolistIsBuilt").toBool()) {
                        js.call("__metrolistReleaseDictionaryData")
                        context = js
                        initialized = true
                        return true
                    }
                    val buildErrorValue = js.call("__metrolistBuildError")
                    if (!buildErrorValue.isNull) error(requireNotNull(buildErrorValue.toString_()))
                }
                false
            } finally {
                if (!initialized) runCatching { js.call("__metrolistReleaseDictionaryData") }
            }
        }

    fun romanize(text: String): String? =
        lock.withLock {
            val js = context ?: return null
            val json = requireNotNull(js.call("__metrolistTokenize", text).toString_())
            val tokens = Json.parseToJsonElement(json).jsonArray
            val words =
                tokens.mapNotNull { token ->
                    val values = token.jsonArray
                    val surface = values[0].jsonPrimitive.content
                    val reading =
                        values
                            .getOrNull(1)
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() && it != "*" }
                            ?: values
                                .getOrNull(2)
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.takeIf { it.isNotBlank() && it != "*" }
                    if (reading == null && containsJapaneseKanji(surface)) return null
                    LyricsRomanizer.romanizeKana(reading ?: surface).takeIf(String::isNotBlank)
                }
            words
                .joinToString(" ")
                .replace(Regex(" {2,}"), " ")
                .replace(Regex("\\s+([，。！？,.!?;:])"), "$1")
                .replace(Regex("([，。！？,.!?;:])(?=[a-zA-Z0-9])"), "$1 ")
                .trim()
        }

    private fun addDictionary(
        js: JSContext,
        name: String,
        base64: String,
    ) {
        js.call("__metrolistAddDictionary", name, base64)
    }

    private fun JSContext.evaluate(script: String) {
        exception = null
        evaluateScript(script)
        exception?.let { error(it.toString()) }
    }

    private fun JSContext.call(
        name: String,
        vararg arguments: Any?,
    ): JSValue {
        exception = null
        val result = requireNotNull(objectForKeyedSubscript(name)).callWithArguments(arguments.toList())
        exception?.let { error(it.toString()) }
        return requireNotNull(result)
    }
}

private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

private val engine = IosKuromojiEngine()
private val dictionaryInstaller = IosDictionaryInstaller()
